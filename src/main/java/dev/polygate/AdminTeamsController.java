package dev.polygate;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "polygate.database.enabled", havingValue = "true")
class AdminTeamsController {
  private final JdbcTemplate db;
  private final AdminAuthService auth;
  private final ObjectMapper json;

  AdminTeamsController(JdbcTemplate db, AdminAuthService auth, ObjectMapper json) {
    this.db = db;
    this.auth = auth;
    this.json = json;
  }

  @GetMapping("/admin/teams")
  @Transactional(readOnly = true)
  List<Map<String, Object>> list(@RequestHeader HttpHeaders headers) {
    auth.requireAdmin(headers);
    return db.query(
        "SELECT * FROM gateway_team ORDER BY created_at",
        (rs, i) -> {
          UUID id = (UUID) rs.getObject("id");
          Map<String, Object> result = new HashMap<>();
          result.put("id", id.toString());
          result.put("name", rs.getString("name"));
          result.put("slug", rs.getString("slug"));
          result.put("description", rs.getString("description"));
          result.put("status", rs.getString("status"));
          result.put("dailyLimitUsd", rs.getBigDecimal("daily_limit_usd"));
          result.put("monthlyLimitUsd", rs.getBigDecimal("monthly_limit_usd"));
          result.put("dailySpendUsd", spend(id, "daily"));
          result.put("monthlySpendUsd", spend(id, "monthly"));
          result.put(
              "activeApiKeys",
              db.queryForObject(
                  "SELECT count(*) FROM gateway_api_key WHERE team_id=? AND enabled=true AND"
                      + " rotation_state='active' AND (expires_at IS NULL OR expires_at>now())",
                  Long.class,
                  id));
          result.put(
              "requestsToday",
              db.queryForObject(
                  "SELECT count(*) FROM gateway_request WHERE team_id=? AND started_at>=?",
                  Long.class,
                  id,
                  Timestamp.from(QuotaService.window("daily")[0])));
          try {
            result.put("allowedModels", json.readTree(rs.getString("allowed_models")));
            result.put("allowedProviders", json.readTree(rs.getString("allowed_providers")));
          } catch (Exception e) {
            throw new IllegalStateException("Invalid project allowlist", e);
          }
          result.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
          result.put("updatedAt", rs.getTimestamp("updated_at").toInstant().toString());
          return result;
        });
  }

  @PostMapping("/admin/teams")
  @Transactional
  Map<String, Object> create(@RequestHeader HttpHeaders headers, @RequestBody JsonNode input) {
    auth.requireWrite(headers);
    auth.requireAdmin(headers);
    UUID id = UUID.randomUUID();
    write(id, input, false);
    return find(headers, id);
  }

  @PutMapping("/admin/teams/{id}")
  @Transactional
  Map<String, Object> update(
      @RequestHeader HttpHeaders headers, @PathVariable UUID id, @RequestBody JsonNode input) {
    auth.requireWrite(headers);
    auth.requireAdmin(headers);
    Map<String, Object> old = find(headers, id);
    ObjectNode merged = json.valueToTree(old);
    input.fields().forEachRemaining(entry -> merged.set(entry.getKey(), entry.getValue()));
    write(id, merged, true);
    return find(headers, id);
  }

  @DeleteMapping("/admin/teams/{id}")
  ResponseEntity<Void> delete(@RequestHeader HttpHeaders headers, @PathVariable UUID id) {
    auth.requireWrite(headers);
    auth.requireAdmin(headers);
    if (db.update("DELETE FROM gateway_team WHERE id=?", id) == 0) throw missing();
    return ResponseEntity.noContent().build();
  }

  private Map<String, Object> find(HttpHeaders headers, UUID id) {
    return list(headers).stream()
        .filter(row -> id.toString().equals(row.get("id")))
        .findFirst()
        .orElseThrow(AdminTeamsController::missing);
  }

  private BigDecimal spend(UUID id, String period) {
    return db.queryForObject(
        "SELECT COALESCE(sum(u.cost_estimate),0) FROM gateway_request r JOIN gateway_usage u ON"
            + " u.request_id=r.id WHERE r.team_id=? AND r.started_at>=?",
        BigDecimal.class,
        id,
        Timestamp.from(QuotaService.window(period)[0]));
  }

  private void write(UUID id, JsonNode input, boolean update) {
    String name = input.path("name").asText("").trim(), slug = input.path("slug").asText("").trim();
    if (name.isBlank() || name.length() > 120) throw bad("Invalid project name");
    if (slug.isBlank()) slug = "project-" + id.toString().substring(0, 8);
    if (!slug.matches("[a-z0-9][a-z0-9_-]{0,63}")) throw bad("Invalid project slug");
    String status = input.path("status").asText("active");
    if (!Set.of("active", "disabled", "archived").contains(status))
      throw bad("Invalid project status");
    BigDecimal daily = limit(input.path("dailyLimitUsd")),
        monthly = limit(input.path("monthlyLimitUsd"));
    String models = allowlist(input.path("allowedModels")),
        providers = allowlist(input.path("allowedProviders"));
    String description = input.path("description").asText("");
    if (description.length() > 2000) throw bad("Project description is too long");
    try {
      if (update)
        db.update(
            "UPDATE gateway_team SET"
                + " name=?,slug=?,description=?,status=?,daily_limit_usd=?,monthly_limit_usd=?,allowed_models=?::jsonb,allowed_providers=?::jsonb,updated_at=now()"
                + " WHERE id=?",
            name,
            slug,
            description,
            status,
            daily,
            monthly,
            models,
            providers,
            id);
      else
        db.update(
            "INSERT INTO"
                + " gateway_team(id,name,slug,description,status,daily_limit_usd,monthly_limit_usd,allowed_models,allowed_providers)"
                + " VALUES (?,?,?,?,?,?,?,?::jsonb,?::jsonb)",
            id,
            name,
            slug,
            description,
            status,
            daily,
            monthly,
            models,
            providers);
    } catch (org.springframework.dao.DuplicateKeyException e) {
      throw new GatewayException(
          HttpStatus.CONFLICT,
          "invalid_request_error",
          "project_exists",
          "Project slug already exists");
    }
  }

  private static BigDecimal limit(JsonNode value) {
    if (value.isMissingNode() || value.isNull()) return BigDecimal.ZERO;
    if (!value.isNumber()
        || value.decimalValue().signum() < 0
        || value.decimalValue().compareTo(BigDecimal.valueOf(1_000_000_000)) > 0)
      throw bad("Invalid project budget");
    return value.decimalValue();
  }

  private static String allowlist(JsonNode values) {
    if (values.isMissingNode() || values.isNull()) return "[]";
    if (!values.isArray() || values.size() > 256) throw bad("Invalid project allowlist");
    for (JsonNode value : values)
      if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 240)
        throw bad("Invalid project allowlist entry");
    return values.toString();
  }

  private static GatewayException missing() {
    return new GatewayException(
        HttpStatus.NOT_FOUND, "not_found_error", "project_not_found", "Project not found");
  }

  private static GatewayException bad(String detail) {
    return new GatewayException(
        HttpStatus.BAD_REQUEST, "invalid_request_error", "invalid_request", detail);
  }
}
