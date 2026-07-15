package dev.polygate;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "polygate.database.enabled", havingValue = "true")
class AdminQuotasController {
  private final JdbcTemplate db;
  private final AdminAuthService auth;
  private final QuotaService quotas;

  AdminQuotasController(JdbcTemplate db, AdminAuthService auth, QuotaService quotas) {
    this.db = db;
    this.auth = auth;
    this.quotas = quotas;
  }

  @GetMapping("/admin/quotas")
  @Transactional(readOnly = true)
  List<Map<String, Object>> list(@RequestHeader HttpHeaders headers) {
    auth.requireAdmin(headers);
    return db.query(
        "SELECT q.*,u.username FROM gateway_quota q JOIN gateway_user u ON u.id=q.user_id ORDER BY"
            + " u.username,q.quota_type",
        (rs, i) -> {
          UUID user = (UUID) rs.getObject("user_id");
          String type = rs.getString("quota_type"), period = rs.getString("period");
          var window = QuotaService.window(period);
          return Map.<String, Object>of(
              "id",
              rs.getObject("id").toString(),
              "userId",
              user.toString(),
              "username",
              rs.getString("username"),
              "quotaType",
              type,
              "limit",
              rs.getBigDecimal("quota_limit"),
              "used",
              quotas.used(user, type, period),
              "period",
              period,
              "periodStart",
              window[0].toString(),
              "periodEnd",
              window[1].toString(),
              "resetAt",
              window[1].toString());
        });
  }

  @PostMapping("/admin/quotas")
  @Transactional
  Map<String, Object> create(@RequestHeader HttpHeaders headers, @RequestBody JsonNode input) {
    auth.requireWrite(headers);
    auth.requireAdmin(headers);
    UUID id = UUID.randomUUID();
    write(id, input, false);
    return find(headers, id);
  }

  @PutMapping("/admin/quotas/{id}")
  @Transactional
  Map<String, Object> update(
      @RequestHeader HttpHeaders headers, @PathVariable UUID id, @RequestBody JsonNode input) {
    auth.requireWrite(headers);
    auth.requireAdmin(headers);
    write(id, input, true);
    return find(headers, id);
  }

  @DeleteMapping("/admin/quotas/{id}")
  ResponseEntity<Void> delete(@RequestHeader HttpHeaders headers, @PathVariable UUID id) {
    auth.requireWrite(headers);
    auth.requireAdmin(headers);
    if (db.update("DELETE FROM gateway_quota WHERE id=?", id) == 0) throw missing();
    return ResponseEntity.noContent().build();
  }

  private Map<String, Object> find(HttpHeaders headers, UUID id) {
    return list(headers).stream()
        .filter(q -> id.toString().equals(q.get("id")))
        .findFirst()
        .orElseThrow(AdminQuotasController::missing);
  }

  private void write(UUID id, JsonNode input, boolean update) {
    var old =
        update
            ? db.queryForList("SELECT * FROM gateway_quota WHERE id=?", id)
            : List.<Map<String, Object>>of();
    if (update && old.isEmpty()) throw missing();
    Map<String, Object> previous = old.isEmpty() ? Map.of() : old.getFirst();
    String user = input.path("userId").asText(Objects.toString(previous.get("user_id"), ""));
    UUID uid;
    try {
      uid = UUID.fromString(user);
    } catch (Exception e) {
      throw bad("Invalid userId");
    }
    String type = input.path("quotaType").asText(Objects.toString(previous.get("quota_type"), "")),
        period = input.path("period").asText(Objects.toString(previous.get("period"), ""));
    if (!Set.of("requests", "tokens", "cost").contains(type)
        || !Set.of("daily", "weekly", "monthly").contains(period))
      throw bad("Invalid quota type or period");
    BigDecimal limit;
    try {
      limit =
          input.has("limit")
              ? input.path("limit").decimalValue()
              : (BigDecimal) previous.get("quota_limit");
      if (limit == null || limit.signum() <= 0 || limit.precision() > 24)
        throw new IllegalArgumentException();
    } catch (Exception e) {
      throw bad("Quota limit must be positive");
    }
    if (db.queryForObject("SELECT count(*) FROM gateway_user WHERE id=?", Integer.class, uid) == 0)
      throw bad("Unknown userId");
    try {
      if (update)
        db.update(
            "UPDATE gateway_quota SET user_id=?,quota_type=?,quota_limit=?,period=? WHERE id=?",
            uid,
            type,
            limit,
            period,
            id);
      else
        db.update(
            "INSERT INTO gateway_quota(id,user_id,quota_type,quota_limit,period) VALUES"
                + " (?,?,?,?,?)",
            id,
            uid,
            type,
            limit,
            period);
    } catch (org.springframework.dao.DuplicateKeyException e) {
      throw new GatewayException(
          HttpStatus.CONFLICT,
          "invalid_request_error",
          "quota_exists",
          "A quota already exists for this user, type and period");
    }
  }

  private static GatewayException bad(String detail) {
    return new GatewayException(
        HttpStatus.BAD_REQUEST, "invalid_request_error", "invalid_request", detail);
  }

  private static GatewayException missing() {
    return new GatewayException(
        HttpStatus.NOT_FOUND, "not_found_error", "quota_not_found", "Quota not found");
  }
}
