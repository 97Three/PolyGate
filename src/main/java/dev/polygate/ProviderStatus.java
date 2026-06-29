package dev.polygate;

import java.sql.Timestamp;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "polygate.database.enabled", havingValue = "true")
class ProviderStatus {
  private final JdbcTemplate db;

  ProviderStatus(JdbcTemplate db) {
    this.db = db;
  }

  Map<String, Object> health(String provider, String credential) {
    String where = " WHERE provider_id=?" + (credential == null ? "" : " AND credential_id=?");
    Object[] args =
        credential == null ? new Object[] {provider} : new Object[] {provider, credential};
    var totals =
        db.queryForMap(
            "SELECT count(*) FILTER(WHERE state IN ('completed','failed')) total,count(*)"
                + " FILTER(WHERE state='completed') success,count(*) FILTER(WHERE state='failed')"
                + " failures,max(ended_at) last_used,max(ended_at) FILTER(WHERE state='completed')"
                + " last_success,max(ended_at) FILTER(WHERE state='failed') last_failure FROM"
                + " gateway_provider_attempt"
                + where,
            args);
    var recent =
        db.queryForList(
            "SELECT state,http_status,safe_error_code,ended_at FROM gateway_provider_attempt"
                + where
                + " AND state IN ('completed','failed') ORDER BY ended_at DESC LIMIT 3",
            args);
    int status =
        recent.isEmpty() || recent.getFirst().get("http_status") == null
            ? 0
            : ((Number) recent.getFirst().get("http_status")).intValue();
    boolean account =
        Set.of(401, 402, 403).contains(status)
            && !recent.isEmpty()
            && "failed".equals(recent.getFirst().get("state"));
    boolean cooling =
        !recent.isEmpty()
            && ((Timestamp) recent.getFirst().get("ended_at")).getTime() + 60000
                > System.currentTimeMillis()
            && (account
                || recent.size() == 3
                    && recent.stream()
                        .allMatch(
                            r ->
                                "failed".equals(r.get("state"))
                                    && r.get("http_status") instanceof Number n
                                    && n.intValue() >= 429));
    long total = ((Number) totals.get("total")).longValue(),
        success = ((Number) totals.get("success")).longValue();
    Map<String, Object> out = new HashMap<>();
    out.put("providerId", provider);
    if (credential != null) out.put("credentialId", credential);
    out.put("requestsTotal", total);
    out.put("successesTotal", success);
    out.put("failuresTotal", totals.get("failures"));
    out.put("successRate", total == 0 ? 100 : 100.0 * success / total);
    out.put(
        "status",
        cooling
            ? "cooldown"
            : recent.isEmpty() || "completed".equals(recent.getFirst().get("state"))
                ? "healthy"
                : "degraded");
    int consecutive = 0;
    for (var r : recent) {
      if (!"failed".equals(r.get("state"))) break;
      consecutive++;
    }
    out.put("consecutiveFailures", consecutive);
    out.put("lastStatusCode", status == 0 ? null : status);
    out.put("lastError", recent.isEmpty() ? null : recent.getFirst().get("safe_error_code"));
    for (String field : List.of("last_used", "last_success", "last_failure")) {
      String target =
          switch (field) {
            case "last_used" -> "lastUsedAt";
            case "last_success" -> "lastSuccessAt";
            default -> "lastFailureAt";
          };
      out.put(
          target,
          totals.get(field) == null
              ? null
              : ((Timestamp) totals.get(field)).toInstant().toString());
    }
    out.put(
        "cooldownUntil",
        cooling
            ? ((Timestamp) recent.getFirst().get("ended_at")).toInstant().plusSeconds(60).toString()
            : null);
    out.put(
        "failureKind",
        account
            ? "account"
            : status == 429
                ? "rate_limit"
                : status >= 500 ? "upstream_unavailable" : status >= 400 ? "config" : "none");
    out.put("accountIssue", status == 402 ? "insufficient_balance" : account ? "auth" : "none");
    out.put("rechargeRequired", account && status == 402);
    out.put(
        "recommendedAction",
        account ? status == 402 ? "核对上游账户余额" : "核对上游账号凭据与权限" : cooling ? "等待冷却后探测或选择其他账号" : null);
    return out;
  }

  boolean cooling(String provider, String credential) {
    return "cooldown".equals(health(provider, credential).get("status"));
  }
}
