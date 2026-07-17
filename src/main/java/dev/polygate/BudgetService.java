package dev.polygate;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.*;
import java.sql.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "polygate.database.enabled", havingValue = "true")
class BudgetService {
  private final JdbcTemplate db;
  private final PricingService pricing;

  BudgetService(JdbcTemplate db, PricingService pricing) {
    this.db = db;
    this.pricing = pricing;
  }

  void reserve(
      UUID request,
      ClientAuthService.Principal principal,
      JsonNode payload,
      JsonNode card,
      long maximumInput,
      long maximumOutput) {
    TenantScope scope = principal.scope();
    ensure(scope);
    Map<String, Object> account = lock(scope);
    long input = maximumInput, output = maximumOutput;
    BigDecimal cost = pricing.cost(card, input, output, 0, 0);
    Long limit =
        account.get("limit_microunits") == null
            ? null
            : ((Number) account.get("limit_microunits")).longValue();
    if (limit != null && cost == null)
      throw new GatewayException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "server_error",
          "pricing_required",
          "A price card is required for this project's hard budget");
    long amount = cost == null ? 0 : micros(cost, RoundingMode.CEILING),
        reserved = ((Number) account.get("reserved_microunits")).longValue(),
        settled = ((Number) account.get("settled_microunits")).longValue();
    if (limit != null
        && BigInteger.valueOf(reserved)
                .add(BigInteger.valueOf(settled))
                .add(BigInteger.valueOf(amount))
                .compareTo(BigInteger.valueOf(limit))
            > 0)
      throw new GatewayException(
          HttpStatus.TOO_MANY_REQUESTS,
          "rate_limit_error",
          "budget_exceeded",
          "Project hard budget would be exceeded");
    db.update(
        "UPDATE gateway_budget_account SET"
            + " reserved_microunits=reserved_microunits+?,version=version+1,updated_at=now() WHERE"
            + " organization_id=? AND project_id=? AND environment_id=?",
        amount,
        scope.organizationId(),
        scope.projectId(),
        scope.environmentId());
    db.update(
        "INSERT INTO"
            + " gateway_budget_reservation(request_id,organization_id,project_id,environment_id,amount_microunits)"
            + " VALUES (?,?,?,?,?)",
        request,
        scope.organizationId(),
        scope.projectId(),
        scope.environmentId(),
        amount);
    event(
        scope,
        request,
        "reservation_created",
        amount,
        0,
        "price_card_reservation",
        null,
        null,
        principal.userId(),
        0,
        0,
        0,
        0);
  }

  void lock(UUID request) {
    var rows =
        db.queryForList(
            "SELECT organization_id,project_id,environment_id FROM gateway_budget_reservation WHERE"
                + " request_id=?",
            request);
    if (!rows.isEmpty()) lock(scope(rows.getFirst()));
  }

  void settle(UUID request) {
    var rows =
        db.queryForList(
            "SELECT"
                + " b.*,u.cost_estimate,u.estimated,u.input_tokens,u.output_tokens,u.cache_read_tokens,u.cache_write_tokens,r.user_id"
                + " FROM gateway_budget_reservation b LEFT JOIN gateway_usage u ON"
                + " u.request_id=b.request_id LEFT JOIN gateway_request r ON r.id=b.request_id"
                + " WHERE b.request_id=?",
            request);
    if (rows.isEmpty()) return;
    var row = rows.getFirst();
    TenantScope scope = scope(row);
    long held = ((Number) row.get("amount_microunits")).longValue();
    long actual =
        row.get("cost_estimate") == null
            ? held
            : micros((BigDecimal) row.get("cost_estimate"), RoundingMode.HALF_UP);
    db.update(
        "UPDATE gateway_budget_account SET"
            + " reserved_microunits=reserved_microunits-?,settled_microunits=settled_microunits+?,version=version+1,updated_at=now()"
            + " WHERE organization_id=? AND project_id=? AND environment_id=?",
        held,
        actual,
        scope.organizationId(),
        scope.projectId(),
        scope.environmentId());
    boolean estimated = Boolean.TRUE.equals(row.get("estimated"));
    event(
        scope,
        request,
        "settled",
        -held,
        actual,
        estimated ? "conservative_usage_estimate" : "provider_usage_with_price_card",
        estimated ? "conservative_estimate" : "provider_reported_usage",
        null,
        (UUID) row.get("user_id"),
        number(row, "input_tokens"),
        number(row, "output_tokens"),
        number(row, "cache_read_tokens"),
        number(row, "cache_write_tokens"));
    db.update("DELETE FROM gateway_budget_reservation WHERE request_id=?", request);
  }

  Map<String, Object> view(TenantScope scope) {
    var accounts =
        db.queryForList(
            "SELECT * FROM gateway_budget_account WHERE organization_id=? AND project_id=? AND"
                + " environment_id=?",
            scope.organizationId(),
            scope.projectId(),
            scope.environmentId());
    Map<String, Object> account = new HashMap<>();
    account.put("organizationId", scope.organizationId());
    account.put("projectId", scope.projectId());
    account.put("environmentId", scope.environmentId());
    account.put("currency", "USD");
    long reserved = 0, settled = 0, version = 0, updated = 0;
    Long limit = null;
    if (!accounts.isEmpty()) {
      var row = accounts.getFirst();
      reserved = number(row, "reserved_microunits");
      settled = number(row, "settled_microunits");
      version = number(row, "version");
      updated = ((Timestamp) row.get("updated_at")).toInstant().toEpochMilli();
      if (row.get("limit_microunits") != null) limit = number(row, "limit_microunits");
    }
    account.put("limitMicrounits", limit);
    account.put("reservedMicrounits", reserved);
    account.put("settledMicrounits", settled);
    account.put(
        "availableMicrounits", limit == null ? null : Math.max(0, limit - reserved - settled));
    account.put(
        "utilizationBasisPoints",
        limit == null
            ? null
            : limit == 0
                ? settled + reserved > 0 ? 10000 : 0
                : BigDecimal.valueOf(settled)
                    .add(BigDecimal.valueOf(reserved))
                    .multiply(BigDecimal.valueOf(10000))
                    .divide(BigDecimal.valueOf(limit), 0, RoundingMode.HALF_UP));
    account.put("version", version);
    account.put("updatedAtMs", updated);
    var events =
        db.query(
            "SELECT * FROM gateway_budget_event WHERE organization_id=? AND project_id=? AND"
                + " environment_id=? ORDER BY created_at DESC LIMIT 100",
            (rs, i) -> eventRow(rs),
            scope.organizationId(),
            scope.projectId(),
            scope.environmentId());
    return Map.of("account", account, "recentEvents", events);
  }

  @Transactional
  Map<String, Object> update(JsonNode input, UUID actor) {
    TenantScope scope = TenantScope.from(input);
    ensure(scope);
    lock(scope);
    Long limit = null;
    if (!input.path("unlimited").asBoolean(false)) {
      JsonNode amount = input.path("limitMicrounits");
      if (!amount.isIntegralNumber()
          || !amount.canConvertToLong()
          || amount.asLong() < 0
          || amount.asLong() > 9_000_000_000_000_000L) throw bad("Invalid hard budget");
      limit = amount.asLong();
    }
    db.update(
        "UPDATE gateway_budget_account SET limit_microunits=?,version=version+1,updated_at=now()"
            + " WHERE organization_id=? AND project_id=? AND environment_id=?",
        limit,
        scope.organizationId(),
        scope.projectId(),
        scope.environmentId());
    event(
        scope,
        null,
        "adjustment",
        0,
        0,
        "administrator_budget_policy",
        null,
        "Budget limit updated",
        actor,
        0,
        0,
        0,
        0);
    return view(scope);
  }

  @Transactional
  Map<String, Object> adjust(JsonNode input, UUID actor) {
    TenantScope scope = TenantScope.from(input);
    String reason = input.path("reason").asText("").trim(),
        evidence = input.path("evidenceReference").asText("").trim();
    JsonNode delta = input.path("deltaMicrounits");
    if (reason.isBlank()
        || reason.length() > 2000
        || evidence.isBlank()
        || evidence.length() > 2000
        || !delta.isIntegralNumber()
        || !delta.canConvertToLong()
        || Math.abs(delta.asLong()) > 9_000_000_000_000_000L)
      throw bad("An adjustment requires a valid amount, reason and evidence reference");
    ensure(scope);
    var account = lock(scope);
    long current = number(account, "settled_microunits");
    if (BigInteger.valueOf(current).add(BigInteger.valueOf(delta.asLong())).signum() < 0)
      throw bad("Adjustment would make settled usage negative");
    db.update(
        "UPDATE gateway_budget_account SET"
            + " settled_microunits=settled_microunits+?,version=version+1,updated_at=now() WHERE"
            + " organization_id=? AND project_id=? AND environment_id=?",
        delta.asLong(),
        scope.organizationId(),
        scope.projectId(),
        scope.environmentId());
    event(scope, null, "adjustment", 0, delta.asLong(), evidence, null, reason, actor, 0, 0, 0, 0);
    return view(scope);
  }

  private void ensure(TenantScope scope) {
    db.update(
        "INSERT INTO gateway_budget_account(organization_id,project_id,environment_id) VALUES"
            + " (?,?,?) ON CONFLICT DO NOTHING",
        scope.organizationId(),
        scope.projectId(),
        scope.environmentId());
  }

  private Map<String, Object> lock(TenantScope scope) {
    return db.queryForMap(
        "SELECT * FROM gateway_budget_account WHERE organization_id=? AND project_id=? AND"
            + " environment_id=? FOR UPDATE",
        scope.organizationId(),
        scope.projectId(),
        scope.environmentId());
  }

  private void event(
      TenantScope scope,
      UUID request,
      String type,
      long reserved,
      long settled,
      String evidence,
      String mode,
      String reason,
      UUID actor,
      long input,
      long output,
      long read,
      long write) {
    db.update(
        "INSERT INTO"
            + " gateway_budget_event(id,organization_id,project_id,environment_id,request_id,event_type,reserved_delta_microunits,settled_delta_microunits,evidence_source,billing_mode,reason,actor_id,input_tokens,output_tokens,cache_read_tokens,cache_write_tokens)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        scope.organizationId(),
        scope.projectId(),
        scope.environmentId(),
        request,
        type,
        reserved,
        settled,
        evidence,
        mode,
        reason,
        actor,
        input,
        output,
        read,
        write);
  }

  private Map<String, Object> eventRow(ResultSet rs) throws SQLException {
    Map<String, Object> row = new HashMap<>();
    row.put("eventId", rs.getObject("id").toString());
    row.put("organizationId", rs.getString("organization_id"));
    row.put("projectId", rs.getString("project_id"));
    row.put("environmentId", rs.getString("environment_id"));
    row.put("currency", "USD");
    String request =
        rs.getObject("request_id") == null ? null : rs.getObject("request_id").toString();
    row.put("reservationId", request);
    row.put("requestLedgerId", request);
    row.put("attemptId", request);
    row.put("eventType", rs.getString("event_type"));
    row.put("reservedDeltaMicrounits", rs.getLong("reserved_delta_microunits"));
    row.put("settledDeltaMicrounits", rs.getLong("settled_delta_microunits"));
    row.put("evidenceSource", rs.getString("evidence_source"));
    row.put("billingMode", rs.getString("billing_mode"));
    row.put("reason", rs.getString("reason"));
    row.put(
        "actorId", rs.getObject("actor_id") == null ? null : rs.getObject("actor_id").toString());
    row.put("inputTokens", rs.getLong("input_tokens"));
    row.put("outputTokens", rs.getLong("output_tokens"));
    row.put("cacheReadTokens", rs.getLong("cache_read_tokens"));
    row.put("cacheWriteTokens", rs.getLong("cache_write_tokens"));
    row.put("createdAtMs", rs.getTimestamp("created_at").toInstant().toEpochMilli());
    return row;
  }

  private static long number(Map<String, Object> row, String key) {
    return row.get(key) == null ? 0 : ((Number) row.get(key)).longValue();
  }

  private static TenantScope scope(Map<String, Object> row) {
    return new TenantScope(
        (String) row.get("organization_id"),
        (String) row.get("project_id"),
        (String) row.get("environment_id"));
  }

  private static long micros(BigDecimal value, RoundingMode mode) {
    return value.multiply(BigDecimal.valueOf(1_000_000)).setScale(0, mode).longValueExact();
  }

  private static GatewayException bad(String message) {
    return new GatewayException(
        HttpStatus.BAD_REQUEST, "invalid_request_error", "invalid_request", message);
  }
}
