package dev.polygate;

import java.lang.management.ManagementFactory;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "polygate.database.enabled", havingValue = "true")
class AdminDashboardController {
  private final JdbcTemplate db;
  private final AdminAuthService auth;

  AdminDashboardController(JdbcTemplate db, AdminAuthService auth) {
    this.db = db;
    this.auth = auth;
  }

  @GetMapping("/admin/dashboard")
  Map<String, Object> dashboard(
      @RequestHeader HttpHeaders headers,
      @RequestParam(defaultValue = "1d") String range,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to) {
    var actor = auth.requireSession(headers);
    Instant end = Instant.now(), start;
    try {
      start =
          switch (range) {
            case "1d" -> end.minusSeconds(86400);
            case "3d" -> end.minusSeconds(259200);
            case "7d" -> end.minusSeconds(604800);
            case "custom" -> {
              end = parseTime(to);
              yield parseTime(from);
            }
            default -> throw bad("Invalid dashboard range");
          };
      if (!start.isBefore(end) || end.toEpochMilli() - start.toEpochMilli() > 90L * 86400000)
        throw bad("Dashboard range must be between zero and 90 days");
    } catch (GatewayException e) {
      throw e;
    } catch (Exception e) {
      throw bad("Invalid dashboard date");
    }
    List<Object> args = new ArrayList<>(List.of(Timestamp.from(start), Timestamp.from(end)));
    String where = " WHERE r.started_at>=? AND r.started_at<=?";
    if (!"admin".equals(actor.get("role"))) {
      where += " AND r.user_id=?";
      args.add(UUID.fromString((String) actor.get("id")));
    }
    Object[] values = args.toArray();
    String joined = " FROM gateway_request r LEFT JOIN gateway_usage u ON u.request_id=r.id";
    Map<String, Object> stats =
        db.queryForObject(
            "SELECT count(*) total,count(*) FILTER(WHERE r.state='completed')"
                + " success,COALESCE(avg(r.latency_ms),0) latency,COALESCE(sum(u.input_tokens),0)"
                + " input,COALESCE(sum(u.output_tokens),0)"
                + " output,COALESCE(sum(u.cache_read_tokens),0)"
                + " cache_read,COALESCE(sum(u.cache_write_tokens),0)"
                + " cache_write,COALESCE(sum(u.cost_estimate),0) cost"
                + joined
                + where,
            (rs, i) ->
                Map.<String, Object>of(
                    "total",
                    rs.getLong("total"),
                    "success",
                    rs.getLong("success"),
                    "latency",
                    rs.getDouble("latency"),
                    "input",
                    rs.getLong("input"),
                    "output",
                    rs.getLong("output"),
                    "cacheRead",
                    rs.getLong("cache_read"),
                    "cacheWrite",
                    rs.getLong("cache_write"),
                    "cost",
                    rs.getBigDecimal("cost")),
            values);
    long total = ((Number) stats.get("total")).longValue(),
        success = ((Number) stats.get("success")).longValue();
    int bucketMs = end.toEpochMilli() - start.toEpochMilli() > 7L * 86400000 ? 86400000 : 3600000;
    String bucket = bucketMs == 86400000 ? "day" : "hour";
    var points =
        db.query(
            "SELECT date_trunc('"
                + bucket
                + "',r.started_at) bucket,count(*) requests,count(*) FILTER(WHERE r.state='failed')"
                + " errors,COALESCE(sum(u.input_tokens),0) input,COALESCE(sum(u.output_tokens),0)"
                + " output,COALESCE(sum(u.cache_read_tokens),0)"
                + " cache_read,COALESCE(sum(u.cache_write_tokens),0) cache_write"
                + joined
                + where
                + " GROUP BY bucket ORDER BY bucket",
            (rs, i) ->
                Map.<String, Object>of(
                    "timestamp",
                    rs.getTimestamp("bucket").toInstant().toString(),
                    "requests",
                    rs.getLong("requests"),
                    "errors",
                    rs.getLong("errors"),
                    "inputTokens",
                    rs.getLong("input"),
                    "outputTokens",
                    rs.getLong("output"),
                    "cacheReadTokens",
                    rs.getLong("cache_read"),
                    "cacheWriteTokens",
                    rs.getLong("cache_write")),
            values);
    var usage =
        db.query(
            "SELECT r.requested_model,r.provider_id,count(*)"
                + " requests,COALESCE(sum(u.input_tokens+u.output_tokens+u.cache_read_tokens+u.cache_write_tokens),0)"
                + " tokens,COALESCE(sum(u.cost_estimate),0) cost"
                + joined
                + where
                + " GROUP BY r.requested_model,r.provider_id ORDER BY requests DESC",
            (rs, i) ->
                Map.<String, Object>of(
                    "model",
                    rs.getString("requested_model"),
                    "provider",
                    Objects.requireNonNullElse(rs.getString("provider_id"), "unresolved"),
                    "requests",
                    rs.getLong("requests"),
                    "tokens",
                    rs.getLong("tokens"),
                    "cost",
                    rs.getBigDecimal("cost")),
            values);
    Map<String, Object> summary = new HashMap<>();
    summary.put("totalRequests", total);
    summary.put("successRequests", success);
    summary.put("totalInputTokens", stats.get("input"));
    summary.put("totalOutputTokens", stats.get("output"));
    summary.put("totalCacheReadTokens", stats.get("cacheRead"));
    summary.put("totalCacheWriteTokens", stats.get("cacheWrite"));
    long tokens =
        List.of("input", "output", "cacheRead", "cacheWrite").stream()
            .mapToLong(key -> ((Number) stats.get(key)).longValue())
            .sum();
    summary.put("totalTokens", tokens);
    summary.put("totalCostEstimate", stats.get("cost"));
    var costs =
        db.queryForMap(
            "SELECT COALESCE(sum(u.actual_cost),0) actual,COALESCE(sum(u.billable_cost),0)"
                + " billable,count(u.billable_cost) proven,count(*) FILTER(WHERE u.estimated)"
                + " estimated"
                + joined
                + where,
            values);
    summary.put("totalActualCost", costs.get("actual"));
    summary.put("totalBillableCost", costs.get("billable"));
    summary.put("billableRequests", costs.get("proven"));
    summary.put("estimateOnlyRequests", total - ((Number) costs.get("proven")).longValue());
    double minutes = Math.max(1, (end.toEpochMilli() - start.toEpochMilli()) / 60000.0);
    summary.put("rpm", total / minutes);
    summary.put("tpm", tokens / minutes);
    Map<String, Object> result = new HashMap<>();
    result.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
    result.put("totalRequests", total);
    result.put("successRate", total == 0 ? 0 : 100.0 * success / total);
    result.put("activeProviders", count("gateway_provider", "enabled=true"));
    result.put("totalProviders", count("gateway_provider", "true"));
    result.put("activeUsers", count("gateway_user", "status='active'"));
    result.put("totalModels", count("gateway_model", "enabled=true"));
    result.put("apiKeysTotal", count("gateway_api_key", "true"));
    result.put(
        "apiKeysActive",
        count("gateway_api_key", "enabled=true AND (expires_at IS NULL OR expires_at>now())"));
    result.put("avgLatencyMs", stats.get("latency"));
    result.put(
        "trendRange",
        Map.of(
            "range", range, "from", start.toString(), "to", end.toString(), "bucketMs", bucketMs));
    result.put(
        "requestTimeSeries",
        points.stream()
            .map(p -> Map.of("timestamp", p.get("timestamp"), "value", p.get("requests")))
            .toList());
    result.put(
        "errorTimeSeries",
        points.stream()
            .map(p -> Map.of("timestamp", p.get("timestamp"), "value", p.get("errors")))
            .toList());
    result.put(
        "tokenTimeSeries",
        points.stream()
            .map(
                p -> {
                  var q = new HashMap<>(p);
                  long input = ((Number) p.get("inputTokens")).longValue(),
                      read = ((Number) p.get("cacheReadTokens")).longValue();
                  q.put("cacheHitRate", input + read == 0 ? 0 : 100.0 * read / (input + read));
                  return q;
                })
            .toList());
    result.put("topModels", usage.stream().limit(5).toList());
    result.put("modelUsage", usage);
    result.put("rangeSummary", summary);
    result.put("rangeDataSource", total == 0 ? "empty" : "relational-ledger");
    result.put("rangeDataEstimated", ((Number) costs.get("estimated")).longValue() > 0);
    result.put("rangeDataAtRetentionLimit", false);
    result.put(
        "providerHealth",
        db.query(
            "SELECT p.id,p.display_name,count(r.id) requests,count(r.id) FILTER(WHERE"
                + " r.state='completed') successes,COALESCE(avg(r.latency_ms),0) latency FROM"
                + " gateway_provider p LEFT JOIN gateway_request r ON r.provider_id=p.id AND"
                + " r.started_at>now()-interval '24 hours' GROUP BY p.id,p.display_name",
            (rs, i) -> {
              long n = rs.getLong("requests"), s = rs.getLong("successes");
              return Map.<String, Object>of(
                  "providerId",
                  rs.getString("id"),
                  "displayName",
                  rs.getString("display_name"),
                  "status",
                  n == 0 ? "healthy" : s == 0 ? "down" : s < n ? "degraded" : "healthy",
                  "requestsTotal",
                  n,
                  "successRate",
                  n == 0 ? 100 : 100.0 * s / n,
                  "avgLatencyMs",
                  rs.getDouble("latency"));
            }));
    result.put(
        "recentActivity",
        db.query(
            "SELECT id,started_at,requested_model,state FROM gateway_request r"
                + where
                + " ORDER BY started_at DESC LIMIT 10",
            (rs, i) ->
                Map.of(
                    "id",
                    rs.getObject("id").toString(),
                    "timestamp",
                    rs.getTimestamp("started_at").toInstant().toString(),
                    "type",
                    "completed".equals(rs.getString("state")) ? "request" : "error",
                    "message",
                    rs.getString("requested_model") + " " + rs.getString("state"),
                    "severity",
                    "completed".equals(rs.getString("state")) ? "info" : "error"),
            values));
    result.put(
        "onboardingMilestones",
        Map.of(
            "hasRequestEver",
            count("gateway_request", "true") > 0,
            "hasSuccessfulRequestEver",
            count("gateway_request", "state='completed'") > 0,
            "hasDefaultProjectPolicy",
            db.queryForObject(
                    "SELECT count(*) FROM gateway_setting WHERE name='governance' AND"
                        + " jsonb_extract_path(document,'policies','org_local/prj_default/env_default')"
                        + " IS NOT NULL",
                    Long.class)
                > 0));
    if (!"admin".equals(actor.get("role"))) {
      UUID own = UUID.fromString((String) actor.get("id"));
      result.put("activeUsers", 1);
      result.put(
          "apiKeysTotal",
          db.queryForObject(
              "SELECT count(*) FROM gateway_api_key WHERE user_id=?", Long.class, own));
      result.put(
          "apiKeysActive",
          db.queryForObject(
              "SELECT count(*) FROM gateway_api_key WHERE user_id=? AND enabled AND"
                  + " rotation_state='active' AND (expires_at IS NULL OR expires_at>now())",
              Long.class,
              own));
      result.put("providerHealth", List.of());
      result.put("activeProviders", usage.stream().map(v -> v.get("provider")).distinct().count());
      result.put("totalProviders", usage.stream().map(v -> v.get("provider")).distinct().count());
      result.put("totalModels", usage.size());
      result.put(
          "onboardingMilestones",
          Map.of(
              "hasRequestEver",
              total > 0,
              "hasSuccessfulRequestEver",
              success > 0,
              "hasDefaultProjectPolicy",
              false));
    }
    return result;
  }

