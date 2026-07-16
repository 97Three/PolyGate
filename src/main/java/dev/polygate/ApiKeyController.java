package dev.polygate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "polygate.database.enabled", havingValue = "true")
class ApiKeyController {
  private static final SecureRandom RANDOM = new SecureRandom();
  private final AdminAuthService auth;
  private final JdbcTemplate database;
  private final ObjectMapper json;
  private final GovernanceService governance;
  private final ClientAuthService clientAuth;
  private final RoutingService routes;

  ApiKeyController(
      AdminAuthService auth,
      JdbcTemplate database,
      ObjectMapper json,
      GovernanceService governance,
      ClientAuthService clientAuth,
      RoutingService routes) {
    this.auth = auth;
    this.database = database;
    this.json = json;
    this.governance = governance;
    this.clientAuth = clientAuth;
    this.routes = routes;
  }

  @GetMapping("/admin/api-keys")
  @Transactional(readOnly = true)
  List<Map<String, Object>> list(@RequestHeader HttpHeaders headers) {
    var actor = auth.requireSession(headers);
    if (!"admin".equals(actor.get("role")))
      return database.query(
          "SELECT k.*,u.username FROM gateway_api_key k LEFT JOIN gateway_user u ON u.id=k.user_id"
              + " WHERE k.user_id=? AND k.rotation_state<>'pending_rotation' ORDER BY k.created_at"
              + " DESC",
          this::publicKey,
          parseId(actor.get("id").toString()));
    return database.query(
        "SELECT k.*, u.username FROM gateway_api_key k LEFT JOIN gateway_user u ON u.id=k.user_id"
            + " WHERE k.rotation_state<>'pending_rotation' ORDER BY k.created_at DESC",
        this::publicKey);
  }

  @GetMapping("/admin/users/{userId}/api-keys")
  @Transactional(readOnly = true)
  List<Map<String, Object>> userKeys(
      @RequestHeader HttpHeaders headers, @PathVariable UUID userId) {
    var actor = auth.requireSession(headers);
    if (!"admin".equals(actor.get("role")) && !userId.toString().equals(actor.get("id")))
      throw forbidden();
    return database.query(
        "SELECT k.*, u.username FROM gateway_api_key k LEFT JOIN gateway_user u ON u.id=k.user_id"
            + " WHERE k.user_id=? AND k.rotation_state<>'pending_rotation' ORDER BY k.created_at"
            + " DESC",
        this::publicKey,
        userId);
  }

