package dev.polygate;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "polygate.database.enabled", havingValue = "true")
class OperationsController {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final AdminAuthService admin;
  private final ClientAuthService clients;
  private final ConfigurationService config;
  private final RoutingService routing;
  private final org.springframework.beans.factory.ObjectProvider<JavaOperationsAgent> agent;
  private final GovernanceService governance;

  OperationsController(
      JdbcTemplate db,
      ObjectMapper json,
      AdminAuthService admin,
      ClientAuthService clients,
      ConfigurationService config,
      RoutingService routing,
      org.springframework.beans.factory.ObjectProvider<JavaOperationsAgent> agent,
      GovernanceService governance) {
    this.db = db;
    this.json = json;
    this.admin = admin;
    this.clients = clients;
    this.config = config;
    this.routing = routing;
    this.agent = agent;
    this.governance = governance;
  }

  @GetMapping("/admin/ops/configuration")
  ObjectNode configuration(@RequestHeader HttpHeaders h) {
    admin.requireAdmin(h);
    return configuration();
  }

  ObjectNode configuration() {
    ObjectNode c =
        json.createObjectNode()
            .put("enabled", false)
            .put("analysisEnabled", false)
            .putNull("selectedModel")
            .put("preferLocal", true);
    ConfigurationService.merge(c, config.read("operations"));
    ArrayNode candidates = c.putArray("candidates");
    boolean ready = false, local = false;
    String recommended = null;
    for (var model : routing.models())
      try {
        var route = routing.resolve(model.get("id"));
        if (route.keyRequired() && route.upstreamKey().isBlank()) continue;
        governance.enforce(
            new ClientAuthService.Principal(null, null, List.of(), TenantScope.LOCAL),
            route,
            new HttpHeaders());
        boolean isLocal = GovernanceService.local(route);
        candidates
            .addObject()
            .put("id", model.get("id"))
            .put("providerId", route.providerId())
            .put("model", route.upstreamModel())
            .put("displayName", model.get("id"))
            .put("local", isLocal);
        if (recommended == null || c.path("preferLocal").asBoolean() && isLocal)
          recommended = model.get("id");
        if (model.get("id").equals(c.path("selectedModel").asText())) {
          ready = true;
          local = isLocal;
        }
      } catch (GatewayException ignored) {
      }
    c.put("modelReady", ready).put("selectedModelLocal", local);
    if (recommended == null) c.putNull("recommendedModel");
    else c.put("recommendedModel", recommended);
    return c;
  }

  @PutMapping("/admin/ops/configuration")
  ObjectNode configure(@RequestHeader HttpHeaders h, @RequestBody JsonNode input) {
    admin.requireWrite(h);
    admin.requireAdmin(h);
    for (String f : List.of("enabled", "analysisEnabled", "preferLocal"))
      if (!input.path(f).isBoolean()) throw bad("Invalid " + f);
    String selected = input.path("selectedModel").asText("");
    boolean available = false;
    for (JsonNode c : configuration().path("candidates"))
      if (selected.equals(c.path("id").asText())) available = true;
    if (!selected.isBlank() && !available
        || input.path("analysisEnabled").asBoolean() && !available)
      throw bad("Select an available operations model before enabling analysis");
    config.write("operations", input);
    return configuration();
  }

  @GetMapping("/admin/ops/incidents")
  Map<String, Object> list(
      @RequestHeader HttpHeaders h,
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(required = false) String status) {
    admin.requireAdmin(h);
    if (limit < 1 || limit > 1000) throw bad("Invalid incident limit");
    List<ObjectNode> all =
        db.query(
            "SELECT document::text FROM gateway_ops_incident ORDER BY"
                + " (document->>'lastSeenAtMs')::bigint DESC",
            (rs, i) -> parse(rs.getString(1)));
    var filtered =
        all.stream()
            .filter(d -> status == null || status.equals(d.path("status").asText()))
            .toList();
    var open =
        all.stream()
            .filter(d -> !Set.of("resolved", "suppressed").contains(d.path("status").asText()))
            .toList();
    Map<String, Object> out = new HashMap<>();
    out.put("items", filtered.stream().limit(limit).toList());
    out.put("total", filtered.size());
    out.put("open", open.size());
    out.put(
        "highestOpenSeverity",
        open.stream().map(d -> d.path("severity").asText()).sorted().findFirst().orElse(null));
    out.put(
        "agents",
        db.query(
            "SELECT document::text,received_at FROM gateway_ops_heartbeat",
            (rs, i) -> {
              ObjectNode node = parse(rs.getString(1));
              node.put(
                  "online",
                  rs.getTimestamp(2).getTime()
                          + Math.max(30000, node.path("intervalSeconds").asLong(30) * 3000)
                      > System.currentTimeMillis());
              return node;
            }));
    return out;
  }

