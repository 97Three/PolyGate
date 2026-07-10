package dev.polygate;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "polygate.database.enabled", havingValue = "true")
class AdminLogsController {
  private static final String SELECT =
      """
SELECT r.*, COALESCE(u.input_tokens,0) input_tokens, COALESCE(u.output_tokens,0) output_tokens,
       COALESCE(u.cache_read_tokens,0) cache_read_tokens, COALESCE(u.cache_write_tokens,0) cache_write_tokens,
       u.cost_estimate,u.actual_cost,u.billable_cost,u.pricing_evidence::text pricing_evidence,u.estimated,r.price_card::text pricing_snapshot,k.name api_key_name, k.key_group api_key_group, g.username
FROM gateway_request r LEFT JOIN gateway_usage u ON u.request_id=r.id
LEFT JOIN gateway_api_key k ON k.id=r.api_key_id LEFT JOIN gateway_user g ON g.id=r.user_id
""";
  private final JdbcTemplate database;
  private final AdminAuthService auth;
  private final ObjectMapper json;

  AdminLogsController(JdbcTemplate database, AdminAuthService auth, ObjectMapper json) {
    this.database = database;
    this.auth = auth;
    this.json = json;
  }

  @GetMapping("/admin/logs")
  Map<String, Object> logs(
      @RequestHeader HttpHeaders headers, @RequestParam MultiValueMap<String, String> params) {
    var actor = auth.requireSession(headers);
    int page = boundedInt(params.getFirst("page"), 1, 1, 100000);
    int pageSize = boundedInt(params.getFirst("pageSize"), 20, 1, 500);
    StringBuilder where = new StringBuilder(" WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (!"admin".equals(actor.get("role"))) {
      String user = params.getFirst("userId");
      if (user != null && !user.isBlank() && !actor.get("id").equals(user))
        throw new GatewayException(
            HttpStatus.FORBIDDEN,
            "forbidden_error",
            "log_permission_denied",
            "Cannot read another user's logs");
      eq(where, args, "r.user_id", UUID.fromString(actor.get("id").toString()));
    }
    eq(where, args, "r.provider_id", params.getFirst("provider"));
    eq(where, args, "r.user_id", uuid(params.getFirst("userId")));
    eq(where, args, "r.api_key_id", uuid(params.getFirst("apiKeyId")));
    eq(where, args, "k.key_group", params.getFirst("group"));
    eq(where, args, "g.username", params.getFirst("username"));
    String model = params.getFirst("model");
    if (model != null && !model.isBlank()) {
      where.append(" AND (r.requested_model ILIKE ? OR r.upstream_model ILIKE ?)");
      args.add("%" + model + "%");
      args.add("%" + model + "%");
    }
    String status = params.getFirst("status");
    if (status != null && !status.isBlank()) {
      if (!List.of("success", "error", "timeout").contains(status))
        throw bad("Invalid status filter");
      where.append(" AND r.state=?");
      args.add("success".equals(status) ? "completed" : "failed");
    }
    String stream = params.getFirst("stream");
    if (stream != null && !stream.isBlank()) {
      if (!List.of("stream", "non-stream").contains(stream)) throw bad("Invalid stream filter");
      eq(where, args, "r.stream", "stream".equals(stream));
    }
    String toolUse = params.getFirst("toolUse");
    if (toolUse != null && !toolUse.isBlank()) {
      if (!List.of("requested", "not-requested").contains(toolUse))
        throw bad("Invalid tool-use filter");
      eq(where, args, "r.tool_requested", "requested".equals(toolUse));
    }
    String traffic = params.getFirst("trafficClass");
    if (traffic != null && !traffic.isBlank()) {
      if (!List.of("business", "synthetic", "diagnostic").contains(traffic))
        throw bad("Invalid traffic class");
      eq(where, args, "r.traffic_class", traffic);
    }
    epoch(where, args, "r.started_at >= ?", params.getFirst("dateFrom"));
    epoch(where, args, "r.started_at <= ?", params.getFirst("dateTo"));
    String search = params.getFirst("search");
    if (search != null && !search.isBlank()) {
      where.append(
          " AND (r.requested_model ILIKE ? OR r.provider_id ILIKE ? OR r.id::text ILIKE ?)");
      for (int i = 0; i < 3; i++) args.add("%" + search + "%");
    }
    Long total =
        database.queryForObject(
            "SELECT count(*) FROM gateway_request r LEFT JOIN gateway_api_key k ON"
                + " k.id=r.api_key_id LEFT JOIN gateway_user g ON g.id=r.user_id"
                + where,
            Long.class,
            args.toArray());
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(pageSize);
    pageArgs.add((long) (page - 1) * pageSize);
    List<Map<String, Object>> rows =
        database.query(
            SELECT + where + " ORDER BY r.started_at DESC, r.id DESC LIMIT ? OFFSET ?",
            this::row,
            pageArgs.toArray());
    Map<String, Object> summary =
        database.queryForObject(
            """
SELECT count(*) total, count(*) FILTER (WHERE r.state='completed') successes,
       COALESCE(sum(u.input_tokens),0) input_tokens, COALESCE(sum(u.output_tokens),0) output_tokens,
       COALESCE(sum(u.cache_read_tokens),0) cache_read_tokens,
       COALESCE(sum(u.cache_write_tokens),0) cache_write_tokens,
       COALESCE(sum(u.cost_estimate),0) cost_estimate,
       COALESCE(sum(u.actual_cost),0) actual_cost,COALESCE(sum(u.billable_cost),0) billable_cost,count(u.billable_cost) billable_requests,
       count(r.latency_ms) latency_samples,GREATEST(COALESCE(extract(epoch FROM max(r.started_at)-min(r.started_at))/60,1),1) minutes,
       COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY r.latency_ms),0) p95
FROM gateway_request r LEFT JOIN gateway_usage u ON u.request_id=r.id
LEFT JOIN gateway_api_key k ON k.id=r.api_key_id LEFT JOIN gateway_user g ON g.id=r.user_id
"""
                + where,
            (rs, index) -> summary(rs),
            args.toArray());
    return Map.of("logs", rows, "total", total == null ? 0 : total, "summary", summary);
  }