  @GetMapping("/admin/latency")
  @org.springframework.transaction.annotation.Transactional(readOnly = true)
  Map<String, Object> latency(@RequestHeader HttpHeaders headers) {
    auth.requireAdmin(headers);
    return db.queryForObject(
        "SELECT COALESCE(percentile_cont(.5) WITHIN GROUP(ORDER BY latency_ms),0)"
            + " p50,COALESCE(percentile_cont(.9) WITHIN GROUP(ORDER BY latency_ms),0)"
            + " p90,COALESCE(percentile_cont(.95) WITHIN GROUP(ORDER BY latency_ms),0)"
            + " p95,COALESCE(percentile_cont(.99) WITHIN GROUP(ORDER BY latency_ms),0)"
            + " p99,COALESCE(avg(latency_ms),0) avg,COALESCE(max(latency_ms),0)"
            + " max,count(latency_ms) n FROM gateway_request",
        (rs, i) ->
            Map.of(
                "p50",
                rs.getDouble("p50"),
                "p90",
                rs.getDouble("p90"),
                "p95",
                rs.getDouble("p95"),
                "p99",
                rs.getDouble("p99"),
                "avg",
                rs.getDouble("avg"),
                "max",
                rs.getDouble("max"),
                "sampleCount",
                rs.getLong("n"),
                "percentilesEstimated",
                false,
                "byModel",
                latencyGroups("requested_model"),
                "byProvider",
                latencyGroups("provider_id")));
  }

