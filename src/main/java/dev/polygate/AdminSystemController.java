package dev.polygate;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "polygate.database.enabled", havingValue = "true")
class AdminSystemController {
  private final ConfigurationService config;
  private final AdminAuthService auth;
  private final JdbcTemplate db;
  private final AdminModelsController providers;
  private final AdminUsersController users;
  private final ApiKeyController keys;

  AdminSystemController(
      ConfigurationService config,
      AdminAuthService auth,
      JdbcTemplate db,
      AdminModelsController providers,
      AdminUsersController users,
      ApiKeyController keys) {
    this.config = config;
    this.auth = auth;
    this.db = db;
    this.providers = providers;
    this.users = users;
    this.keys = keys;
  }

  @GetMapping("/admin/settings")
  ObjectNode settings(@RequestHeader HttpHeaders headers) {
    auth.requireAdmin(headers);
    return config.system();
  }

  @PutMapping("/admin/settings")
  ObjectNode update(@RequestHeader HttpHeaders headers, @RequestBody JsonNode input) {
    auth.requireWrite(headers);
    auth.requireAdmin(headers);
    return config.updateSystem(input);
  }

  @PostMapping("/admin/settings/reload-config")
  Map<String, Object> reload(@RequestHeader HttpHeaders headers) {
    auth.requireWrite(headers);
    auth.requireAdmin(headers);
    ObjectNode settings = config.system();
    return Map.of(
        "ok",
        true,
        "settings",
        settings,
        "providerCount",
        providers.providers(headers).size(),
        "defaultProvider",
        settings.path("gateway").path("defaultProvider"),
        "providerOrder",
        settings.path("gateway").path("providerOrder"),
        "issues",
        List.of(),
        "reloadScope",
        Map.of(
            "applied",
            List.of("database-backed provider, model and alias configuration"),
            "requiresRestart",
            List.of("environment credentials", "bind address")));
  }

  @GetMapping("/admin/audit")
  Map<String, Object> audit(
      @RequestHeader HttpHeaders headers, @RequestParam(defaultValue = "100") int limit) {
    auth.requireAdmin(headers);
    if (limit < 1 || limit > 1000)
      throw new GatewayException(
          HttpStatus.BAD_REQUEST,
          "invalid_request_error",
          "invalid_request",
          "Invalid audit limit");
    var events =
        db.query(
            "SELECT a.*,u.username FROM gateway_audit a LEFT JOIN gateway_user u ON u.id=a.actor_id"
                + " ORDER BY occurred_at DESC LIMIT ?",
            (rs, i) -> {
              Map<String, Object> row = new HashMap<>();
              row.put("id", rs.getObject("id").toString());
              row.put("timestamp", rs.getTimestamp("occurred_at").toInstant().toString());
              row.put("type", "config_change");
              row.put("actor", rs.getString("username"));
              row.put("target", rs.getString("resource_id"));
              row.put("message", rs.getString("action") + " " + rs.getString("resource_id"));
              row.put("severity", "info");
              return row;
            },
            limit);
    return Map.of(
        "events",
        events,
        "total",
        db.queryForObject("SELECT count(*) FROM gateway_audit", Long.class));
  }

  @PostMapping("/admin/backup")
  ResponseEntity<?> backup(@RequestHeader HttpHeaders headers) {
    auth.requireWrite(headers);
    auth.requireAdmin(headers);
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(
            Map.of(
                "schemaVersion",
                1,
                "service",
                "polygate",
                "generatedAt",
                Instant.now().toString(),
                "containsSecrets",
                false,
                "containsPersonalData",
                true,
                "settings",
                config.system(),
                "users",
                users.list(headers),
                "control",
                Map.of(
                    "providers",
                    providers.providers(headers),
                    "aliases",
                    providers.aliases(headers),
                    "apiKeys",
                    keys.list(headers))));
  }
}
