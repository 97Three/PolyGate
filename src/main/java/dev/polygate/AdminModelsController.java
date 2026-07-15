package dev.polygate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "polygate.database.enabled", havingValue = "true")
class AdminModelsController {
  private static final Set<String> PROVIDER_FIELDS =
      Set.of(
          "id",
          "displayName",
          "protocol",
          "baseUrl",
          "apiKeyEnv",
          "clearApiKeyEnv",
          "apiKeyRequired",
          "models",
          "defaultModel",
          "disabled",
          "modelPrefixes",
          "passthroughUnknownModels",
          "maxTokensField",
          "deduplicateStreamText",
          "bufferStreamText",
          "fidelityMode",
          "toolUse",
          "modelProfileDefaults",
          "modelProfiles",
          "staticHeaders",
          "requestTimeoutMs",
          "streamIdleTimeoutMs",
          "retry",
          "pricing",
          "modelPricing",
          "trustUpstreamCost",
          "governance",
          "tokenCounting",
          "reasoning",
          "sampling");
  private final JdbcTemplate database;
  private final AdminAuthService auth;
  private final GatewaySettings settings;
  private final ObjectMapper json;
  private final ClientAuthService clientAuth;
  private final RoutingService routes;
  private final GovernanceService governance;
  private final ProviderCredentialService credentials;
  private final ProviderStatus providerStatus;

  AdminModelsController(
      JdbcTemplate database,
      AdminAuthService auth,
      GatewaySettings settings,
      ObjectMapper json,
      ClientAuthService clientAuth,
      RoutingService routes,
      GovernanceService governance,
      ProviderCredentialService credentials,
      ProviderStatus providerStatus) {
    this.database = database;
    this.auth = auth;
    this.settings = settings;
    this.json = json;
    this.clientAuth = clientAuth;
    this.routes = routes;
    this.governance = governance;
    this.credentials = credentials;
    this.providerStatus = providerStatus;
  }

  @GetMapping("/admin/providers")
  @Transactional(readOnly = true)
  List<Map<String, Object>> providers(
      @RequestHeader HttpHeaders headers, @RequestParam(required = false) java.util.UUID apiKeyId) {
    var actor = auth.requireSession(headers);
    var all = providersWithoutAuth();
    if ("admin".equals(actor.get("role")) && apiKeyId == null) return all;
    var allowed = catalog(headers, apiKeyId);
    List<Map<String, Object>> result = new ArrayList<>();
    for (var provider : all) {
      String id = provider.get("id").toString();
      var models =
          allowed.stream()
              .filter(route -> id.equals(route.providerId()))
              .map(RoutingService.Route::upstreamModel)
              .distinct()
              .toList();
      if (models.isEmpty()) continue;
      Map<String, Object> row = new HashMap<>(provider);
      row.put("models", models);
      if (!models.contains(row.get("defaultModel"))) row.put("defaultModel", models.getFirst());
      row.put("apiKeyEnv", null);
      row.remove("staticHeaders");
      row.remove("credentials");
      row.remove("activeCredentialId");
      row.remove("health");
      row.put(
          "modelInventory",
          ((List<?>) provider.get("modelInventory"))
              .stream()
                  .filter(
                      item ->
                          item instanceof Map<?, ?> inventory
                              && models.contains(inventory.get("model")))
                  .toList());
      result.add(row);
    }
    return result;
  }

  @Transactional(readOnly = true)
  List<Map<String, Object>> providers(HttpHeaders headers) {
    return providers(headers, null);
  }

