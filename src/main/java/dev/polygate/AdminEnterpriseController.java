package dev.polygate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.*;
import java.sql.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "polygate.database.enabled", havingValue = "true")
class AdminEnterpriseController {
  private static final String SELECT =
      "SELECT"
          + " r.*,u.input_tokens,u.output_tokens,u.cache_read_tokens,u.cache_write_tokens,u.cost_estimate,u.actual_cost,u.billable_cost,u.pricing_evidence,u.estimated"
          + " FROM gateway_request r LEFT JOIN gateway_usage u ON u.request_id=r.id";
  private final JdbcTemplate db;
  private final AdminAuthService auth;
  private final BudgetService budget;
  private final GovernanceService governance;
  private final ObjectMapper json;

  AdminEnterpriseController(
      JdbcTemplate db,
      AdminAuthService auth,
      BudgetService budget,
      GovernanceService governance,
      ObjectMapper json) {
    this.db = db;
    this.auth = auth;
    this.budget = budget;
    this.governance = governance;
    this.json = json;
  }

  @GetMapping("/admin/enterprise/overview")
  Object overview(@RequestHeader HttpHeaders headers) {
    auth.requireAdmin(headers);
    Map<String, Object> view = new HashMap<>();
    view.put("backend", "postgres");
    view.put("location", "postgresql://polygate");
    view.put("leaseTtlSecs", 86400);
    view.put("reconcileIntervalSecs", 60);
    var counts =
        db.queryForMap(
            "SELECT count(*) total,count(*) FILTER(WHERE state='started') started,count(*)"
                + " FILTER(WHERE state='completed') completed,count(*) FILTER(WHERE state='failed')"
                + " failed,count(DISTINCT organization_id) organizations,count(DISTINCT"
                + " (organization_id,project_id)) projects,count(DISTINCT"
                + " (organization_id,project_id,environment_id)) environments FROM"
                + " gateway_request");
    view.put("totalRequests", counts.get("total"));
    view.put("startedRequests", counts.get("started"));
    view.put("completedRequests", counts.get("completed"));
    view.put("failedRequests", counts.get("failed"));
    view.put(
        "cancelledRequests",
        db.queryForObject(
            "SELECT count(*) FROM gateway_request WHERE state='cancelled'", Long.class));
    view.put("unreconciledRequests", counts.get("started"));
    view.put(
        "idempotentRequests",
        db.queryForObject(
            "SELECT count(*) FROM gateway_request WHERE has_idempotency_key=true", Long.class));
    view.put(
        "activeLeases",
        db.queryForObject("SELECT count(*) FROM gateway_budget_reservation", Long.class));
    view.put(
        "expiredLeases",
        db.queryForObject(
            "SELECT count(*) FROM gateway_request WHERE state='started' AND"
                + " started_at<now()-interval '2 hours'",
            Long.class));
    long billable =
        db.queryForObject(
            "SELECT count(*) FROM gateway_usage WHERE billable_cost IS NOT NULL", Long.class);
    view.put("chargeableRequests", billable);
    view.put("estimateOnlyRequests", ((Number) counts.get("total")).longValue() - billable);
    view.put(
        "totalCostMicrounits",
        db.queryForObject(
            "SELECT COALESCE(round(sum(cost_estimate)*1000000),0) FROM gateway_usage",
            BigDecimal.class));
    view.put(
        "totalBillableCostMicrounits",
        db.queryForObject(
            "SELECT COALESCE(round(sum(billable_cost)*1000000),0) FROM gateway_usage",
            BigDecimal.class));
    view.put("organizationCount", counts.get("organizations"));
    view.put("projectCount", counts.get("projects"));
    view.put("environmentCount", counts.get("environments"));
    return view;
  }

  @GetMapping("/admin/enterprise/requests")
  Object requests(@RequestHeader HttpHeaders headers, @RequestParam Map<String, String> params) {
    auth.requireAdmin(headers);
    int page = positive(params.getOrDefault("page", "1"), 100000),
        size = positive(params.getOrDefault("pageSize", "20"), 500);
    StringBuilder where = new StringBuilder(" WHERE true");
    List<Object> args = new ArrayList<>();
    for (var filter :
        Map.of(
                "state",
                "r.state",
                "organizationId",
                "r.organization_id",
                "projectId",
                "r.project_id",
                "environmentId",
                "r.environment_id")
            .entrySet()) {
      String value = params.get(filter.getKey());
      if (value != null && !value.isBlank()) {
        where.append(" AND ").append(filter.getValue()).append("=?");
        args.add(value);
      }
    }
    String protocol = params.get("protocol");
    if (protocol != null && !protocol.isBlank()) {
      if (!Set.of("anthropic-messages", "openai-chat-completions").contains(protocol))
        throw bad("Invalid client protocol");
      where.append(" AND r.client_protocol=?");
      args.add("openai-chat-completions".equals(protocol) ? "openai" : "anthropic");
    }
    String traffic = params.get("trafficClass");
    if (traffic != null && !traffic.isBlank()) {
      if (!Set.of("business", "batch", "synthetic", "diagnostic").contains(traffic))
        throw bad("Invalid traffic class");
      where.append(" AND r.traffic_class=?");
      args.add(traffic);
    }
    String search = params.get("search");
    if (search != null && !search.isBlank()) {
      where.append(
          " AND (r.id::text ILIKE ? OR r.requested_model ILIKE ? OR r.provider_id ILIKE ?)");
      for (int i = 0; i < 3; i++) args.add("%" + search + "%");
    }
    long total =
        db.queryForObject(
            "SELECT count(*) FROM gateway_request r" + where, Long.class, args.toArray());
    List<Object> paged = new ArrayList<>(args);
    paged.add(size);
    paged.add((long) (page - 1) * size);
    var rows =
        db.query(
            SELECT + where + " ORDER BY r.started_at DESC,r.id DESC LIMIT ? OFFSET ?",
            (rs, i) -> requestRow(rs),
            paged.toArray());
    return Map.of("requests", rows, "total", total, "page", page, "pageSize", size);
  }