  @PostMapping({"/admin/api-keys", "/admin/users/{userId}/api-keys"})
  @Transactional
  Map<String, Object> create(
      @RequestHeader HttpHeaders headers,
      @RequestBody JsonNode input,
      @PathVariable(required = false) UUID userId) {
    auth.requireWrite(headers);
    var actor = writeActor(headers);
    String name = input.path("name").asText("").trim();
    if (name.isBlank() || name.length() > 120)
      throw bad("API key name is required and must be at most 120 characters");
    UUID owner =
        userId != null
            ? userId
            : input.path("userId").isTextual()
                ? parseId(input.path("userId").asText())
                : parseId((String) auth.requireSession(headers).get("id"));
    if (!"admin".equals(actor.get("role")) && !owner.toString().equals(actor.get("id")))
      throw forbidden();
    if (database.queryForObject(
            "SELECT count(*) FROM gateway_user WHERE id=? AND status='active'",
            Integer.class,
            owner)
        == 0) throw bad("User must exist and be active");
    String allowedModels = allowedModels(input.path("allowedModels"));
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    String secret = "sk-mpj-" + HexFormat.of().formatHex(bytes);
    UUID id = UUID.randomUUID();
    String prefix = secret.substring(0, 14) + "...";
    Instant expires = expiry(input.path("expiresAt"));
    if (!"admin".equals(actor.get("role"))) {
      if (input.hasNonNull("teamId") && !input.path("teamId").asText().isBlank()
          || !"user".equals(input.path("principalType").asText("user"))) throw forbidden();
      for (String field :
          List.of(
              "spendLimitUsd",
              "fiveHourLimitUsd",
              "dailyLimitUsd",
              "weeklyLimitUsd",
              "monthlyLimitUsd",
              "rateLimited",
              "ipRestricted",
              "allowedIps")) if (input.has(field)) throw forbidden();
      database.query(
          "SELECT id FROM gateway_user WHERE id=? FOR UPDATE", (rs, i) -> rs.getObject(1), owner);
      if (database.queryForObject(
              "SELECT count(*) FROM gateway_api_key WHERE user_id=? AND enabled=true AND"
                  + " rotation_state='active' AND (expires_at IS NULL OR expires_at>now())",
              Long.class,
              owner)
          >= 5)
        throw new GatewayException(
            HttpStatus.CONFLICT,
            "invalid_request_error",
            "active_key_limit",
            "Self-service permits at most five active keys");
      Instant maximum = Instant.now().plusSeconds(30L * 86400);
      if (expires == null) expires = maximum;
      if (expires.isAfter(maximum)) throw bad("Self-service key expiry cannot exceed 30 days");
    }
    if (expires != null && !expires.isAfter(Instant.now()))
      throw bad("Key expiry must be in the future");
    String group = nullableText(input.path("group"));
    String principal = input.path("principalType").asText("user");
    if (!java.util.Set.of("user", "service_account").contains(principal))
      throw bad("Invalid principalType");
    String purpose = nullableText(input.path("purpose"));
    if (purpose != null && purpose.length() > 500) throw bad("purpose is too long");
    if ("service_account".equals(principal)
        && (purpose == null
            || expires == null
            || expires.isAfter(Instant.now().plusSeconds(90L * 86400))))
      throw bad("Service accounts require a purpose and an expiry within 90 days");
    String providers = allowedModels(input.path("allowedProviders"));
    UUID team = teamId(input.path("teamId"));
    JsonNode policy = policy(input, json.createObjectNode());
    if ("service_account".equals(principal)
        && !"polygate_ops_agent".equals(purpose)
        && (purpose.length() < 8 || "[]".equals(allowedModels) || "[]".equals(providers)))
      throw bad(
          "Inference service accounts require a meaningful purpose and explicit model/provider"
              + " allowlists");
    database.update(
        "INSERT INTO gateway_api_key(id, user_id, name, secret_hash, key_prefix, allowed_models,"
            + " expires_at, key_group) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)",
        id,
        owner,
        name,
        ClientAuthService.digest(secret),
        prefix,
        allowedModels,
        expires == null ? null : Timestamp.from(expires),
        group);
    database.update(
        "UPDATE gateway_api_key SET"
            + " principal_type=?,purpose=?,allowed_providers=?::jsonb,team_id=?,policy=?::jsonb,quota_subject=id"
            + " WHERE id=?",
        principal,
        purpose,
        providers,
        team,
        policy.toString(),
        id);
    Map<String, Object> row =
        database.queryForObject(
            "SELECT k.*, u.username FROM gateway_api_key k LEFT JOIN gateway_user u ON"
                + " u.id=k.user_id WHERE k.id=?",
            this::publicKey,
            id);
    row.put("key", secret);
    row.put("keyPreview", prefix + secret.substring(secret.length() - 4));
    return row;
  }

  @PostMapping("/admin/api-keys/{id}/disable")
  Map<String, Object> disable(@RequestHeader HttpHeaders headers, @PathVariable UUID id) {
    auth.requireWrite(headers);
    requireKeyWrite(headers, id);
    if (database.update("UPDATE gateway_api_key SET enabled=false WHERE id=?", id) == 0)
      throw notFound();
    return Map.of("ok", true);
  }