  @GetMapping("/admin/ops/incidents/{id}")
  ObjectNode detail(@RequestHeader HttpHeaders h, @PathVariable UUID id) {
    admin.requireAdmin(h);
    ObjectNode d = incident(id, false);
    d.set(
        "evidence",
        json.valueToTree(
            db.query(
                "SELECT document::text FROM gateway_ops_evidence WHERE incident_id=? ORDER BY"
                    + " (document->>'observedAtMs')::bigint DESC LIMIT 200",
                (rs, i) -> parse(rs.getString(1)),
                id)));
    d.set(
        "timeline",
        json.valueToTree(
            db.query(
                "SELECT document::text FROM gateway_ops_timeline WHERE incident_id=? ORDER BY"
                    + " (document->>'occurredAtMs')::bigint",
                (rs, i) -> parse(rs.getString(1)),
                id)));
    return d;
  }

  @PostMapping("/admin/ops/incidents/{id}/status")
  @Transactional
  ObjectNode status(
      @RequestHeader HttpHeaders h, @PathVariable UUID id, @RequestBody JsonNode input) {
    admin.requireWrite(h);
    admin.requireAdmin(h);
    String next = input.path("status").asText(), reason = text(input, "reason", 2000);
    if (!Set.of("acknowledged", "mitigating", "monitoring", "suppressed").contains(next))
      throw bad(
          "Status must be acknowledged, mitigating, monitoring or suppressed; recovery resolves"
              + " incidents");
    ObjectNode d = incident(id, true);
    d.put("status", next);
    db.update("UPDATE gateway_ops_incident SET document=?::jsonb WHERE id=?", d.toString(), id);
    var actor = admin.requireSession(h);
    timeline(
        id, "status_changed", actor.get("id").toString(), actor.get("username").toString(), reason);
    return d;
  }

  @PostMapping("/admin/ops/incidents/{id}/feedback")
  @Transactional
  Map<String, Boolean> feedback(
      @RequestHeader HttpHeaders h, @PathVariable UUID id, @RequestBody JsonNode input) {
    admin.requireWrite(h);
    admin.requireAdmin(h);
    incident(id, true);
    if (!Set.of("true_positive", "false_positive", "needs_review")
        .contains(input.path("outcome").asText())) throw bad("Invalid feedback outcome");
    if (input.toString().length() > 4000) throw bad("Feedback is too long");
    var actor = admin.requireSession(h);
    timeline(
        id,
        "feedback",
        actor.get("id").toString(),
        actor.get("username").toString(),
        input.toString());
    return Map.of("accepted", true);
  }