  @GetMapping("/admin/logs/{id}")
  Map<String, Object> byId(@RequestHeader HttpHeaders headers, @PathVariable UUID id) {
    var actor = auth.requireSession(headers);
    List<Map<String, Object>> rows = database.query(SELECT + " WHERE r.id=?", this::row, id);
    if (rows.isEmpty())
      throw new GatewayException(
          HttpStatus.NOT_FOUND, "not_found_error", "log_not_found", "Log not found");
    if (!"admin".equals(actor.get("role"))
        && !actor.get("id").equals(rows.getFirst().get("userId")))
      throw new GatewayException(
          HttpStatus.FORBIDDEN,
          "forbidden_error",
          "log_permission_denied",
          "Cannot read another user's log");
    return rows.getFirst();
  }

  private Map<String, Object> row(ResultSet rs, int index) throws SQLException {
    Map<String, Object> row = new HashMap<>();
    row.put("id", rs.getObject("id").toString());
    row.put("requestId", rs.getObject("id").toString());
    row.put("timestamp", rs.getTimestamp("started_at").toInstant().toString());
    row.put("userId", rs.getObject("user_id") == null ? "" : rs.getObject("user_id").toString());
    row.put("username", rs.getString("username") == null ? "" : rs.getString("username"));
    row.put(
        "apiKeyId",
        rs.getObject("api_key_id") == null ? null : rs.getObject("api_key_id").toString());
    row.put("apiKeyName", rs.getString("api_key_name"));
    row.put("apiKeyGroup", rs.getString("api_key_group"));
    row.put("model", rs.getString("requested_model"));
    row.put("resolvedModel", rs.getString("upstream_model"));
    row.put("provider", rs.getString("provider_id"));
    row.put(
        "protocol",
        "openai_compat".equals(rs.getString("provider_protocol")) ? "openai-compat" : "anthropic");
    row.put("trafficClass", rs.getString("traffic_class"));
    row.put("clientIp", rs.getString("client_ip"));
    row.put("retryCount", rs.getInt("retry_count"));
    row.put("credentialId", rs.getString("credential_id"));
    row.put(
        "clientProtocol",
        "openai".equals(rs.getString("client_protocol"))
            ? "openai-chat-completions"
            : "anthropic-messages");
    row.put("stream", rs.getBoolean("stream") ? "stream" : "non-stream");
    row.put("toolUseRequested", rs.getBoolean("tool_requested"));
    row.put("status", "completed".equals(rs.getString("state")) ? "success" : "error");
    row.put("statusCode", rs.getInt("http_status"));
    row.put("inputTokens", rs.getLong("input_tokens"));
    row.put("outputTokens", rs.getLong("output_tokens"));
    row.put("cacheReadTokens", rs.getLong("cache_read_tokens"));
    row.put("cacheWriteTokens", rs.getLong("cache_write_tokens"));
    row.put("latencyMs", rs.getLong("latency_ms"));
    row.put("terminalReason", rs.getString("safe_error_code"));
    row.put(
        "errorMessage",
        "completed".equals(rs.getString("state")) ? null : rs.getString("safe_error_code"));
    row.put("costEstimate", rs.getBigDecimal("cost_estimate"));
    row.put("actualCost", rs.getBigDecimal("actual_cost"));
    row.put("billableCost", rs.getBigDecimal("billable_cost"));
    row.put(
        "reconciliationStatus",
        rs.getBigDecimal("billable_cost") == null ? "estimate_only" : "billable");
    try {
      String evidence = rs.getString("pricing_evidence");
      row.put("pricingEvidence", evidence == null ? null : json.readTree(evidence));
      String snapshot = rs.getString("pricing_snapshot");
      if (snapshot != null) {
        var card = json.readTree(snapshot);
        var rates = json.createObjectNode();
        for (String field :
            List.of(
                "inputPerMillion",
                "outputPerMillion",
                "cacheReadPerMillion",
                "cacheWritePerMillion")) rates.set(field, card.path(field));
        row.put("modelPricing", rates);
      }
    } catch (Exception e) {
      throw new SQLException("Invalid pricing evidence", e);
    }
    row.put("usageEstimated", rs.getBoolean("estimated"));
    row.put(
        "billingMode",
        rs.getBoolean("estimated") ? "conservative_estimate" : "provider_reported_usage");
    row.put(
        "totalTokens",
        rs.getLong("input_tokens")
            + rs.getLong("output_tokens")
            + rs.getLong("cache_read_tokens")
            + rs.getLong("cache_write_tokens"));
    return row;
  }