  private Map<String, Object> latencyGroups(String column) {
    Map<String, Object> result = new HashMap<>();
    db.query(
        "SELECT "
            + column
            + " id,percentile_cont(.5) WITHIN GROUP(ORDER BY latency_ms) p50,percentile_cont(.95)"
            + " WITHIN GROUP(ORDER BY latency_ms) p95,avg(latency_ms) avg FROM gateway_request"
            + " WHERE latency_ms IS NOT NULL AND "
            + column
            + " IS NOT NULL GROUP BY "
            + column,
        (org.springframework.jdbc.core.RowCallbackHandler)
            rs ->
                result.put(
                    rs.getString("id"),
                    Map.of(
                        "p50",
                        rs.getDouble("p50"),
                        "p95",
                        rs.getDouble("p95"),
                        "avg",
                        rs.getDouble("avg"))));
    return result;
  }

  private long count(String table, String where) {
    return db.queryForObject("SELECT count(*) FROM " + table + " WHERE " + where, Long.class);
  }

  private static Instant parseTime(String value) {
    try {
      return Instant.ofEpochMilli(Long.parseLong(value));
    } catch (NumberFormatException e) {
      return Instant.parse(value);
    }
  }

  private static GatewayException bad(String detail) {
    return new GatewayException(
        HttpStatus.BAD_REQUEST, "invalid_request_error", "invalid_request", detail);
  }
}