  @GetMapping("/internal/ops/v1/snapshot")
  ObjectNode snapshot(@RequestHeader HttpHeaders h) {
    var principal = clients.authenticateAgent(h);
    TenantScope s = principal.scope();
    Object[] scope = {s.organizationId(), s.projectId(), s.environmentId()};
    String filter = " WHERE organization_id=? AND project_id=? AND environment_id=?";
    ObjectNode out =
        json.createObjectNode()
            .put("capturedAtMs", System.currentTimeMillis())
            .put("databaseReady", true)
            .put("authReady", true)
            .put("controlReady", true)
            .put("governanceReady", true)
            .put("gatewayReady", true)
            .put("draining", false)
            .put("pendingFinalizers", 0);
    out.putArray("degradedLedgerOperations");
    out.putObject("providerHealth");
    out.set(
        "requests",
        db.queryForObject(
            "SELECT count(*) total,count(*) FILTER(WHERE state='completed') success,count(*)"
                + " FILTER(WHERE http_status>=500) errors,count(*) FILTER(WHERE http_status=429)"
                + " limited,count(*) FILTER(WHERE safe_error_code LIKE '%protocol%')"
                + " protocol,count(*) FILTER(WHERE stream AND state='failed')"
                + " streams,COALESCE(avg(latency_ms),0) latency FROM gateway_request"
                + filter
                + " AND started_at>now()-interval '5 minutes'",
            (rs, i) ->
                json.createObjectNode()
                    .put("windowSeconds", 300)
                    .put("totalRequests", rs.getLong("total"))
                    .put("successfulRequests", rs.getLong("success"))
                    .put("serverErrors", rs.getLong("errors"))
                    .put("rateLimited", rs.getLong("limited"))
                    .put("protocolFailures", rs.getLong("protocol"))
                    .put("streamFailures", rs.getLong("streams"))
                    .put("averageLatencyMs", rs.getLong("latency")),
            scope));
    ObjectNode ledger = out.putObject("ledger");
    ledger.put(
        "unreconciledRequests",
        db.queryForObject(
            "SELECT count(*) FROM gateway_request" + filter + " AND state='started'",
            Long.class,
            scope));
    ledger.put(
        "openUsageReservations",
        db.queryForObject(
            "SELECT count(*) FROM gateway_budget_reservation" + filter, Long.class, scope));
    ledger.put(
        "oldestOpenReservationAgeMs",
        db.queryForObject(
            "SELECT COALESCE(extract(epoch FROM now()-min(created_at))*1000,0)::bigint FROM"
                + " gateway_budget_reservation"
                + filter,
            Long.class,
            scope));
    ledger.put(
        "budgetAccountsAtOrAbove80Percent",
        db.queryForObject(
            "SELECT count(*) FROM gateway_budget_account"
                + filter
                + " AND limit_microunits>0 AND"
                + " (settled_microunits+reserved_microunits)>=limit_microunits*.8",
            Long.class,
            scope));
    ledger.put(
        "budgetAccountsExhausted",
        db.queryForObject(
            "SELECT count(*) FROM gateway_budget_account"
                + filter
                + " AND limit_microunits IS NOT NULL AND"
                + " (settled_microunits+reserved_microunits)>=limit_microunits",
            Long.class,
            scope));
    out.putNull("recentChangeAtMs");
    out.set("agentConfiguration", configuration());
    return out;
  }

  @PostMapping("/internal/ops/v1/heartbeats")
  Map<String, Boolean> heartbeat(@RequestHeader HttpHeaders h, @RequestBody JsonNode input) {
    var principal = clients.authenticateAgent(h);
    return recordHeartbeat(principal.apiKeyId(), input);
  }

  Map<String, Boolean> recordHeartbeat(UUID instance, JsonNode input) {
    if (!Set.of("disabled", "replay", "shadow", "read_only").contains(input.path("mode").asText())
        || !Set.of("disabled", "configured", "missing_credential", "error")
            .contains(input.path("modelStatus").asText("disabled")))
      throw bad("Invalid heartbeat status");
    long interval = input.path("intervalSeconds").asLong();
    if (interval < 1 || interval > 86400 || input.toString().length() > 8000)
      throw bad("Invalid heartbeat");
    ObjectNode node = ((ObjectNode) input).deepCopy();
    node.put("instanceId", instance.toString()).put("observedAtMs", System.currentTimeMillis());
    db.update(
        "INSERT INTO gateway_ops_heartbeat(instance_id,document) VALUES (?,?::jsonb) ON"
            + " CONFLICT(instance_id) DO UPDATE SET document=excluded.document,received_at=now()",
        instance,
        node.toString());
    return Map.of("accepted", true);
  }

  @PostMapping("/internal/ops/v1/observations")
  @Transactional
  Map<String, Object> observation(@RequestHeader HttpHeaders h, @RequestBody JsonNode input) {
    var principal = clients.authenticateAgent(h);
    return observe(principal, input);
  }