  private Map<String, Object> summary(ResultSet rs) throws SQLException {
    long input = rs.getLong("input_tokens"), output = rs.getLong("output_tokens");
    long read = rs.getLong("cache_read_tokens"), write = rs.getLong("cache_write_tokens");
    Map<String, Object> result = new HashMap<>();
    result.put("totalRequests", rs.getLong("total"));
    result.put("successRequests", rs.getLong("successes"));
    result.put("totalInputTokens", input);
    result.put("totalOutputTokens", output);
    result.put("totalCacheReadTokens", read);
    result.put("totalCacheWriteTokens", write);
    result.put("totalTokens", input + output + read + write);
    result.put("totalCostEstimate", rs.getBigDecimal("cost_estimate"));
    result.put("totalActualCost", rs.getBigDecimal("actual_cost"));
    result.put("totalBillableCost", rs.getBigDecimal("billable_cost"));
    result.put("billableRequests", rs.getLong("billable_requests"));
    result.put("estimateOnlyRequests", rs.getLong("total") - rs.getLong("billable_requests"));
    result.put("latencyP95Ms", rs.getDouble("p95"));
    result.put("latencySampleCount", rs.getLong("latency_samples"));
    result.put("rpm", rs.getLong("total") / rs.getDouble("minutes"));
    result.put("tpm", (input + output + read + write) / rs.getDouble("minutes"));
    return result;
  }

  private static void eq(StringBuilder where, List<Object> args, String field, Object value) {
    if (value == null || value instanceof String text && text.isBlank()) return;
    where.append(" AND ").append(field).append("=?");
    args.add(value);
  }

  private static UUID uuid(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return UUID.fromString(value);
    } catch (Exception e) {
      throw bad("Invalid UUID filter");
    }
  }

  private static void epoch(StringBuilder where, List<Object> args, String clause, String value) {
    if (value == null || value.isBlank()) return;
    try {
      where.append(" AND ").append(clause);
      args.add(java.sql.Timestamp.from(Instant.ofEpochMilli(Long.parseLong(value))));
    } catch (Exception e) {
      throw bad("Invalid date filter");
    }
  }

  private static int boundedInt(String raw, int fallback, int min, int max) {
    if (raw == null) return fallback;
    try {
      int value = Integer.parseInt(raw);
      if (value >= min && value <= max) return value;
    } catch (NumberFormatException ignored) {
    }
    throw bad("Invalid pagination");
  }

  private static GatewayException bad(String detail) {
    return new GatewayException(
        HttpStatus.BAD_REQUEST, "invalid_request_error", "invalid_request", detail);
  }
}
