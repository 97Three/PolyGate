package dev.polygate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "polygate.database.enabled", havingValue = "true")
class QuotaService {
  private final JdbcTemplate db;
  private final PricingService pricing;
  private final ObjectMapper json;

  QuotaService(JdbcTemplate db, PricingService pricing, ObjectMapper json) {
    this.db = db;
    this.pricing = pricing;
    this.json = json;
  }

  void reserve(
      UUID requestId,
      ClientAuthService.Principal principal,
      RoutingService.Route route,
      JsonNode request,
      JsonNode priceCard,
      long maximumInput,
      long maximumOutput) {
    if (principal.userId() == null) return;
    long maxInput = maximumInput;
    long maxOutput = maximumOutput;
    reserveSpend(requestId, principal, pricing.cost(priceCard, maxInput, maxOutput, 0, 0));
    var quotas =
        db.query(
            "SELECT id,quota_type,quota_limit,period FROM gateway_quota WHERE user_id=? ORDER BY id"
                + " FOR UPDATE",
            (rs, i) ->
                new Quota(
                    (UUID) rs.getObject("id"),
                    rs.getString("quota_type"),
                    rs.getBigDecimal("quota_limit"),
                    rs.getString("period")),
            principal.userId());
    for (var quota : quotas) {
      BigDecimal amount =
          switch (quota.type()) {
            case "requests" -> BigDecimal.ONE;
            case "tokens" -> BigDecimal.valueOf(maxInput + maxOutput);
            case "cost" -> {
              BigDecimal cost = pricing.cost(priceCard, maxInput, maxOutput, 0, 0);
              if (cost == null)
                throw new GatewayException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "server_error",
                    "pricing_required",
                    "A configured price card is required to enforce the cost quota");
              yield cost;
            }
            default -> throw new IllegalStateException("Unknown stored quota type");
          };
      BigDecimal used = used(principal.userId(), quota.type(), quota.period());
      BigDecimal pending =
          "requests".equals(quota.type())
              ? BigDecimal.ZERO
              : db.queryForObject(
                  "SELECT COALESCE(sum(amount),0) FROM gateway_quota_reservation WHERE quota_id=?"
                      + " AND expires_at>now()",
                  BigDecimal.class,
                  quota.id());
      if (used.add(pending).add(amount).compareTo(quota.limit()) > 0)
        throw new GatewayException(
            HttpStatus.TOO_MANY_REQUESTS,
            "rate_limit_error",
            "quota_exceeded",
            "The " + quota.period() + " " + quota.type() + " quota would be exceeded");
      db.update(
          "INSERT INTO gateway_quota_reservation(request_id,quota_id,amount) VALUES (?,?,?)",
          requestId,
          quota.id(),
          amount);
    }
  }

  void lock(UUID requestId) {
    var actors =
        db.queryForList("SELECT quota_subject,team_id FROM gateway_request WHERE id=?", requestId);
    if (!actors.isEmpty()) {
      Object subject = actors.getFirst().get("quota_subject"),
          team = actors.getFirst().get("team_id");
      if (subject != null)
        db.queryForObject(
            "SELECT pg_advisory_xact_lock(hashtext(?))", Object.class, "key:" + subject);
      if (team != null)
        db.query(
            "SELECT id FROM gateway_team WHERE id=? FOR UPDATE", (rs, i) -> rs.getObject(1), team);
    }
    db.query(
        "SELECT q.id FROM gateway_quota q JOIN gateway_quota_reservation r ON r.quota_id=q.id WHERE"
            + " r.request_id=? ORDER BY q.id FOR UPDATE OF q",
        (rs, i) -> rs.getObject(1),
        requestId);
  }

  void release(UUID requestId) {
    db.update("DELETE FROM gateway_quota_reservation WHERE request_id=?", requestId);
    db.update("DELETE FROM gateway_spend_reservation WHERE request_id=?", requestId);
  }

  private void reserveSpend(
      UUID requestId, ClientAuthService.Principal principal, BigDecimal amount) {
    if (principal.apiKeyId() == null) return;
    var keys =
        db.queryForList(
            "SELECT enabled,rotation_state,policy::text policy,team_id,COALESCE(quota_subject,id)"
                + " subject FROM gateway_api_key WHERE id=? FOR UPDATE",
            principal.apiKeyId());
    if (keys.isEmpty()
        || !Boolean.TRUE.equals(keys.getFirst().get("enabled"))
        || !"active".equals(keys.getFirst().get("rotation_state")))
      throw new GatewayException(
          HttpStatus.UNAUTHORIZED,
          "authentication_error",
          "authentication_failed",
          "Client credential is no longer active");
    var key = keys.getFirst();
    UUID subject = (UUID) key.get("subject"), team = (UUID) key.get("team_id");
    db.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", Object.class, "key:" + subject);
    JsonNode policy;
    try {
      policy = json.readTree((String) key.get("policy"));
    } catch (Exception e) {
      throw new IllegalStateException("Invalid key policy", e);
    }
    if (policy.path("rateLimited").asBoolean(false)
        && db.queryForObject(
                "SELECT count(*) FROM gateway_request WHERE quota_subject=? AND"
                    + " started_at>now()-interval '1 minute'",
                Long.class,
                subject)
            >= 600) throw limited("API key request rate exceeded");
    boolean reserve = false;
    Map<String, Instant> windows = new LinkedHashMap<>();
    windows.put("spendLimitUsd", Instant.EPOCH);
    windows.put("fiveHourLimitUsd", Instant.now().minusSeconds(18000));
    windows.put("dailyLimitUsd", window("daily")[0]);
    windows.put("weeklyLimitUsd", window("weekly")[0]);
    windows.put("monthlyLimitUsd", window("monthly")[0]);
    for (var cap : windows.entrySet()) {
      BigDecimal limit = policy.path(cap.getKey()).decimalValue();
      if (limit.signum() <= 0) continue;
      reserve = true;
      requirePrice(amount);
      BigDecimal used =
          db.queryForObject(
              "SELECT COALESCE(sum(u.cost_estimate),0) FROM gateway_request r JOIN gateway_usage u"
                  + " ON u.request_id=r.id WHERE r.quota_subject=? AND r.started_at>=?",
              BigDecimal.class,
              subject,
              Timestamp.from(cap.getValue()));
      BigDecimal pending =
          db.queryForObject(
              "SELECT COALESCE(sum(amount),0) FROM gateway_spend_reservation WHERE subject_id=? AND"
                  + " expires_at>now()",
              BigDecimal.class,
              subject);
      if (used.add(pending).add(amount).compareTo(limit) > 0)
        throw limited("API key " + cap.getKey() + " would be exceeded");
    }
    if (team != null) {
      var teams =
          db.queryForList(
              "SELECT status,daily_limit_usd,monthly_limit_usd FROM gateway_team WHERE id=? FOR"
                  + " UPDATE",
              team);
      if (teams.isEmpty() || !"active".equals(teams.getFirst().get("status")))
        throw new GatewayException(
            HttpStatus.FORBIDDEN,
            "forbidden_error",
            "team_disabled",
            "The API key project is not active");
      for (String period : List.of("daily", "monthly")) {
        BigDecimal limit = (BigDecimal) teams.getFirst().get(period + "_limit_usd");
        if (limit.signum() <= 0) continue;
        reserve = true;
        requirePrice(amount);
        BigDecimal used =
            db.queryForObject(
                "SELECT COALESCE(sum(u.cost_estimate),0) FROM gateway_request r JOIN gateway_usage"
                    + " u ON u.request_id=r.id WHERE r.team_id=? AND r.started_at>=?",
                BigDecimal.class,
                team,
                Timestamp.from(window(period)[0]));
        BigDecimal pending =
            db.queryForObject(
                "SELECT COALESCE(sum(amount),0) FROM gateway_spend_reservation WHERE team_id=? AND"
                    + " expires_at>now()",
                BigDecimal.class,
                team);
        if (used.add(pending).add(amount).compareTo(limit) > 0)
          throw limited("Project " + period + " budget would be exceeded");
      }
    }
    if (reserve)
      db.update(
          "INSERT INTO gateway_spend_reservation(request_id,subject_id,team_id,amount) VALUES"
              + " (?,?,?,?)",
          requestId,
          subject,
          team,
          amount);
  }

  private static void requirePrice(BigDecimal amount) {
    if (amount == null)
      throw new GatewayException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "server_error",
          "pricing_required",
          "A configured price card is required to enforce spending limits");
  }

  private static GatewayException limited(String message) {
    return new GatewayException(
        HttpStatus.TOO_MANY_REQUESTS, "rate_limit_error", "spend_limit_exceeded", message);
  }

  BigDecimal used(UUID user, String type, String period) {
    String expression =
        switch (type) {
          case "requests" -> "count(*)";
          case "tokens" ->
              "COALESCE(sum(u.input_tokens+u.output_tokens+u.cache_read_tokens+u.cache_write_tokens),0)";
          case "cost" -> "COALESCE(sum(u.cost_estimate),0)";
          default -> throw new IllegalArgumentException("Invalid quota type");
        };
    return db.queryForObject(
        "SELECT "
            + expression
            + " FROM gateway_request r LEFT JOIN gateway_usage u ON u.request_id=r.id WHERE"
            + " r.user_id=? AND r.started_at>=?",
        BigDecimal.class,
        user,
        Timestamp.from(window(period)[0]));
  }

  static Instant[] window(String period) {
    ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC),
        start = now.toLocalDate().atStartOfDay(ZoneOffset.UTC);
    return switch (period) {
      case "daily" -> new Instant[] {start.toInstant(), start.plusDays(1).toInstant()};
      case "weekly" -> {
        start = start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        yield new Instant[] {start.toInstant(), start.plusWeeks(1).toInstant()};
      }
      case "monthly" -> {
        start = start.withDayOfMonth(1);
        yield new Instant[] {start.toInstant(), start.plusMonths(1).toInstant()};
      }
      default -> throw new IllegalArgumentException("Invalid quota period");
    };
  }

  private record Quota(UUID id, String type, BigDecimal limit, String period) {}
}