  private List<RoutingService.Route> catalog(HttpHeaders headers, java.util.UUID keyId) {
    var actor = auth.requireSession(headers);
    List<java.util.UUID> ids;
    if (keyId != null) {
      var owners = database.queryForList("SELECT user_id FROM gateway_api_key WHERE id=?", keyId);
      if (owners.isEmpty()
          || !"admin".equals(actor.get("role"))
              && !actor
                  .get("id")
                  .toString()
                  .equals(java.util.Objects.toString(owners.getFirst().get("user_id"), "")))
        throw new GatewayException(
            HttpStatus.FORBIDDEN,
            "forbidden_error",
            "key_permission_denied",
            "Cannot read this API key catalog");
      ids = List.of(keyId);
    } else
      ids =
          database.query(
              "SELECT id FROM gateway_api_key WHERE user_id=? AND enabled=true AND"
                  + " rotation_state='active' AND (expires_at IS NULL OR expires_at>now())",
              (rs, i) -> rs.getObject(1, java.util.UUID.class),
              java.util.UUID.fromString(actor.get("id").toString()));
    List<RoutingService.Route> allowed = new ArrayList<>();
    for (var id : ids) {
      ClientAuthService.Principal principal;
      try {
        principal = clientAuth.catalogPrincipal(id);
      } catch (GatewayException e) {
        continue;
      }
      for (var model : routes.models())
        try {
          var route = routes.resolve(model.get("id"));
          clientAuth.authorizeRoute(principal, route);
          governance.enforce(principal, route, new HttpHeaders());
          allowed.add(route);
        } catch (GatewayException ignored) {
        }
    }
    return allowed;
  }

  private List<Map<String, Object>> providersWithoutAuth() {
    return database.query(
        "SELECT id, display_name, protocol, base_url, credential_env, enabled, config::text AS"
            + " config FROM gateway_provider ORDER BY id",
        (rs, index) -> {
          String id = rs.getString("id");
          ObjectNode config;
          try {
            config = (ObjectNode) json.readTree(rs.getString("config"));
          } catch (Exception e) {
            throw new IllegalStateException("Invalid provider configuration", e);
          }
          List<String> models =
              database.query(
                  "SELECT upstream_id FROM gateway_model WHERE provider_id=? AND enabled=true ORDER"
                      + " BY created_at,upstream_id",
                  (model, i) -> model.getString(1),
                  id);
          List<Map<String, Object>> inventory = new ArrayList<>();
          String defaultModel =
              config.path("defaultModel").asText(models.isEmpty() ? "" : models.getFirst());
          inventory.addAll(
              database.query(
                  "SELECT upstream_id,enabled,profile::text FROM gateway_model WHERE provider_id=?"
                      + " ORDER BY upstream_id",
                  (model, i) -> {
                    Map<String, Object> row = new HashMap<>();
                    try {
                      JsonNode profile =
                          ModelCapabilities.profile(
                              config,
                              model.getString("upstream_id"),
                              json.readTree(model.getString("profile")));
                      profile
                          .fields()
                          .forEachRemaining(
                              entry ->
                                  row.put(
                                      entry.getKey(),
                                      json.convertValue(entry.getValue(), Object.class)));
                    } catch (Exception e) {
                      throw new IllegalStateException("Invalid model profile", e);
                    }
                    row.put("model", model.getString("upstream_id"));
                    row.put("status", model.getBoolean("enabled") ? "active" : "disabled");
                    row.put("default", defaultModel.equals(model.getString("upstream_id")));
                    return row;
                  },
                  id));
          String env = rs.getString("credential_env");
          boolean configured =
              "POLYGATE_UPSTREAM_KEY".equals(env)
                  ? !settings.upstreamKey.isBlank()
                  : System.getenv(env) != null && !System.getenv(env).isBlank();
          Map<String, Object> provider = new HashMap<>();
          provider.put("id", id);
          provider.put("displayName", rs.getString("display_name"));
          provider.put("source", "control");
          provider.put(
              "protocol",
              "openai_compat".equals(rs.getString("protocol")) ? "openai-compat" : "anthropic");
          provider.put("baseUrl", rs.getString("base_url"));
          provider.put("apiKeyEnv", env);
          provider.put("apiKeyRequired", config.path("apiKeyRequired").asBoolean(true));
          provider.put("defaultModel", defaultModel);
          provider.put("models", models);
          provider.put("modelPrefixes", List.of());
          provider.put("passthroughUnknownModels", false);
          provider.put(
              "maxTokensField",
              "openai_compat".equals(rs.getString("protocol"))
                  ? "max_completion_tokens"
                  : "max_tokens");
          provider.put("deduplicateStreamText", false);
          provider.put("bufferStreamText", false);
          provider.put("fidelityMode", "strict");
          provider.put("status", rs.getBoolean("enabled") ? "active" : "disabled");
          var pool = credentials.list(id);
          boolean pooled =
              config.path("credentialPoolConfigured").asBoolean(false) || !pool.isEmpty();
          provider.put(
              "hasApiKey",
              pooled
                  ? pool.stream()
                      .anyMatch(
                          credential ->
                              "active".equals(credential.get("status"))
                                  && Boolean.TRUE.equals(credential.get("hasApiKey")))
                  : configured);
          provider.put("credentials", pool);
          provider.put("health", providerStatus.health(id, null));
          provider.put("credentialPoolMode", config.path("credentialPoolMode").asText("failover"));
          provider.put(
              "activeCredentialId",
              pool.stream()
                  .filter(credential -> Boolean.TRUE.equals(credential.get("active")))
                  .map(credential -> credential.get("id"))
                  .findFirst()
                  .orElse(null));
          provider.put("modelInventory", inventory);
          provider.put(
              "toolUse",
              Map.of(
                  "supported",
                  true,
                  "toolChoice",
                  true,
                  "parallelToolCalls",
                  true,
                  "streamingArguments",
                  "native"));
          config
              .fields()
              .forEachRemaining(
                  entry -> {
                    if (PROVIDER_FIELDS.contains(entry.getKey())
                        && !Set.of(
                                "id",
                                "baseUrl",
                                "apiKeyEnv",
                                "models",
                                "protocol",
                                "displayName",
                                "disabled")
                            .contains(entry.getKey()))
                      provider.put(
                          entry.getKey(), json.convertValue(entry.getValue(), Object.class));
                  });
          provider.put("lastTest", config.has("lastTest") ? config.path("lastTest") : null);
          return provider;
        });
  }

