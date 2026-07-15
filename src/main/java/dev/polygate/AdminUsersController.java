package dev.polygate;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "polygate.database.enabled", havingValue = "true")
class AdminUsersController {
  private static final String SELECT =
      """
SELECT u.*, (SELECT count(*) FROM gateway_api_key k WHERE k.user_id=u.id AND k.enabled=true) key_count,
(SELECT count(*) FROM gateway_request r WHERE r.user_id=u.id AND r.started_at>now()-interval '24 hours') request_count
FROM gateway_user u
""";
  private final JdbcTemplate db;
  private final AdminAuthService auth;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

  AdminUsersController(JdbcTemplate db, AdminAuthService auth) {
    this.db = db;
    this.auth = auth;
  }

  @GetMapping("/admin/users")
  List<Map<String, Object>> list(@RequestHeader HttpHeaders headers) {
    var actor = auth.requireSession(headers);
    if ("admin".equals(actor.get("role")))
      return db.query(SELECT + " ORDER BY u.created_at", this::row);
    return db.query(SELECT + " WHERE u.id=?", this::row, UUID.fromString((String) actor.get("id")));
  }

  @PostMapping("/admin/users")
  @Transactional
  Map<String, Object> create(@RequestHeader HttpHeaders headers, @RequestBody JsonNode input) {
    auth.requireWrite(headers);
    auth.requireAdmin(headers);
    String name = input.path("username").asText("").trim();
    if (!name.matches("[A-Za-z0-9._@-]{1,128}")) throw bad("Invalid username");
    String password = password(input.path("password").asText(""));
    String role = role(input.path("role").asText("user"));
    String status = status(input.path("status").asText("active"));
    String email = email(input.path("email").asText(""));
    UUID id = UUID.randomUUID();
    try {
      db.update(
          "INSERT INTO gateway_user(id,username,password_hash,role,email,status) VALUES"
              + " (?,?,?,?,?,?)",
          id,
          name,
          encoder.encode(password),
          role,
          email,
          status);
    } catch (org.springframework.dao.DuplicateKeyException e) {
      throw new GatewayException(
          HttpStatus.CONFLICT, "invalid_request_error", "user_exists", "Username already exists");
    }
    return get(id);
  }

  @PutMapping("/admin/users/{id}")
  @Transactional
  Map<String, Object> update(
      @RequestHeader HttpHeaders headers, @PathVariable UUID id, @RequestBody JsonNode input) {
    auth.requireWrite(headers);
    auth.requireAdmin(headers);
    db.execute("LOCK TABLE gateway_user IN SHARE ROW EXCLUSIVE MODE");
    Map<String, Object> old = get(id);
    String role = role(input.path("role").asText((String) old.get("role")));
    String status = status(input.path("status").asText((String) old.get("status")));
    protectLastAdmin(old, role, status);
    db.update(
        "UPDATE gateway_user SET email=?,role=?,status=? WHERE id=?",
        input.has("email") ? email(input.path("email").asText()) : old.get("email"),
        role,
        status,
        id);
    if (input.hasNonNull("password") && !input.path("password").asText().isEmpty()) {
      db.update(
          "UPDATE gateway_user SET password_hash=? WHERE id=?",
          encoder.encode(password(input.path("password").asText())),
          id);
      db.update("DELETE FROM gateway_session WHERE user_id=?", id);
    }
    if (!"active".equals(status)) db.update("DELETE FROM gateway_session WHERE user_id=?", id);
    return get(id);
  }

  @DeleteMapping("/admin/users/{id}")
  @Transactional
  ResponseEntity<Void> delete(@RequestHeader HttpHeaders headers, @PathVariable UUID id) {
    auth.requireWrite(headers);
    auth.requireAdmin(headers);
    db.execute("LOCK TABLE gateway_user IN SHARE ROW EXCLUSIVE MODE");
    Map<String, Object> old = get(id);
    protectLastAdmin(old, "user", "disabled");
    if (id.toString().equals(auth.requireSession(headers).get("id")))
      throw bad("Cannot delete the current user");
    db.update("DELETE FROM gateway_session WHERE user_id=?", id);
    db.update("DELETE FROM gateway_api_key WHERE user_id=?", id);
    db.update("DELETE FROM gateway_user WHERE id=?", id);
    return ResponseEntity.noContent().build();
  }

  private void protectLastAdmin(Map<String, Object> old, String role, String status) {
    if ("admin".equals(old.get("role"))
        && "active".equals(old.get("status"))
        && (!"admin".equals(role) || !"active".equals(status))
        && db.queryForObject(
                "SELECT count(*) FROM gateway_user WHERE role='admin' AND status='active'",
                Long.class)
            <= 1) throw bad("At least one active administrator is required");
  }

  private Map<String, Object> get(UUID id) {
    var rows = db.query(SELECT + " WHERE u.id=?", this::row, id);
    if (rows.isEmpty())
      throw new GatewayException(
          HttpStatus.NOT_FOUND, "not_found_error", "user_not_found", "User not found");
    return rows.getFirst();
  }

  private Map<String, Object> row(ResultSet rs, int index) throws SQLException {
    Map<String, Object> result = new HashMap<>();
    result.put("id", rs.getObject("id").toString());
    result.put("username", rs.getString("username"));
    result.put("email", rs.getString("email"));
    result.put("role", rs.getString("role"));
    result.put("status", rs.getString("status"));
    result.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
    result.put(
        "lastLoginAt",
        rs.getTimestamp("last_login_at") == null
            ? null
            : rs.getTimestamp("last_login_at").toInstant().toString());
    result.put("apiKeyCount", rs.getLong("key_count"));
    result.put("requestCount24h", rs.getLong("request_count"));
    return result;
  }

  private static String role(String value) {
    if (!Set.of("admin", "user", "viewer").contains(value)) throw bad("Invalid role");
    return value;
  }

  private static String status(String value) {
    if (!Set.of("active", "disabled", "suspended").contains(value)) throw bad("Invalid status");
    return value;
  }

  private static String email(String value) {
    if (value.length() > 254 || value.contains("\n") || value.contains("\r"))
      throw bad("Invalid email");
    return value;
  }

  private static String password(String value) {
    if (value.length() < 12 || value.getBytes(StandardCharsets.UTF_8).length > 72)
      throw bad("Password must contain at least 12 characters and at most 72 UTF-8 bytes");
    return value;
  }

  private static GatewayException bad(String detail) {
    return new GatewayException(
        HttpStatus.BAD_REQUEST, "invalid_request_error", "invalid_request", detail);
  }
}
