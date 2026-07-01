package dev.polygate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
class RoutingService {
  private final GatewaySettings settings;
  private final JdbcTemplate database;
  private final ObjectMapper json;
  private final ConfigurationService configuration;
  private final ProviderCredentialService credentials;
  private final SmartRoutingService smart;

  RoutingService(
      GatewaySettings settings,
      ObjectProvider<JdbcTemplate> database,
      ObjectMapper json,
      ConfigurationService configuration,
      ObjectProvider<ProviderCredentialService> credentials,
      ObjectProvider<SmartRoutingService> smart) {
    this.settings = settings;
    this.database = database.getIfAvailable();
    this.json = json;
    this.configuration = configuration;
    this.credentials = credentials.getIfAvailable();
    this.smart = smart.getIfAvailable();
  }

  @PostConstruct
  void seedConfiguredDefault() {
    if (database == null) return;
    if (database.queryForObject("SELECT count(*) FROM gateway_provider", Integer.class) > 0) return;
    database.update(
        "INSERT INTO gateway_provider(id, display_name, protocol, base_url, credential_env) VALUES"
            + " ('deepseek', 'Default Provider', ?, ?, 'POLYGATE_UPSTREAM_KEY') ON CONFLICT"
            + " (id) DO NOTHING",
        settings.upstreamProtocol,
        settings.upstreamUrl.toString());
    database.update(
        "INSERT INTO gateway_model(public_id, provider_id, upstream_id) VALUES (?, 'deepseek', ?)"
            + " ON CONFLICT (public_id) DO NOTHING",
        settings.publicModel,
        settings.upstreamModel);
  }

  Route resolve(String requestedModel) {
    if (requestedModel == null || requestedModel.isBlank()) throw bad("model is required");
    var selection = smart == null ? null : smart.select(requestedModel);
    return resolveStatic(
        requestedModel,
        selection == null ? requestedModel : selection.model(),
        selection == null ? null : selection.decision());
  }

  private Route resolveStatic(String requestedModel, String lookupModel, JsonNode decision) {
    if (database == null) {
      if (!settings.publicModel.equals(requestedModel)) throw bad("Unknown model");
      return new Route(
          requestedModel,
          "deepseek",
          settings.upstreamModel,
          settings.upstreamUrl,
          settings.upstreamKey,
          settings.upstreamProtocol,
          effective(JsonNodeFactory.instance.objectNode()));
    }
    List<Route> routes =
        database.query(
            """
SELECT m.public_id, m.upstream_id,m.profile::text AS model_profile, p.id AS provider_id, p.base_url, p.credential_env,p.protocol,p.config::text AS config
FROM gateway_model m JOIN gateway_provider p ON p.id=m.provider_id
WHERE m.enabled=true AND p.enabled=true AND (m.public_id=? OR m.public_id=(SELECT a.target_model FROM model_alias a WHERE a.alias=?))
LIMIT 1
""",
            (rs, index) -> {
              URI url = URI.create(rs.getString("base_url"));
              GatewaySettings.validateUpstream(url, settings.allowHttpMock);
              String credentialEnv = rs.getString("credential_env");
              String key =
                  "POLYGATE_UPSTREAM_KEY".equals(credentialEnv)
                      ? settings.upstreamKey
                      : System.getenv(credentialEnv);
              JsonNode config;
              try {
                config = json.readTree(rs.getString("config"));
                ((com.fasterxml.jackson.databind.node.ObjectNode) config)
                    .put("_providerBaseUrl", rs.getString("base_url"));
                ((com.fasterxml.jackson.databind.node.ObjectNode) config)
                    .set("_modelOverride", json.readTree(rs.getString("model_profile")));
              } catch (Exception e) {
                throw new IllegalStateException("Invalid provider configuration", e);
              }
              return new Route(
                  requestedModel,
                  rs.getString("provider_id"),
                  rs.getString("upstream_id"),
                  url,
                  key == null ? "" : key,
                  rs.getString("protocol"),
                  effective(config));
            },
            lookupModel,
            lookupModel);
    if (routes.isEmpty()) return dynamicRoute(requestedModel, lookupModel);
    Route route = routes.getFirst();
    if (decision != null)
      ((com.fasterxml.jackson.databind.node.ObjectNode) route.config())
          .set("_routingDecision", decision);
    return credentials == null ? route : credentials.decorate(route, false);
  }