  @GetMapping("/admin/enterprise/requests/{id}")
  Object detail(@RequestHeader HttpHeaders headers, @PathVariable UUID id) {
    auth.requireAdmin(headers);
    var rows = db.query(SELECT + " WHERE r.id=?", (rs, i) -> requestRow(rs), id);
    if (rows.isEmpty())
      throw new GatewayException(
          HttpStatus.NOT_FOUND, "not_found_error", "request_not_found", "Request not found");
    var request = rows.getFirst();
    var attempts =
        db.query(
            "SELECT * FROM gateway_provider_attempt WHERE request_id=? ORDER BY ordinal",
            (rs, i) -> {
              Map<String, Object> attempt = new HashMap<>(request);
              attempt.put("attemptId", rs.getObject("id").toString());
              attempt.put("requestLedgerId", id.toString());
              attempt.put("providerId", rs.getString("provider_id"));
              attempt.put("resolvedModel", rs.getString("upstream_model"));
              attempt.put("providerProtocol", rs.getString("provider_protocol"));
              attempt.put("credentialId", rs.getString("credential_id"));
              attempt.put("state", rs.getString("state"));
              attempt.put("statusCode", rs.getObject("http_status"));
              attempt.put("errorMessage", rs.getString("safe_error_code"));
              attempt.put("terminalReason", rs.getString("safe_error_code"));
              attempt.put("inputTokens", rs.getLong("input_tokens"));
              attempt.put("outputTokens", rs.getLong("output_tokens"));
              attempt.put("cacheReadTokens", rs.getLong("cache_read_tokens"));
              attempt.put("cacheWriteTokens", rs.getLong("cache_write_tokens"));
              attempt.put("latencyMs", rs.getLong("latency_ms"));
              attempt.put("createdAtMs", rs.getTimestamp("started_at").toInstant().toEpochMilli());
              attempt.put("completedAtMs", rs.getTimestamp("ended_at").toInstant().toEpochMilli());
              attempt.put("updatedAtMs", rs.getTimestamp("ended_at").toInstant().toEpochMilli());
              costs(rs, attempt);
              return attempt;
            },
            id);
    if (attempts.isEmpty()) {
      var historic = new HashMap<>(request);
      historic.put("attemptId", id.toString());
      historic.put("requestLedgerId", id.toString());
      attempts = List.of(historic);
    }
    return Map.of("request", request, "attempts", attempts);
  }

  @GetMapping("/admin/enterprise/budget")
  Object budget(
      @RequestHeader HttpHeaders headers,
      @RequestParam(defaultValue = "org_local") String organizationId,
      @RequestParam(defaultValue = "prj_default") String projectId,
      @RequestParam(defaultValue = "env_default") String environmentId) {
    auth.requireAdmin(headers);
    return budget.view(new TenantScope(organizationId, projectId, environmentId));
  }

  @PutMapping("/admin/enterprise/budget")
  @Transactional
  Object update(@RequestHeader HttpHeaders headers, @RequestBody JsonNode input) {
    auth.requireWrite(headers);
    auth.requireAdmin(headers);
    return budget.update(input, UUID.fromString(auth.requireSession(headers).get("id").toString()));
  }

  @PostMapping("/admin/enterprise/budget/adjustments")
  @Transactional
  Object adjust(@RequestHeader HttpHeaders headers, @RequestBody JsonNode input) {
    auth.requireWrite(headers);
    auth.requireAdmin(headers);
    return budget.adjust(input, UUID.fromString(auth.requireSession(headers).get("id").toString()));
  }