  @PostMapping("/admin/providers")
  @Transactional
  Map<String, Object> createProvider(
      @RequestHeader HttpHeaders headers, @RequestBody JsonNode input) {
    auth.requireWrite(headers);
    auth.requireAdmin(headers);
    String id = input.path("id").asText("").trim();
    if (!id.matches("[a-z][a-z0-9_-]{0,62}")) throw bad("Invalid provider id");
    if (database.queryForObject(
            "SELECT count(*) FROM gateway_provider WHERE id=?", Integer.class, id)
        > 0) {
      throw new GatewayException(
          HttpStatus.CONFLICT,
          "invalid_request_error",
          "provider_exists",
          "Provider already exists");
    }
    ProviderInput body = providerInput(input);
    database.update(
        "INSERT INTO gateway_provider(id, display_name, protocol, base_url, credential_env,"
            + " enabled,config) VALUES (?, ?, ?, ?, ?, ?,?::jsonb)",
        id,
        body.displayName(),
        body.protocol(),
        body.baseUrl(),
        body.credentialEnv(),
        !body.disabled(),
        body.config().toString());
    writeModels(id, body.models());
    return provider(id);
  }

  @PutMapping("/admin/providers/{id}")
  @Transactional
  Map<String, Object> updateProvider(
      @RequestHeader HttpHeaders headers, @PathVariable String id, @RequestBody JsonNode input) {
    auth.requireWrite(headers);
    auth.requireAdmin(headers);
    requireProvider(id);
    if (!input.isObject()) throw bad("Provider request must be an object");
    GenerationPolicy.validate(input);
    Map<String, Object> old = provider(id);
    ObjectNode merged = json.createObjectNode();
    for (String field : PROVIDER_FIELDS)
      if (old.containsKey(field)) merged.set(field, json.valueToTree(old.get(field)));
    merged.put("disabled", "disabled".equals(old.get("status")));
    input.fields().forEachRemaining(entry -> merged.set(entry.getKey(), entry.getValue()));
    ProviderInput body = providerInput(merged);
    var previous =
        database.queryForObject(
            "SELECT config::text FROM gateway_provider WHERE id=?", String.class, id);
    try {
      JsonNode privateConfig = json.readTree(previous);
      for (String field : List.of("credentialPoolConfigured", "credentialPoolMode"))
        if (privateConfig.has(field))
          ((ObjectNode) body.config()).set(field, privateConfig.path(field));
    } catch (Exception e) {
      throw new IllegalStateException("Invalid provider pool configuration", e);
    }
    database.update(
        "UPDATE gateway_provider SET display_name=?, protocol=?,base_url=?, credential_env=?,"
            + " enabled=?, config=?::jsonb,updated_at=now() WHERE id=?",
        body.displayName(),
        body.protocol(),
        body.baseUrl(),
        body.credentialEnv(),
        !body.disabled(),
        body.config().toString(),
        id);
    writeModels(id, body.models());
    return provider(id);
  }