  @Transactional
  Map<String, Object> observe(ClientAuthService.Principal principal, JsonNode input) {
    String key = text(input, "eventKey", 256),
        detector = text(input, "detectorType", 128),
        title = text(input, "title", 500),
        summary = text(input, "summary", 8000),
        recovery = text(input, "recoveryCriteria", 2000);
    if (!Set.of("SEV-1", "SEV-2", "SEV-3", "SEV-4").contains(input.path("severity").asText())
        || !input.path("active").isBoolean()
        || !input.path("affectedScope").isObject()
        || !input.path("evidence").isObject()
        || input.toString().length() > 32768) throw bad("Invalid observation");
    TenantScope s = principal.scope();
    db.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtext(?))",
        Object.class,
        s.organizationId() + "/" + s.projectId() + "/" + s.environmentId() + "/" + key);
    var existing =
        db.query(
            "SELECT document::text FROM gateway_ops_incident WHERE organization_id=? AND"
                + " project_id=? AND environment_id=? AND event_key=? FOR UPDATE",
            (rs, i) -> parse(rs.getString(1)),
            s.organizationId(),
            s.projectId(),
            s.environmentId(),
            key);
    long now = System.currentTimeMillis();
    boolean active = input.path("active").asBoolean();
    if (existing.isEmpty() && !active) return Map.of("accepted", true);
    if (!existing.isEmpty()
        && !active
        && "resolved".equals(existing.getFirst().path("status").asText()))
      return Map.of("incident", existing.getFirst());
    ObjectNode d =
        existing.isEmpty()
            ? json.createObjectNode()
                .put("id", UUID.randomUUID().toString())
                .put("firstSeenAtMs", now)
                .put("occurrenceCount", 0)
                .put("status", "open")
            : existing.getFirst();
    String prior = d.path("status").asText();
    d.put("eventKey", key)
        .put("detectorType", detector)
        .put("severity", input.path("severity").asText())
        .put("title", title)
        .put("summary", summary)
        .put("recoveryCriteria", recovery)
        .put("lastSeenAtMs", now)
        .put("occurrenceCount", d.path("occurrenceCount").asLong() + 1);
    d.set("affectedScope", input.path("affectedScope"));
    if (!active) d.put("status", "resolved").put("resolvedAtMs", now);
    else if ("resolved".equals(prior)) d.put("status", "open").putNull("resolvedAtMs");
    else if (!d.has("resolvedAtMs")) d.putNull("resolvedAtMs");
    UUID id = UUID.fromString(d.path("id").asText());
    db.update(
        "INSERT INTO"
            + " gateway_ops_incident(id,event_key,organization_id,project_id,environment_id,document)"
            + " VALUES (?,?,?,?,?,?::jsonb) ON CONFLICT(id) DO UPDATE SET"
            + " document=excluded.document",
        id,
        key,
        s.organizationId(),
        s.projectId(),
        s.environmentId(),
        d.toString());
    ObjectNode evidence =
        json.createObjectNode()
            .put("id", UUID.randomUUID().toString())
            .put("incidentId", id.toString())
            .put("observedAtMs", now);
    evidence.set("evidence", input.path("evidence"));
    db.update(
        "INSERT INTO gateway_ops_evidence(id,incident_id,document) VALUES (?,?,?::jsonb)",
        UUID.fromString(evidence.path("id").asText()),
        id,
        evidence.toString());
    timeline(
        id,
        active ? existing.isEmpty() ? "opened" : "observed" : "recovered",
        principal.userId().toString(),
        "operations service account",
        active ? "Detector observed an active condition" : "Detector confirmed recovery");
    return Map.of("incident", d);
  }

  private ObjectNode incident(UUID id, boolean lock) {
    var rows =
        db.query(
            "SELECT document::text FROM gateway_ops_incident WHERE id=?"
                + (lock ? " FOR UPDATE" : ""),
            (rs, i) -> parse(rs.getString(1)),
            id);
    if (rows.isEmpty())
      throw new GatewayException(
          HttpStatus.NOT_FOUND, "not_found_error", "incident_not_found", "Incident not found");
    return rows.getFirst();
  }

  @PostMapping("/admin/ops/run")
  Object run(@RequestHeader HttpHeaders headers) {
    admin.requireWrite(headers);
    admin.requireAdmin(headers);
    return agent.getObject().check();
  }

  private void timeline(UUID incident, String event, String actor, String name, String message) {
    UUID id = UUID.randomUUID();
    ObjectNode d =
        json.createObjectNode()
            .put("id", id.toString())
            .put("incidentId", incident.toString())
            .put("eventType", event)
            .put("actorId", actor)
            .put("actorName", name)
            .put("message", message)
            .put("occurredAtMs", System.currentTimeMillis());
    db.update(
        "INSERT INTO gateway_ops_timeline(id,incident_id,document) VALUES (?,?,?::jsonb)",
        id,
        incident,
        d.toString());
  }

  private ObjectNode parse(String value) {
    try {
      return (ObjectNode) json.readTree(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String text(JsonNode input, String field, int max) {
    String value = input.path(field).asText("").trim();
    if (!input.path(field).isTextual() || value.isEmpty() || value.length() > max)
      throw bad("Invalid " + field);
    return value;
  }

  private static GatewayException bad(String detail) {
    return new GatewayException(
        HttpStatus.BAD_REQUEST, "invalid_request_error", "invalid_operations_input", detail);
  }
}