  Route forRequest(Route route) {
    if (smart != null)
      smart.record(route.config().path("_routingDecision"), route.requestedModel());
    return credentials == null ? route : credentials.decorate(route, true);
  }

  Route nextCredential(Route route, java.util.Set<String> rejected) {
    return credentials == null ? null : credentials.alternative(route, rejected);
  }

  List<Route> fallbacks(Route first) {
    if (database == null || first.requestedModel().startsWith(first.providerId() + ":"))
      return List.of();
    java.util.LinkedHashSet<String> models = new java.util.LinkedHashSet<>();
    if (smart != null)
      for (JsonNode group : smart.configuration().path("groups")) {
        boolean matches = false;
        for (JsonNode alias : group.path("aliases"))
          if (first.requestedModel().equals(alias.asText())) matches = true;
        if (matches)
          for (JsonNode c : group.path("candidates")) models.add(c.path("model").asText());
      }
    var order = configuration.system().path("gateway").path("providerOrder");
    for (JsonNode provider : order)
      database.query(
          "SELECT public_id FROM gateway_model m JOIN gateway_provider p ON p.id=m.provider_id"
              + " WHERE m.provider_id=? AND m.upstream_id=? AND m.enabled AND p.enabled ORDER BY"
              + " m.public_id",
          (org.springframework.jdbc.core.RowCallbackHandler) rs -> models.add(rs.getString(1)),
          provider.asText(),
          first.upstreamModel());
    List<Route> result = new java.util.ArrayList<>();
    for (String model : models)
      try {
        Route r = resolveStatic(first.requestedModel(), model, null);
        if (r.protocol().equals(first.protocol())
            && (!r.providerId().equals(first.providerId())
                || !r.upstreamModel().equals(first.upstreamModel()))) result.add(r);
      } catch (GatewayException ignored) {
      }
    return result;
  }