  @PutMapping("/admin/api-keys/{id}")
  @Transactional
  Map<String, Object> update(
      @RequestHeader HttpHeaders headers, @PathVariable UUID id, @RequestBody JsonNode input) {
    auth.requireWrite(headers);
    var actor = requireKeyWrite(headers, id);
    if (!"admin".equals(actor.get("role")))
      input
          .fieldNames()
          .forEachRemaining(
              field -> {
                if (!java.util.Set.of("name", "group").contains(field)) throw forbidden();
              });
    List<Map<String, Object>> existing =
        database.query(
            "SELECT k.*, u.username FROM gateway_api_key k LEFT JOIN gateway_user u ON"
                + " u.id=k.user_id WHERE k.id=?",
            this::publicKey,
            id);
    if (existing.isEmpty()) throw notFound();
    Map<String, Object> old = existing.getFirst();
    if (old.get("supersededByKeyId") != null || "pending_rotation".equals(old.get("status")))
      throw bad("Superseded or pending keys cannot be edited");
    String name =
        input.has("name") ? input.path("name").asText("").trim() : (String) old.get("name");
    if (name.isBlank() || name.length() > 120) throw bad("Invalid API key name");
    String group =
        input.has("group") ? nullableText(input.path("group")) : (String) old.get("group");
    String allowed =
        input.has("allowedModels")
            ? allowedModels(input.path("allowedModels"))
            : json.valueToTree(old.get("allowedModels")).toString();
    Instant expires =
        input.has("expiresAt")
            ? expiry(input.path("expiresAt"))
            : old.get("expiresAt") == null ? null : Instant.parse((String) old.get("expiresAt"));
    boolean enabled =
        input.has("status")
            ? "active".equals(input.path("status").asText())
            : "active".equals(old.get("status"));
    if (input.has("status")
        && !java.util.Set.of("active", "revoked").contains(input.path("status").asText()))
      throw bad("Invalid key status");
    String providers =
        input.has("allowedProviders")
            ? allowedModels(input.path("allowedProviders"))
            : json.valueToTree(old.get("allowedProviders")).toString();
    UUID team =
        input.has("teamId")
            ? teamId(input.path("teamId"))
            : old.get("teamId") == null ? null : parseId((String) old.get("teamId"));
    JsonNode previous =
        database.queryForObject(
            "SELECT policy::text FROM gateway_api_key WHERE id=?",
            (rs, i) -> {
              try {
                return json.readTree(rs.getString(1));
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            },
            id);
    JsonNode policy = policy(input, previous);
    database.update(
        "UPDATE gateway_api_key SET name=?, key_group=?, allowed_models=?::jsonb, expires_at=?,"
            + " enabled=?,allowed_providers=?::jsonb,team_id=?,policy=?::jsonb WHERE id=?",
        name,
        group,
        allowed,
        expires == null ? null : Timestamp.from(expires),
        enabled,
        providers,
        team,
        policy.toString(),
        id);
    return database.queryForObject(
        "SELECT k.*, u.username FROM gateway_api_key k LEFT JOIN gateway_user u ON u.id=k.user_id"
            + " WHERE k.id=?",
        this::publicKey,
        id);
  }

  @DeleteMapping("/admin/api-keys/{id}")
  ResponseEntity<Void> delete(@RequestHeader HttpHeaders headers, @PathVariable UUID id) {
    auth.requireWrite(headers);
    requireKeyWrite(headers, id);
    if (database.update("DELETE FROM gateway_api_key WHERE id=?", id) == 0) throw notFound();
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/admin/api-keys/{id}/rotate")
  @Transactional
  Map<String, Object> prepareRotation(@RequestHeader HttpHeaders headers, @PathVariable UUID id) {
    auth.requireWrite(headers);
    requireKeyWrite(headers, id);
    var source =
        database.queryForList(
            "SELECT enabled,rotation_state FROM gateway_api_key WHERE id=? FOR UPDATE", id);
    if (source.isEmpty()) throw notFound();
    if (!Boolean.TRUE.equals(source.getFirst().get("enabled"))
        || !"active".equals(source.getFirst().get("rotation_state")))
      throw bad("Only active keys can be rotated");
    if (database.queryForObject(
            "SELECT count(*) FROM gateway_api_key WHERE rotation_source=? AND"
                + " rotation_state='pending_rotation'",
            Integer.class,
            id)
        > 0)
      throw new GatewayException(
          HttpStatus.CONFLICT,
          "invalid_request_error",
          "rotation_pending",
          "Cancel or confirm the existing pending rotation first");
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    String secret = "sk-mpj-" + HexFormat.of().formatHex(bytes);
    UUID replacement = UUID.randomUUID();
    String prefix = secret.substring(0, 14) + "...";
    database.update(
        """
INSERT INTO gateway_api_key(id,user_id,name,secret_hash,key_prefix,allowed_models,expires_at,key_group,principal_type,purpose,team_id,allowed_providers,policy,rotation_source,rotation_state,enabled,quota_subject,organization_id,project_id,environment_id)
SELECT ?,user_id,name,?,?,allowed_models,expires_at,key_group,principal_type,purpose,team_id,allowed_providers,policy,id,'pending_rotation',false,COALESCE(quota_subject,id),organization_id,project_id,environment_id FROM gateway_api_key WHERE id=?
""",
        replacement,
        ClientAuthService.digest(secret),
        prefix,
        id);
    var row =
        database.queryForObject(
            "SELECT k.*,u.username FROM gateway_api_key k LEFT JOIN gateway_user u ON"
                + " u.id=k.user_id WHERE k.id=?",
            this::publicKey,
            replacement);
    row.put("key", secret);
    row.put("keyPreview", prefix + secret.substring(secret.length() - 4));
    return row;
  }

  @PostMapping("/admin/api-keys/{id}/rotate/{replacement}")
  @Transactional
  Map<String, Object> confirmRotation(
      @RequestHeader HttpHeaders headers, @PathVariable UUID id, @PathVariable UUID replacement) {
    auth.requireWrite(headers);
    requireKeyWrite(headers, id);
    var source =
        database.queryForList(
            "SELECT enabled,rotation_state FROM gateway_api_key WHERE id=? FOR UPDATE", id);
    if (source.isEmpty()) throw notFound();
    if (!Boolean.TRUE.equals(source.getFirst().get("enabled"))
        || !"active".equals(source.getFirst().get("rotation_state")))
      throw bad("Source key is no longer active");
    int count =
        database.update(
            "UPDATE gateway_api_key SET enabled=true,rotation_state='active' WHERE id=? AND"
                + " rotation_source=? AND rotation_state='pending_rotation'",
            replacement,
            id);
    if (count == 0) throw bad("Pending rotation not found");
    database.update(
        "UPDATE gateway_api_key SET enabled=false,rotation_state='superseded',superseded_by=? WHERE"
            + " id=?",
        replacement,
        id);
    return database.queryForObject(
        "SELECT k.*,u.username FROM gateway_api_key k LEFT JOIN gateway_user u ON u.id=k.user_id"
            + " WHERE k.id=?",
        this::publicKey,
        replacement);
  }

  @DeleteMapping("/admin/api-keys/{id}/rotate/{replacement}")
  ResponseEntity<Void> cancelRotation(
      @RequestHeader HttpHeaders headers, @PathVariable UUID id, @PathVariable UUID replacement) {
    auth.requireWrite(headers);
    requireKeyWrite(headers, id);
    if (database.update(
            "DELETE FROM gateway_api_key WHERE id=? AND rotation_source=? AND"
                + " rotation_state='pending_rotation'",
            replacement,
            id)
        == 0) throw bad("Pending rotation not found");
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/admin/api-keys/{id}/scope")
  @Transactional
  Map<String, Object> scope(
      @RequestHeader HttpHeaders headers, @PathVariable UUID id, @RequestBody JsonNode input) {
    auth.requireWrite(headers);
    auth.requireAdmin(headers);
    TenantScope scope = TenantScope.from(input);
    if (database.update(
            "UPDATE gateway_api_key SET organization_id=?,project_id=?,environment_id=? WHERE id=?"
                + " AND rotation_state='active'",
            scope.organizationId(),
            scope.projectId(),
            scope.environmentId(),
            id)
        == 0) throw notFound();
    return database.queryForObject(
        "SELECT k.*,u.username FROM gateway_api_key k LEFT JOIN gateway_user u ON u.id=k.user_id"
            + " WHERE k.id=?",
        this::publicKey,
        id);
  }

  @GetMapping("/admin/api-keys/{id}/setup")
  Map<String, Object> setup(
      @RequestHeader HttpHeaders headers,
      @PathVariable UUID id,
      @org.springframework.web.bind.annotation.RequestParam String model) {
    var actor = auth.requireSession(headers);
    var owners =
        database.queryForList("SELECT user_id,policy::text FROM gateway_api_key WHERE id=?", id);
    if (owners.isEmpty()) throw notFound();
    if (!"admin".equals(actor.get("role"))
        && !actor
            .get("id")
            .toString()
            .equals(Objects.toString(owners.getFirst().get("user_id"), ""))) throw forbidden();
    Map<String, Object> result = new HashMap<>();
    result.put("apiKeyId", id.toString());
    result.put("model", model);
    result.put("status", "blocked");
    result.put("code", "key_unavailable");
    result.put("checkedAtMs", System.currentTimeMillis());
    result.put("upstreamVerified", false);
    try {
      var principal = clientAuth.catalogPrincipal(id);
      var route = routes.resolve(model);
      result.put("project", principal.scope());
      var policy = governance.policy(principal.scope());
      result.put("defaultClassification", policy.path("defaultClassification"));
      result.put("effectiveMode", policy.path("maximumMode"));
      clientAuth.authorizeRoute(principal, route);
      governance.enforce(principal, route, new HttpHeaders());
      if (route.keyRequired() && route.upstreamKey().isBlank())
        result.put("code", "missing_credential");
      else {
        result.put("code", "ready");
        result.put("status", "ready");
      }
    } catch (GatewayException error) {
      result.put("code", error.code);
    }
    return result;
  }

  private Map<String, Object> publicKey(ResultSet rs, int index) throws SQLException {
    Map<String, Object> row = new HashMap<>();
    row.put("id", rs.getObject("id").toString());
    row.put("userId", rs.getObject("user_id") == null ? "" : rs.getObject("user_id").toString());
    row.put("username", rs.getString("username"));
    row.put("name", rs.getString("name"));
    row.put("organizationId", rs.getString("organization_id"));
    row.put("projectId", rs.getString("project_id"));
    row.put("environmentId", rs.getString("environment_id"));
    row.put("principalType", rs.getString("principal_type"));
    row.put("purpose", rs.getString("purpose"));
    row.put("keyPrefix", rs.getString("key_prefix"));
    row.put("group", rs.getString("key_group"));
    row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
    row.put(
        "lastUsedAt",
        rs.getTimestamp("last_used_at") == null
            ? null
            : rs.getTimestamp("last_used_at").toInstant().toString());
    row.put(
        "expiresAt",
        rs.getTimestamp("expires_at") == null
            ? null
            : rs.getTimestamp("expires_at").toInstant().toString());
    row.put(
        "status",
        "pending_rotation".equals(rs.getString("rotation_state"))
            ? "pending_rotation"
            : rs.getBoolean("enabled") ? "active" : "revoked");
    row.put(
        "supersededByKeyId",
        rs.getObject("superseded_by") == null ? null : rs.getObject("superseded_by").toString());
    UUID keyId = (UUID) rs.getObject("id");
    var counters =
        database.queryForMap(
            "SELECT count(*)"
                + " requests,COALESCE(sum(u.input_tokens+u.output_tokens+u.cache_read_tokens+u.cache_write_tokens),0)"
                + " tokens FROM gateway_request r LEFT JOIN gateway_usage u ON u.request_id=r.id"
                + " WHERE r.api_key_id=? AND r.started_at>=date_trunc('day',now())",
            keyId);
    row.put("requestsToday", counters.get("requests"));
    row.put("tokensToday", counters.get("tokens"));
    row.put("teamId", rs.getObject("team_id") == null ? null : rs.getObject("team_id").toString());
    row.put(
        "teamName",
        rs.getObject("team_id") == null
            ? null
            : database.queryForObject(
                "SELECT name FROM gateway_team WHERE id=?", String.class, rs.getObject("team_id")));
    try {
      JsonNode models = json.readTree(rs.getString("allowed_models"));
      List<String> values = new ArrayList<>();
      for (JsonNode value : models) values.add(value.asText());
      row.put("allowedModels", values);
      row.put("allowedProviders", json.readTree(rs.getString("allowed_providers")));
      json.readTree(rs.getString("policy"))
          .fields()
          .forEachRemaining(entry -> row.put(entry.getKey(), entry.getValue()));
    } catch (Exception e) {
      throw new SQLException("Invalid stored API key model policy", e);
    }
    return row;
  }

  private String allowedModels(JsonNode value) {
    if (value.isMissingNode() || value.isNull()) return "[]";
    if (!value.isArray()) throw bad("allowedModels must be an array");
    if (value.size() > 256) throw bad("Allowlist is too large");
    for (JsonNode model : value)
      if (!model.isTextual() || model.asText().isBlank() || model.asText().length() > 240)
        throw bad("Allowlist must contain valid names");
    return value.toString();
  }

  private UUID teamId(JsonNode value) {
    String text = nullableText(value);
    if (text == null || text.isBlank()) return null;
    UUID id = parseId(text);
    if (database.queryForObject("SELECT count(*) FROM gateway_team WHERE id=?", Integer.class, id)
        == 0) throw bad("Unknown teamId");
    return id;
  }

  private JsonNode policy(JsonNode input, JsonNode old) {
    var policy = (com.fasterxml.jackson.databind.node.ObjectNode) old.deepCopy();
    for (String field :
        List.of(
            "spendLimitUsd",
            "fiveHourLimitUsd",
            "dailyLimitUsd",
            "weeklyLimitUsd",
            "monthlyLimitUsd"))
      if (input.has(field)) {
        JsonNode value = input.path(field);
        if (!value.isNumber()
            || value.decimalValue().signum() < 0
            || value.decimalValue().compareTo(java.math.BigDecimal.valueOf(1_000_000_000)) > 0)
          throw bad("Invalid " + field);
        policy.set(field, value);
      }
    for (String field : List.of("rateLimited", "ipRestricted"))
      if (input.has(field)) {
        if (!input.path(field).isBoolean()) throw bad("Invalid " + field);
        policy.set(field, input.path(field));
      }
    if (input.has("allowedIps")) {
      JsonNode ips = input.path("allowedIps");
      if (!ips.isArray() || ips.size() > 256) throw bad("Invalid allowedIps");
      for (JsonNode ip : ips)
        if (!ip.isTextual() || !IpPolicy.valid(ip.asText()))
          throw bad("Invalid IP address or CIDR");
      policy.set("allowedIps", ips);
    }
    if (policy.path("ipRestricted").asBoolean(false) && policy.path("allowedIps").isEmpty())
      throw bad("IP restrictions require at least one allowed address");
    return policy;
  }

  private static Instant expiry(JsonNode value) {
    if (value.isMissingNode() || value.isNull() || value.asText().isBlank()) return null;
    try {
      return value.asText().matches("[0-9]+")
          ? Instant.ofEpochMilli(Long.parseLong(value.asText()))
          : Instant.parse(value.asText());
    } catch (Exception e) {
      throw bad("expiresAt must be an ISO timestamp");
    }
  }

  private Map<String, Object> writeActor(HttpHeaders headers) {
    var actor = auth.requireSession(headers);
    if (!java.util.Set.of("admin", "user").contains(actor.get("role"))) throw forbidden();
    return actor;
  }

  private Map<String, Object> requireKeyWrite(HttpHeaders headers, UUID id) {
    var actor = writeActor(headers);
    var keys =
        database.queryForList("SELECT user_id,principal_type FROM gateway_api_key WHERE id=?", id);
    if (keys.isEmpty()) throw notFound();
    if (!"admin".equals(actor.get("role"))
        && (!actor.get("id").toString().equals(Objects.toString(keys.getFirst().get("user_id"), ""))
            || !"user".equals(keys.getFirst().get("principal_type")))) throw forbidden();
    return actor;
  }

  private static GatewayException forbidden() {
    return new GatewayException(
        HttpStatus.FORBIDDEN,
        "forbidden_error",
        "key_permission_denied",
        "This account cannot perform the requested API key operation");
  }

  private static String nullableText(JsonNode value) {
    return value.isNull() || value.isMissingNode() || value.asText().isBlank()
        ? null
        : value.asText();
  }

  private static UUID parseId(String value) {
    try {
      return UUID.fromString(value);
    } catch (Exception e) {
      throw bad("Invalid userId");
    }
  }

  private static GatewayException bad(String detail) {
    return new GatewayException(
        HttpStatus.BAD_REQUEST, "invalid_request_error", "invalid_request", detail);
  }

  private static GatewayException notFound() {
    return new GatewayException(
        HttpStatus.NOT_FOUND, "not_found_error", "api_key_not_found", "API key not found");
  }
}