  private Map<String, Object> requestRow(ResultSet rs) throws SQLException {
    Map<String, Object> row = new HashMap<>();
    String id = rs.getObject("id").toString();
    row.put("ledgerId", id);
    row.put("requestId", id);
    row.put("organizationId", rs.getString("organization_id"));
    row.put("projectId", rs.getString("project_id"));
    row.put("environmentId", rs.getString("environment_id"));
    row.put(
        "principalId",
        rs.getObject("user_id") == null ? "legacy" : rs.getObject("user_id").toString());
    String protocol = rs.getString("client_protocol");
    row.put(
        "clientProtocol",
        "openai".equals(protocol) ? "openai-chat-completions" : "anthropic-messages");
    row.put("requestedModel", rs.getString("requested_model"));
    row.put("requestPath", "openai".equals(protocol) ? "/v1/chat/completions" : "/v1/messages");
    row.put("trafficClass", rs.getString("traffic_class"));
    row.put("providerId", rs.getString("provider_id"));
    row.put("resolvedModel", rs.getString("upstream_model"));
    row.put("providerProtocol", rs.getString("provider_protocol"));
    row.put("credentialId", rs.getString("credential_id"));
    row.put("toolUseRequested", rs.getBoolean("tool_requested"));
    row.put("stream", rs.getBoolean("stream"));
    row.put("state", rs.getString("state"));
    row.put("statusCode", rs.getObject("http_status"));
    row.put("terminalReason", rs.getString("safe_error_code"));
    row.put("errorMessage", rs.getString("safe_error_code"));
    row.put("inputTokens", rs.getLong("input_tokens"));
    row.put("outputTokens", rs.getLong("output_tokens"));
    row.put("cacheReadTokens", rs.getLong("cache_read_tokens"));
    row.put("cacheWriteTokens", rs.getLong("cache_write_tokens"));
    BigDecimal cost = rs.getBigDecimal("cost_estimate");
    row.put(
        "costAmountMicrounits",
        cost == null
            ? 0
            : cost.multiply(BigDecimal.valueOf(1000000)).setScale(0, RoundingMode.HALF_UP));
    row.put("actualCostMicrounits", null);
    row.put("billableCostMicrounits", null);
    row.put("pricingEvidence", null);
    row.put("currency", "USD");
    row.put(
        "billingMode",
        rs.getBoolean("estimated") ? "conservative_estimate" : "provider_reported_usage");
    row.put("chargeable", rs.getBigDecimal("billable_cost") != null);
    row.put("latencyMs", rs.getLong("latency_ms"));
    row.put("firstByteLatencyMs", null);
    row.put(
        "toolOutcome", rs.getBoolean("tool_requested") ? "completed_unobserved" : "not_requested");
    row.put("toolRepairAttempted", rs.getBoolean("tool_repair_attempted"));
    row.put("toolRepairRecovered", rs.getBoolean("tool_repair_recovered"));
    row.put("retryCount", rs.getInt("retry_count"));
    row.put("fallbackFromProvider", rs.getString("fallback_from_provider"));
    row.put("hasIdempotencyKey", rs.getBoolean("has_idempotency_key"));
    row.put("leaseOwner", "polygate");
    long created = rs.getTimestamp("started_at").toInstant().toEpochMilli();
    row.put("leaseExpiresAtMs", created + 86400000);
    row.put("createdAtMs", created);
    Timestamp end = rs.getTimestamp("ended_at");
    row.put("updatedAtMs", end == null ? created : end.toInstant().toEpochMilli());
    row.put("completedAtMs", end == null ? null : end.toInstant().toEpochMilli());
    row.put("attemptCount", rs.getInt("retry_count") + 1);
    costs(rs, row);
    return row;
  }

  private void costs(ResultSet rs, Map<String, Object> row) throws SQLException {
    BigDecimal estimate = rs.getBigDecimal("cost_estimate"),
        actual = rs.getBigDecimal("actual_cost"),
        billable = rs.getBigDecimal("billable_cost");
    row.put(
        "costAmountMicrounits",
        estimate == null
            ? 0
            : estimate.multiply(BigDecimal.valueOf(1000000)).setScale(0, RoundingMode.HALF_UP));
    row.put(
        "actualCostMicrounits",
        actual == null
            ? null
            : actual.multiply(BigDecimal.valueOf(1000000)).setScale(0, RoundingMode.HALF_UP));
    row.put(
        "billableCostMicrounits",
        billable == null
            ? null
            : billable.multiply(BigDecimal.valueOf(1000000)).setScale(0, RoundingMode.HALF_UP));
    row.put("chargeable", billable != null);
    String evidence = rs.getString("pricing_evidence");
    try {
      row.put("pricingEvidence", evidence == null ? null : json.readTree(evidence));
    } catch (Exception e) {
      throw new SQLException("Invalid pricing evidence", e);
    }
  }

  private static int positive(String text, int maximum) {
    try {
      int value = Integer.parseInt(text);
      if (value > 0 && value <= maximum) return value;
    } catch (Exception ignored) {
    }
    throw bad("Invalid pagination");
  }

  private static GatewayException bad(String message) {
    return new GatewayException(
        HttpStatus.BAD_REQUEST, "invalid_request_error", "invalid_request", message);
  }
}