  private Route dynamicRoute(String requested, String lookup) {
    if (!lookup.matches("[A-Za-z0-9._:/-]{1,240}")) throw bad("Invalid model name");
    JsonNode gateway = configuration.system().path("gateway");
    java.util.ArrayList<String> order = new java.util.ArrayList<>();
    String defaultProvider = gateway.path("defaultProvider").asText();
    for (JsonNode id : gateway.path("providerOrder")) order.add(id.asText());
    var rows =
        database.queryForList(
            "SELECT m.public_id,m.upstream_id,m.enabled,p.id,p.config::text config FROM"
                + " gateway_model m JOIN gateway_provider p ON p.id=m.provider_id WHERE p.enabled"
                + " ORDER BY p.id,m.public_id");
    rows.sort(
        java.util.Comparator.comparingInt(
            r -> {
              int rank = order.indexOf((String) r.get("id"));
              return rank >= 0
                  ? rank
                  : ((String) r.get("id")).equals(defaultProvider) ? -1 : Integer.MAX_VALUE;
            }));
    for (var row : rows)
      if (lookup.equals(row.get("upstream_id")) && Boolean.TRUE.equals(row.get("enabled"))) {
        Route r = resolveStatic(requested, (String) row.get("public_id"), null);
        return r;
      }
    if (database.queryForObject(
            "SELECT count(*) FROM gateway_model WHERE public_id=? OR upstream_id=?",
            Integer.class,
            lookup,
            lookup)
        > 0) throw bad("Model is disabled or unavailable");
    for (var row : rows) {
      if (!Boolean.TRUE.equals(row.get("enabled"))) continue;
      JsonNode config;
      try {
        config = json.readTree((String) row.get("config"));
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
      boolean matches = false;
      String upstream = lookup;
      if (lookup.startsWith(row.get("id") + ":")) {
        matches = config.path("passthroughUnknownModels").asBoolean();
        upstream = lookup.substring(row.get("id").toString().length() + 1);
      } else {
        for (JsonNode prefix : config.path("modelPrefixes"))
          if (!prefix.asText().isBlank() && lookup.startsWith(prefix.asText())) matches = true;
        if (row.get("id").equals(defaultProvider)
            && config.path("passthroughUnknownModels").asBoolean()) matches = true;
      }
      if (matches) {
        Route r = resolveStatic(requested, (String) row.get("public_id"), null);
        ((com.fasterxml.jackson.databind.node.ObjectNode) r.config()).remove("_modelOverride");
        return new Route(
            requested,
            r.providerId(),
            upstream,
            r.upstreamUrl(),
            r.upstreamKey(),
            r.protocol(),
            r.config(),
            r.credentialId());
      }
    }
    throw bad("Unknown model");
  }

  List<Map<String, String>> models() {
    if (database == null)
      return List.of(Map.of("id", settings.publicModel, "type", "model", "owned_by", "deepseek"));
    var catalog =
        database.query(
            """
SELECT public_id AS id, provider_id AS owner FROM gateway_model m
WHERE enabled=true AND EXISTS (SELECT 1 FROM gateway_provider p WHERE p.id=m.provider_id AND p.enabled=true)
UNION ALL
SELECT a.alias AS id, m.provider_id AS owner FROM model_alias a JOIN gateway_model m ON m.public_id=a.target_model
WHERE m.enabled=true AND EXISTS (SELECT 1 FROM gateway_provider p WHERE p.id=m.provider_id AND p.enabled=true)
ORDER BY id
""",
            (rs, index) ->
                Map.of(
                    "id",
                    rs.getString("id"),
                    "display_name",
                    rs.getString("id"),
                    "type",
                    "model",
                    "owned_by",
                    rs.getString("owner")));
    java.util.ArrayList<Map<String, String>> result = new java.util.ArrayList<>(catalog);
    if (smart != null)
      for (JsonNode group : smart.configuration().path("groups"))
        for (JsonNode alias : group.path("aliases")) {
          if (result.stream().anyMatch(m -> m.get("id").equals(alias.asText()))) continue;
          try {
            Route route = resolve(alias.asText());
            result.add(
                Map.of(
                    "id",
                    alias.asText(),
                    "display_name",
                    alias.asText(),
                    "type",
                    "model",
                    "owned_by",
                    route.providerId()));
          } catch (GatewayException ignored) {
          }
        }
    return result;
  }

  private static GatewayException bad(String detail) {
    return new GatewayException(
        HttpStatus.BAD_REQUEST, "invalid_request_error", "invalid_request", detail);
  }

  private JsonNode effective(JsonNode value) {
    var config = (com.fasterxml.jackson.databind.node.ObjectNode) value.deepCopy();
    var limits = configuration.limits();
    if (!config.hasNonNull("requestTimeoutMs"))
      config.put("requestTimeoutMs", limits.timeoutSeconds() * 1000L);
    if (!config.hasNonNull("streamIdleTimeoutMs"))
      config.put("streamIdleTimeoutMs", limits.idleSeconds() * 1000L);
    return config;
  }

  record Route(
      String requestedModel,
      String providerId,
      String upstreamModel,
      URI upstreamUrl,
      String upstreamKey,
      String protocol,
      JsonNode config,
      String credentialId) {
    Route(
        String requestedModel,
        String providerId,
        String upstreamModel,
        URI upstreamUrl,
        String upstreamKey,
        String protocol,
        JsonNode config) {
      this(
          requestedModel,
          providerId,
          upstreamModel,
          upstreamUrl,
          upstreamKey,
          protocol,
          config,
          null);
    }

    Route(
        String requestedModel,
        String providerId,
        String upstreamModel,
        URI upstreamUrl,
        String upstreamKey) {
      this(
          requestedModel,
          providerId,
          upstreamModel,
          upstreamUrl,
          upstreamKey,
          "anthropic",
          JsonNodeFactory.instance.objectNode());
    }

    String endpoint(String path) {
      String base = upstreamUrl.toString().replaceAll("/+$", "");
      return base.endsWith("/v1") && path.startsWith("/v1/")
          ? base + path.substring(3)
          : base + path;
    }

    boolean keyRequired() {
      return config.path("apiKeyRequired").asBoolean(true);
    }

    long requestTimeoutMs() {
      return config.path("requestTimeoutMs").asLong(60000);
    }

    long streamIdleTimeoutMs() {
      return config.path("streamIdleTimeoutMs").asLong(60000);
    }
  }
}
