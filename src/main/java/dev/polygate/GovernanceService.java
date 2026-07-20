package dev.polygate;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class GovernanceService {
  static final List<String> ACTIONS =
      List.of(
          "project_policy.upsert",
          "provider.allowlist_change",
          "routing.cloud_first",
          "budget.hard_limit",
          "identity.permission",
          "model.production_promotion",
          "data_egress.change",
          "database.major_migration",
          "secret.rotation");
  private static final List<String> MODES =
      List.of("local_strict", "local_first", "balanced", "cloud_first");
  private final JdbcTemplate db;
  private final ConfigurationService config;
  private final ObjectMapper json;
  private final BudgetService budget;
  final boolean dualApproval;

  GovernanceService(
      ObjectProvider<JdbcTemplate> db,
      ConfigurationService config,
      ObjectMapper json,
      ObjectProvider<BudgetService> budget,
      @Value("${POLYGATE_REQUIRE_DUAL_APPROVAL:false}") boolean dualApproval) {
    this.db = db.getIfAvailable();
    this.config = config;
    this.json = json;
    this.budget = budget.getIfAvailable();
    this.dualApproval = dualApproval;
  }

  boolean requiresDualApproval() {
    return dualApproval;
  }

  ObjectNode policy(TenantScope scope) {
    JsonNode saved = config.read("governance").path("policies").path(scope.key());
    if (saved.isObject()) return ((ObjectNode) saved).deepCopy();
    ObjectNode policy = json.createObjectNode();
    policy
        .put("organizationId", scope.organizationId())
        .put("projectId", scope.projectId())
        .put("environmentId", scope.environmentId());
    boolean local = TenantScope.LOCAL.equals(scope);
    policy
        .put("maximumMode", local ? "cloud_first" : "local_strict")
        .put("defaultClassification", local ? "public" : "unknown")
        .put("cloudEnabled", local)
        .put("updatedBy", "")
        .put("updatedAtMs", 0);
    for (String field :
        List.of("allowedProviders", "allowedModels", "allowedRegions", "allowedApiVersions"))
      policy.putArray(field);
    return policy;
  }

  void enforce(
      ClientAuthService.Principal principal, RoutingService.Route route, HttpHeaders headers) {
    TenantScope scope = principal.scope();
    String org = headers.getFirst("x-polygate-organization-id"),
        project = headers.getFirst("x-polygate-project-id"),
        environment = headers.getFirst("x-polygate-environment-id");
    if (org != null || project != null || environment != null) {
      if (org == null || project == null || environment == null)
        throw bad("Tenant scope headers must be supplied together");
      if (!scope.equals(new TenantScope(org, project, environment)))
        throw forbidden("Tenant scope does not match the API key");
    }
    JsonNode policy = policy(scope);
    String classification =
        Objects.requireNonNullElse(
            headers.getFirst("x-polygate-data-classification"),
            policy.path("defaultClassification").asText());
    if (!Set.of("unknown", "sensitive", "internal", "public").contains(classification))
      throw bad("Invalid data classification");
    String maximum = policy.path("maximumMode").asText(),
        mode = Objects.requireNonNullElse(headers.getFirst("x-polygate-hybrid-mode"), maximum);
    if (!MODES.contains(mode)) throw bad("Invalid hybrid routing mode");
    if (MODES.indexOf(mode) > MODES.indexOf(maximum))
      throw forbidden("Requested routing mode exceeds the project policy");
    if (Set.of("unknown", "sensitive").contains(classification)) mode = "local_strict";
    boolean local = local(route);
    if (!local && (!policy.path("cloudEnabled").asBoolean() || "local_strict".equals(mode)))
      throw forbidden("Project classification or routing policy requires a local provider");
    if (!allowed(policy.path("allowedProviders"), route.providerId())
        || !allowed(policy.path("allowedModels"), route.upstreamModel())
            && !allowed(policy.path("allowedModels"), route.requestedModel()))
      throw forbidden("Project policy does not allow the model or provider");
    String region = route.config().path("governance").path("region").asText("global"),
        version =
            route
                .config()
                .path("governance")
                .path("apiVersion")
                .asText("anthropic".equals(route.protocol()) ? "anthropic-v1" : "openai-v1");
    if (!allowed(policy.path("allowedRegions"), region)
        || !allowed(policy.path("allowedApiVersions"), version))
      throw forbidden("Provider region or API version is outside the project policy");
    if (route.config() instanceof com.fasterxml.jackson.databind.node.ObjectNode config)
      config.put("_effectiveHybridMode", mode);
  }

  List<JsonNode> policies() {
    List<JsonNode> list = new ArrayList<>();
    config.read("governance").path("policies").elements().forEachRemaining(list::add);
    return list;
  }

  @Transactional(readOnly = true)
  List<Map<String, Object>> changes() {
    if (db == null) return List.of();
    return db.query(
        "SELECT * FROM gateway_change_request ORDER BY created_at DESC LIMIT 500",
        (rs, i) -> change(rs.getObject("id", UUID.class)));
  }

  Map<String, Object> change(UUID id) {
    var rows = db.queryForList("SELECT * FROM gateway_change_request WHERE id=?", id);
    if (rows.isEmpty()) throw missing();
    var row = rows.getFirst();
    Map<String, Object> result = new HashMap<>();
    result.put("id", id.toString());
    result.put("action", row.get("action"));
    result.put("target", row.get("target"));
    result.put("reason", row.get("reason"));
    result.put("payloadSha256", row.get("payload_sha256"));
    result.put("status", row.get("status"));
    result.put("risk", "high");
    result.put("requestedBy", row.get("requested_by").toString());
    result.put("requestedByName", row.get("requested_by_name"));
    result.put("createdAtMs", ((Timestamp) row.get("created_at")).toInstant().toEpochMilli());
    result.put("updatedAtMs", ((Timestamp) row.get("updated_at")).toInstant().toEpochMilli());
    result.put(
        "appliedAtMs",
        row.get("applied_at") == null
            ? null
            : ((Timestamp) row.get("applied_at")).toInstant().toEpochMilli());
    try {
      result.put("payload", json.readTree(row.get("payload").toString()));
      result.put("approvals", json.readTree(row.get("approvals").toString()));
    } catch (Exception e) {
      throw new IllegalStateException("Invalid change document", e);
    }
    return result;
  }

  @Transactional
  Map<String, Object> create(JsonNode input, Map<String, Object> actor) {
    String action = input.path("action").asText(),
        target = input.path("target").asText(),
        reason = input.path("reason").asText().trim();
    JsonNode payload = input.path("payload");
    if (!ACTIONS.contains(action)
        || target.isBlank()
        || target.length() > 512
        || reason.isBlank()
        || reason.length() > 2000
        || !payload.isObject()
        || payload.toString().length() > 131072) throw bad("Invalid change request");
    rejectSecrets(payload);
    UUID id = UUID.randomUUID();
    ArrayNode approvals = json.createArrayNode();
    approvals
        .addObject()
        .put("actorId", actor.get("id").toString())
        .put("actorName", actor.get("username").toString())
        .put("approvedAtMs", System.currentTimeMillis());
    db.update(
        "INSERT INTO"
            + " gateway_change_request(id,action,target,payload,payload_sha256,reason,requested_by,requested_by_name,approvals,status)"
            + " VALUES (?,?,?,?::jsonb,?,?,?,?,?::jsonb,?)",
        id,
        action,
        target,
        payload.toString(),
        hash(payload),
        reason,
        UUID.fromString(actor.get("id").toString()),
        actor.get("username"),
        approvals.toString(),
        dualApproval ? "pending_second_approval" : "approved");
    return change(id);
  }

  @Transactional
  Map<String, Object> approve(UUID id, Map<String, Object> actor) {
    var row = lock(id);
    if ("applied".equals(row.get("status"))) throw bad("Change was already applied");
    if (actor.get("id").toString().equals(row.get("requested_by").toString()))
      throw forbidden("A second administrator must approve this change");
    try {
      ArrayNode approvals = (ArrayNode) json.readTree(row.get("approvals").toString());
      for (JsonNode approval : approvals)
        if (actor.get("id").toString().equals(approval.path("actorId").asText()))
          throw bad("Administrator already approved this change");
      approvals
          .addObject()
          .put("actorId", actor.get("id").toString())
          .put("actorName", actor.get("username").toString())
          .put("approvedAtMs", System.currentTimeMillis());
      db.update(
          "UPDATE gateway_change_request SET approvals=?::jsonb,status='approved',updated_at=now()"
              + " WHERE id=?",
          approvals.toString(),
          id);
    } catch (GatewayException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Invalid approval record", e);
    }
    return change(id);
  }

  @Transactional
  Map<String, Object> apply(UUID id, Map<String, Object> actor) {
    var row = lock(id);
    if ("applied".equals(row.get("status"))) {
      try {
        return Map.of(
            "ok",
            true,
            "changeId",
            id.toString(),
            "action",
            row.get("action"),
            "result",
            row.get("result") == null
                ? json.createObjectNode()
                : json.readTree(row.get("result").toString()));
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
    if (!"approved".equals(row.get("status")))
      throw forbidden("Change is awaiting a second administrator");
    JsonNode payload;
    try {
      payload = json.readTree(row.get("payload").toString());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    if (!hash(payload).equals(row.get("payload_sha256")))
      throw bad("Approved payload hash does not match");
    Object result;
    String action = (String) row.get("action");
    if ("project_policy.upsert".equals(action)) {
      TenantScope scope = TenantScope.from(payload);
      if (!scope.key().equals(row.get("target")))
        throw bad("Project policy target does not match its payload");
      ObjectNode policy = validatePolicy(payload, actor.get("id").toString());
      db.queryForObject(
          "SELECT pg_advisory_xact_lock(hashtext('polygate-governance'))", Object.class);
      ObjectNode document = config.read("governance");
      document.withObject("/policies").set(scope.key(), policy);
      config.write("governance", document);
      result = policy;
    } else if ("budget.hard_limit".equals(action)) {
      TenantScope scope = TenantScope.from(payload);
      if (!scope.key().equals(row.get("target")))
        throw bad("Budget target does not match its payload");
      result = budget.update(payload, UUID.fromString(actor.get("id").toString()));
    } else
      throw bad("This approved action must be applied through its dedicated management operation");
    db.update(
        "UPDATE gateway_change_request SET"
            + " status='applied',result=?::jsonb,updated_at=now(),applied_at=now() WHERE id=?",
        json.valueToTree(result).toString(),
        id);
    return Map.of("ok", true, "changeId", id.toString(), "action", action, "result", result);
  }

  void requireApproved(HttpHeaders headers, String action, String target, JsonNode payload) {
    if (!dualApproval) return;
    String header = headers.getFirst("x-polygate-change-request-id");
    UUID id;
    try {
      id = UUID.fromString(header);
    } catch (Exception e) {
      throw forbidden("An approved change request is required");
    }
    var row = lock(id);
    if (!"approved".equals(row.get("status"))
        || !action.equals(row.get("action"))
        || !target.equals(row.get("target"))
        || !hash(permissionPayload(payload)).equals(row.get("payload_sha256")))
      throw forbidden("The approval does not authorize this exact operation and payload");
    db.update(
        "UPDATE gateway_change_request SET status='applied',updated_at=now(),applied_at=now() WHERE"
            + " id=?",
        id);
  }

  private Map<String, Object> lock(UUID id) {
    var rows = db.queryForList("SELECT * FROM gateway_change_request WHERE id=? FOR UPDATE", id);
    if (rows.isEmpty()) throw missing();
    return rows.getFirst();
  }

  private ObjectNode validatePolicy(JsonNode input, String actor) {
    TenantScope scope = TenantScope.from(input);
    ObjectNode policy = (ObjectNode) input.deepCopy();
    if (!MODES.contains(policy.path("maximumMode").asText())
        || !Set.of("unknown", "sensitive", "internal", "public")
            .contains(policy.path("defaultClassification").asText())
        || !policy.path("cloudEnabled").isBoolean()) throw bad("Invalid project routing policy");
    for (String field :
        List.of("allowedProviders", "allowedModels", "allowedRegions", "allowedApiVersions")) {
      JsonNode list = policy.path(field);
      if (!list.isArray() || list.size() > 256) throw bad("Invalid policy allowlist");
      for (JsonNode value : list)
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 240)
          throw bad("Invalid policy allowlist entry");
    }
    policy
        .put("organizationId", scope.organizationId())
        .put("projectId", scope.projectId())
        .put("environmentId", scope.environmentId())
        .put("updatedBy", actor)
        .put("updatedAtMs", System.currentTimeMillis());
    return policy;
  }

  private String hash(JsonNode value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical(value).toString().getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private JsonNode canonical(JsonNode input) {
    if (input.isObject()) {
      ObjectNode out = json.createObjectNode();
      List<String> names = new ArrayList<>();
      input.fieldNames().forEachRemaining(names::add);
      Collections.sort(names);
      for (String name : names) out.set(name, canonical(input.path(name)));
      return out;
    }
    if (input.isArray()) {
      ArrayNode out = json.createArrayNode();
      for (JsonNode value : input) out.add(canonical(value));
      return out;
    }
    return input;
  }

  private JsonNode permissionPayload(JsonNode input) {
    if (!input.isObject()) return input;
    ObjectNode copy = ((ObjectNode) input).deepCopy();
    copy.remove("password");
    return copy;
  }

  private static void rejectSecrets(JsonNode value) {
    if (value.isObject())
      value
          .fields()
          .forEachRemaining(
              entry -> {
                String name = entry.getKey().toLowerCase(Locale.ROOT);
                if (Set.of("password", "apikey", "api_key", "secret", "token", "authorization")
                    .contains(name))
                  throw bad(
                      "Approval documents must refer to secret names, not contain secret values");
                rejectSecrets(entry.getValue());
              });
    else if (value.isArray()) value.forEach(GovernanceService::rejectSecrets);
  }

  static boolean local(RoutingService.Route route) {
    String declared = route.config().path("governance").path("boundary").asText();
    if (!declared.isBlank()) return "local".equals(declared);
    URI url = route.upstreamUrl();
    String host = url.getHost();
    if (host == null) return false;
    return host.equals("localhost")
        || host.equals("::1")
        || host.startsWith("127.")
        || host.startsWith("10.")
        || host.startsWith("192.168.")
        || host.matches("172\\.(1[6-9]|2[0-9]|3[01])\\..*")
        || !host.contains(".")
        || host.endsWith(".internal")
        || host.endsWith(".local");
  }

  private static boolean allowed(JsonNode values, String value) {
    if (values.isMissingNode() || values.isEmpty()) return true;
    for (JsonNode rule : values) {
      String[] chunks = rule.asText().split("\\*", -1);
      StringJoiner pattern = new StringJoiner(".*", "^", "$");
      for (String chunk : chunks) pattern.add(java.util.regex.Pattern.quote(chunk));
      if (value.matches(pattern.toString())) return true;
    }
    return false;
  }

  private static GatewayException bad(String message) {
    return new GatewayException(
        HttpStatus.BAD_REQUEST, "invalid_request_error", "invalid_request", message);
  }

  private static GatewayException forbidden(String message) {
    return new GatewayException(
        HttpStatus.FORBIDDEN, "forbidden_error", "project_policy_denied", message);
  }

  private static GatewayException missing() {
    return new GatewayException(
        HttpStatus.NOT_FOUND, "not_found_error", "change_not_found", "Change request not found");
  }
}