  @PostMapping("/admin/providers/{id}/disable")
  @Transactional
  Map<String, Object> disableProvider(
      @RequestHeader HttpHeaders headers, @PathVariable String id, @RequestBody JsonNode input) {
    auth.requireWrite(headers);
    auth.requireAdmin(headers);
    if (!input.path("disabled").isBoolean()) throw bad("disabled must be a boolean");
    if (database.update(
            "UPDATE gateway_provider SET enabled=?, updated_at=now() WHERE id=?",
            !input.path("disabled").asBoolean(),
            id)
        == 0) throw missingProvider();
    return provider(id);
  }

  @DeleteMapping("/admin/providers/{id}")
  @Transactional
  ResponseEntity<Void> deleteProvider(
      @RequestHeader HttpHeaders headers,
      @PathVariable String id,
      @RequestParam(defaultValue = "false") boolean force) {
    auth.requireWrite(headers);
    auth.requireAdmin(headers);
    requireProvider(id);
    Integer models =
        database.queryForObject(
            "SELECT count(*) FROM gateway_model WHERE provider_id=?", Integer.class, id);
    if (models != null && models > 0 && !force)
      throw new GatewayException(
          HttpStatus.CONFLICT,
          "invalid_request_error",
          "provider_has_models",
          "Use force=true to remove a provider with models");
    if (force) {
      database.update(
          "DELETE FROM model_alias WHERE target_model IN (SELECT public_id FROM gateway_model WHERE"
              + " provider_id=?)",
          id);
      database.update("DELETE FROM gateway_model WHERE provider_id=?", id);
    }
    database.update("DELETE FROM gateway_provider WHERE id=?", id);
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/admin/providers/{id}/models")
  @Transactional
  Map<String, Object> updateModel(
      @RequestHeader HttpHeaders headers, @PathVariable String id, @RequestBody JsonNode input) {
    auth.requireWrite(headers);
    auth.requireAdmin(headers);
    requireProvider(id);
    if (!input.isObject()) throw bad("Model request must be an object");
    input
        .fieldNames()
        .forEachRemaining(
            field -> {
              if (!Set.of("model", "status", "displayName", "family", "contextWindow", "profile")
                  .contains(field)) throw bad("Unsupported model field: " + field);
            });
    String model = input.path("model").asText("").trim();
    if (!model.matches("[A-Za-z0-9._:/-]{1,240}")) throw bad("Invalid model name");
    String status = input.path("status").asText("active");
    if (!Set.of("active", "disabled").contains(status)) throw bad("Invalid model status");
    String publicId = id + ":" + model;
    requireModelNameAvailable(publicId);
    ObjectNode profile =
        input.path("profile").isObject()
            ? ((ObjectNode) input.path("profile")).deepCopy()
            : json.createObjectNode();
    for (String field : List.of("displayName", "family", "contextWindow"))
      if (input.has(field)) profile.set(field, input.path(field));
    database.update(
        "INSERT INTO gateway_model(public_id, provider_id, upstream_id, enabled,profile) VALUES (?,"
            + " ?, ?, ?,?::jsonb) ON CONFLICT (public_id) DO UPDATE SET"
            + " enabled=excluded.enabled,profile=excluded.profile",
        publicId,
        id,
        model,
        "active".equals(status),
        profile.toString());
    return Map.of("provider", provider(id));
  }

  Map<String, Object> provider(String id) {
    return providersWithoutAuth().stream()
        .filter(value -> id.equals(value.get("id")))
        .findFirst()
        .orElseThrow(AdminModelsController::missingProvider);
  }

  private void requireProvider(String id) {
    if (database.queryForObject(
            "SELECT count(*) FROM gateway_provider WHERE id=?", Integer.class, id)
        == 0) throw missingProvider();
  }

  private void writeModels(String id, List<String> models) {
    database.update("UPDATE gateway_model SET enabled=false WHERE provider_id=?", id);
    for (String model : models) {
      String publicId = id + ":" + model;
      requireModelNameAvailable(publicId);
      database.update(
          "INSERT INTO gateway_model(public_id, provider_id, upstream_id, enabled) VALUES (?, ?, ?,"
              + " true) ON CONFLICT (public_id) DO UPDATE SET enabled=true",
          publicId,
          id,
          model);
    }
  }

  private void requireModelNameAvailable(String name) {
    database.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", Object.class, name);
    if (database.queryForObject(
            "SELECT count(*) FROM model_alias WHERE alias=?", Integer.class, name)
        > 0) throw bad("Model name conflicts with an alias");
  }

  private ProviderInput providerInput(JsonNode input) {
    if (!input.isObject()) throw bad("Provider request must be an object");
    GenerationPolicy.validate(input);
    input
        .fieldNames()
        .forEachRemaining(
            field -> {
              if (!PROVIDER_FIELDS.contains(field))
                throw bad("Unsupported provider field: " + field);
            });
    String protocol = input.path("protocol").asText("anthropic");
    if (!Set.of("anthropic", "openai-compat").contains(protocol))
      throw bad("Invalid provider protocol");
    String name = input.path("displayName").asText("").trim();
    if (name.isBlank() || name.length() > 120) throw bad("Invalid displayName");
    String rawUrl = input.path("baseUrl").asText("");
    URI url;
    try {
      url = URI.create(rawUrl);
      GatewaySettings.validateUpstream(url, settings.allowHttpMock);
    } catch (GatewayException e) {
      throw e;
    } catch (Exception e) {
      throw bad("Invalid provider baseUrl");
    }
    String env = input.path("apiKeyEnv").asText("").trim();
    if (input.path("clearApiKeyEnv").asBoolean(false)) env = "";
    if (!env.isBlank() && !env.matches("[A-Z][A-Z0-9_]{0,127}"))
      throw bad("apiKeyEnv must name an environment variable");
    if (env.isBlank() && input.path("apiKeyRequired").asBoolean(true))
      throw bad("apiKeyEnv is required when apiKeyRequired is true");
    JsonNode values = input.path("models");
    if (!values.isArray() || values.isEmpty() || values.size() > 256)
      throw bad("models must be a nonempty array");
    List<String> models = new ArrayList<>();
    for (JsonNode value : values) {
      String model = value.asText("").trim();
      if (!value.isTextual() || !model.matches("[A-Za-z0-9._:/-]{1,240}") || models.contains(model))
        throw bad("Invalid or duplicate model");
      models.add(model);
    }
    String defaultModel = input.path("defaultModel").asText(models.getFirst());
    if (!models.contains(defaultModel)) throw bad("defaultModel must be in models");
    if (input.has("disabled") && !input.path("disabled").isBoolean())
      throw bad("disabled must be a boolean");
    ObjectNode config = ((ObjectNode) input).deepCopy();
    for (String field :
        List.of(
            "id",
            "displayName",
            "protocol",
            "baseUrl",
            "apiKeyEnv",
            "models",
            "disabled",
            "clearApiKeyEnv")) config.remove(field);
    config.put("defaultModel", defaultModel);
    if (!config.has("fidelityMode"))
      config.put(
          "fidelityMode",
          config.path("deduplicateStreamText").asBoolean()
                  || config.path("bufferStreamText").asBoolean()
              ? "best_effort"
              : "strict");
    if (!Set.of("strict", "best_effort", "stability")
        .contains(config.path("fidelityMode").asText())) throw bad("Invalid fidelity mode");
    JsonNode tools = config.path("toolUse");
    if (!tools.isMissingNode()) {
      if (!tools.isObject()) throw bad("toolUse must be an object");
      for (String field :
          List.of("supported", "toolChoice", "parallelToolCalls", "repairInvalidArguments"))
        if (tools.has(field) && !tools.path(field).isBoolean())
          throw bad("Invalid toolUse." + field);
      if (!Set.of("native", "delta", "cumulative", "best_effort")
          .contains(tools.path("streamingArguments").asText("native")))
        throw bad("Invalid tool argument mode");
      if (!Set.of("best_effort", "strict")
          .contains(tools.path("responseValidation").asText("best_effort")))
        throw bad("Invalid tool response validation mode");
      if (tools.path("repairInvalidArguments").asBoolean()
          && (!"openai-compat".equals(protocol)
              || !"strict".equals(tools.path("responseValidation").asText())))
        throw bad(
            "Tool argument repair requires an OpenAI-compatible provider and strict response"
                + " validation");
    }
    for (String field : List.of("requestTimeoutMs", "streamIdleTimeoutMs"))
      if (config.hasNonNull(field)
          && (!config.path(field).isIntegralNumber()
              || config.path(field).asLong() < 1000
              || config.path(field).asLong() > 3600000)) throw bad("Invalid " + field);
    if ((config.path("bufferStreamText").asBoolean(false)
            || config.path("deduplicateStreamText").asBoolean(false))
        && "strict".equals(config.path("fidelityMode").asText("compatible")))
      throw bad("Strict fidelity mode cannot rewrite or buffer stream text");
    if (config.path("trustUpstreamCost").asBoolean(false))
      throw bad("Provider-reported billing evidence is required before trusting upstream cost");
    if (config.hasNonNull("maxTokensField")
        && !Set.of("max_tokens", "max_completion_tokens", "both")
            .contains(config.path("maxTokensField").asText())) throw bad("Invalid maxTokensField");
    JsonNode headers = config.path("staticHeaders");
    if (!headers.isMissingNode() && !headers.isObject())
      throw bad("staticHeaders must be an object");
    headers
        .fields()
        .forEachRemaining(
            entry -> {
              String key = entry.getKey().toLowerCase(java.util.Locale.ROOT);
              if (!key.matches("[a-z0-9-]{1,128}")
                  || Set.of("authorization", "x-api-key", "cookie", "host", "content-length")
                      .contains(key)
                  || !entry.getValue().isTextual()
                  || entry.getValue().asText().contains("\r")
                  || entry.getValue().asText().contains("\n")) throw bad("Invalid static header");
            });
    validatePrice(config.path("pricing"));
    config.path("modelPricing").fields().forEachRemaining(entry -> validatePrice(entry.getValue()));
    if (config.has("governance")) {
      JsonNode governance = config.path("governance");
      if (!governance.isObject()
          || !Set.of("local", "cloud").contains(governance.path("boundary").asText())
          || governance.path("region").asText().isBlank()
          || governance.path("apiVersion").asText().isBlank())
        throw bad("Invalid provider governance metadata");
    }
    if (config.has("tokenCounting")
        && !Set.of("disabled", "anthropic")
            .contains(config.path("tokenCounting").path("mode").asText()))
      throw bad("Invalid token counting mode");
    if (config.has("retry")) {
      JsonNode retry = config.path("retry");
      int attempts = retry.path("max_attempts").asInt(1);
      long initial = retry.path("initial_delay_ms").asLong(250),
          maximum = retry.path("max_delay_ms").asLong(5000);
      double jitter = retry.path("jitter_ratio").asDouble(.1);
      if (!retry.isObject()
          || attempts < 1
          || attempts > 5
          || initial < 0
          || maximum < initial
          || maximum > 60000
          || !Double.isFinite(jitter)
          || jitter < 0
          || jitter > 1) throw bad("Invalid provider retry policy");
    }
    return new ProviderInput(
        name,
        url.toString(),
        env,
        models,
        input.path("disabled").asBoolean(false),
        "openai-compat".equals(protocol) ? "openai_compat" : "anthropic",
        config);
  }

  private static void validatePrice(JsonNode card) {
    if (card.isMissingNode() || card.isNull()) return;
    if (!card.isObject()) throw bad("Price card must be an object");
    for (String field :
        List.of(
            "inputPerMillion", "outputPerMillion", "cacheReadPerMillion", "cacheWritePerMillion")) {
      JsonNode rate = card.path(field);
      if (!rate.isNumber()
          || rate.decimalValue().signum() < 0
          || rate.decimalValue().compareTo(java.math.BigDecimal.valueOf(1000000)) > 0)
        throw bad("Invalid pricing rate " + field);
    }
  }

  private record ProviderInput(
      String displayName,
      String baseUrl,
      String credentialEnv,
      List<String> models,
      boolean disabled,
      String protocol,
      JsonNode config) {}

  private static GatewayException missingProvider() {
    return new GatewayException(
        HttpStatus.NOT_FOUND, "not_found_error", "provider_not_found", "Provider not found");
  }

  @GetMapping("/admin/aliases")
  List<Map<String, String>> aliases(
      @RequestHeader HttpHeaders headers, @RequestParam(required = false) java.util.UUID apiKeyId) {
    var actor = auth.requireSession(headers);
    var all =
        database.query(
            """
            SELECT a.alias, m.public_id, m.upstream_id, m.provider_id FROM model_alias a
            JOIN gateway_model m ON m.public_id=a.target_model ORDER BY a.alias
            """,
            (rs, index) ->
                Map.of(
                    "alias",
                    rs.getString("alias"),
                    "target",
                    rs.getString("provider_id") + ":" + rs.getString("upstream_id"),
                    "resolvedProvider",
                    rs.getString("provider_id"),
                    "resolvedModel",
                    rs.getString("upstream_id")));
    if ("admin".equals(actor.get("role")) && apiKeyId == null) return all;
    var allowed = catalog(headers, apiKeyId);
    return all.stream()
        .filter(
            alias ->
                allowed.stream()
                    .anyMatch(route -> route.requestedModel().equals(alias.get("alias"))))
        .toList();
  }

  @Transactional(readOnly = true)
  List<Map<String, String>> aliases(HttpHeaders headers) {
    return aliases(headers, null);
  }

  @PostMapping("/admin/aliases")
  @Transactional
  Map<String, String> createAlias(@RequestHeader HttpHeaders headers, @RequestBody JsonNode input) {
    auth.requireWrite(headers);
    auth.requireAdmin(headers);
    String alias = input.path("alias").asText("").trim();
    String target = input.path("target").asText("").trim();
    if (!alias.matches("[A-Za-z0-9._:/-]{1,240}") || target.isBlank())
      throw bad("Invalid alias or target");
    database.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", Object.class, alias);
    if (!database
        .query(
            "SELECT public_id FROM gateway_model WHERE public_id=?",
            (rs, i) -> rs.getString(1),
            alias)
        .isEmpty()) throw bad("Alias conflicts with a model");
    List<String> matches =
        database.query(
            "SELECT public_id FROM gateway_model WHERE public_id=? OR provider_id || ':' ||"
                + " upstream_id=?",
            (rs, i) -> rs.getString(1),
            target,
            target);
    if (matches.size() != 1) throw bad("Alias target must resolve to exactly one configured model");
    try {
      database.update(
          "INSERT INTO model_alias(alias, target_model) VALUES (?, ?) ON CONFLICT(alias) DO UPDATE"
              + " SET target_model=excluded.target_model",
          alias,
          matches.getFirst());
    } catch (DuplicateKeyException e) {
      throw new GatewayException(
          HttpStatus.CONFLICT, "invalid_request_error", "alias_exists", "Alias already exists");
    }
    return database.queryForObject(
        """
        SELECT a.alias, m.upstream_id, m.provider_id FROM model_alias a
        JOIN gateway_model m ON m.public_id=a.target_model WHERE a.alias=?
        """,
        (rs, i) ->
            Map.of(
                "alias",
                rs.getString("alias"),
                "target",
                rs.getString("provider_id") + ":" + rs.getString("upstream_id"),
                "resolvedProvider",
                rs.getString("provider_id"),
                "resolvedModel",
                rs.getString("upstream_id")),
        alias);
  }

  @DeleteMapping("/admin/aliases/{alias}")
  ResponseEntity<Void> deleteAlias(@RequestHeader HttpHeaders headers, @PathVariable String alias) {
    auth.requireWrite(headers);
    auth.requireAdmin(headers);
    if (database.update("DELETE FROM model_alias WHERE alias=?", alias) == 0) {
      throw new GatewayException(
          HttpStatus.NOT_FOUND, "not_found_error", "alias_not_found", "Alias not found");
    }
    return ResponseEntity.noContent().build();
  }

  private static GatewayException bad(String message) {
    return new GatewayException(
        HttpStatus.BAD_REQUEST, "invalid_request_error", "invalid_request", message);
  }
}
