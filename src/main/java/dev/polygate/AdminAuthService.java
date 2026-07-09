package dev.polygate;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "polygate.database.enabled", havingValue = "true")
class AdminAuthService {
  static final String COOKIE = "polygate_admin_session";
  private static final SecureRandom RANDOM = new SecureRandom();
  private final JdbcTemplate database;
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
  private final org.springframework.security.crypto.argon2.Argon2PasswordEncoder argon =
      org.springframework.security.crypto.argon2.Argon2PasswordEncoder
          .defaultsForSpringSecurity_v5_8();
  private final String dummyHash = passwords.encode(UUID.randomUUID().toString());
  private final String bootstrapPassword;

  @Value("${POLYGATE_ADMIN_COOKIE_SECURE:false}")
  private boolean secureCookie;

  String sessionCookie(String token, int seconds) {
    return sessionCookie(token, seconds, false);
  }

  String sessionCookie(String token, int seconds, boolean forceSecure) {
    return COOKIE
        + "="
        + token
        + "; Path=/; HttpOnly; SameSite=Lax; Max-Age="
        + seconds
        + ((secureCookie
                || forceSecure
                || (org.springframework.web.context.request.RequestContextHolder
                            .getRequestAttributes()
                        instanceof
                        org.springframework.web.context.request.ServletRequestAttributes s
                    && s.getRequest().isSecure()))
            ? "; Secure"
            : "");
  }

  AdminAuthService(
      JdbcTemplate database,
      @Value("${polygate.admin-bootstrap-password}") String bootstrapPassword) {
    this.database = database;
    this.bootstrapPassword = bootstrapPassword;
  }

  @PostConstruct
  void bootstrap() {
    new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                database.getDataSource()))
        .executeWithoutResult(
            tx -> {
              database.queryForObject("SELECT pg_advisory_xact_lock(867204613)", Object.class);
              Integer count =
                  database.queryForObject("SELECT count(*) FROM gateway_user", Integer.class);
              if (count != null && count == 0) {
                if (bootstrapPassword.length() < 24)
                  throw new IllegalStateException(
                      "Set a long POLYGATE_ADMIN_BOOTSTRAP_PASSWORD before first startup");
                database.update(
                    "INSERT INTO gateway_user(id, username, password_hash, role, email, status)"
                        + " VALUES (?, 'admin', ?, 'admin', '', 'active')",
                    UUID.randomUUID(),
                    passwords.encode(bootstrapPassword));
              }
            });
  }

  Login login(String username, String password) {
    if (username == null
        || password == null
        || username.length() > 128
        || password.getBytes(StandardCharsets.UTF_8).length > 1024) throw unauthorized();
    var attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
    String address =
        attrs instanceof org.springframework.web.context.request.ServletRequestAttributes servlet
            ? servlet.getRequest().getRemoteAddr()
            : "local";
    String attempt =
        HexFormat.of()
            .formatHex(hash(username.toLowerCase(java.util.Locale.ROOT) + "\u0000" + address));
    if (database.queryForObject(
            "SELECT count(*) FROM gateway_login_attempt WHERE identity_hash=? AND"
                + " locked_until>now()",
            Integer.class,
            attempt)
        > 0)
      throw new GatewayException(
          HttpStatus.TOO_MANY_REQUESTS,
          "rate_limit_error",
          "login_locked",
          "Too many login failures; try again in 15 minutes");
    var rows =
        database.query(
            "SELECT id, username, password_hash, role, email, status, created_at, last_login_at"
                + " FROM gateway_user WHERE username=?",
            (rs, index) ->
                new UserRow(
                    (UUID) rs.getObject("id"),
                    rs.getString("username"),
                    rs.getString("password_hash"),
                    rs.getString("role"),
                    rs.getString("email"),
                    rs.getString("status"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("last_login_at") == null
                        ? null
                        : rs.getTimestamp("last_login_at").toInstant()),
            username);
    String stored = rows.isEmpty() ? dummyHash : rows.getFirst().passwordHash;
    boolean matches =
        stored.startsWith("$argon2")
            ? argon.matches(password, stored)
            : password.getBytes(StandardCharsets.UTF_8).length <= 72
                && passwords.matches(password, stored);
    if (rows.isEmpty() || !"active".equals(rows.getFirst().status) || !matches) {
      database.update(
          "INSERT INTO gateway_login_attempt(identity_hash,failures) VALUES (?,1) ON"
              + " CONFLICT(identity_hash) DO UPDATE SET failures=CASE WHEN"
              + " gateway_login_attempt.updated_at<now()-interval '15 minutes' THEN 1 ELSE"
              + " gateway_login_attempt.failures+1 END,locked_until=CASE WHEN"
              + " gateway_login_attempt.failures>=4 AND"
              + " gateway_login_attempt.updated_at>=now()-interval '15 minutes' THEN now()+interval"
              + " '15 minutes' ELSE NULL END,updated_at=now()",
          attempt);
      throw unauthorized();
    }
    database.update(
        "DELETE FROM gateway_login_attempt WHERE identity_hash=? OR updated_at<now()-interval '1"
            + " day'",
        attempt);
    UserRow user = rows.getFirst();
    return issue(user);
  }

  Login federatedLogin(UUID id) {
    var row = database.queryForMap("SELECT * FROM gateway_user WHERE id=? AND status='active'", id);
    return issue(
        new UserRow(
            id,
            (String) row.get("username"),
            null,
            (String) row.get("role"),
            (String) row.get("email"),
            (String) row.get("status"),
            ((java.sql.Timestamp) row.get("created_at")).toInstant(),
            row.get("last_login_at") == null
                ? null
                : ((java.sql.Timestamp) row.get("last_login_at")).toInstant()));
  }

  private Login issue(UserRow user) {
    byte[] secret = new byte[32];
    RANDOM.nextBytes(secret);
    String token = HexFormat.of().formatHex(secret);
    Instant expiry = Instant.now().plusSeconds(12 * 60 * 60);
    database.update(
        "INSERT INTO gateway_session(id, user_id, token_hash, expires_at) VALUES (?, ?, ?, ?)",
        UUID.randomUUID(),
        user.id,
        hash(token),
        java.sql.Timestamp.from(expiry));
    database.update("UPDATE gateway_user SET last_login_at=now() WHERE id=?", user.id);
    return new Login(publicUser(user), token, expiry);
  }

  Map<String, Object> requireSession(HttpHeaders headers) {
    String token = cookieValue(headers);
    if (token == null || token.length() != 64) throw unauthorized();
    var rows =
        database.query(
            "SELECT u.id, u.username, u.role, u.email, u.status, u.created_at, u.last_login_at FROM"
                + " gateway_session s JOIN gateway_user u ON u.id=s.user_id WHERE s.token_hash=?"
                + " AND s.expires_at > now() AND u.status='active'",
            (rs, index) ->
                new UserRow(
                    (UUID) rs.getObject("id"),
                    rs.getString("username"),
                    null,
                    rs.getString("role"),
                    rs.getString("email"),
                    rs.getString("status"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("last_login_at") == null
                        ? null
                        : rs.getTimestamp("last_login_at").toInstant()),
            hash(token));
    if (rows.isEmpty()) throw unauthorized();
    return publicUser(rows.getFirst());
  }

  void logout(HttpHeaders headers) {
    String token = cookieValue(headers);
    if (token != null)
      database.update("DELETE FROM gateway_session WHERE token_hash=?", hash(token));
  }

  void requireWrite(HttpHeaders headers) {
    requireSession(headers);
    if (!"1".equals(headers.getFirst("x-polygate-csrf"))) {
      throw new GatewayException(
          HttpStatus.FORBIDDEN, "forbidden_error", "csrf_required", "CSRF header is required");
    }
    String origin = headers.getFirst(HttpHeaders.ORIGIN);
    if (origin == null) origin = headers.getFirst(HttpHeaders.REFERER);
    if (origin != null) {
      try {
        URI uri = URI.create(origin);
        String host = headers.getFirst(HttpHeaders.HOST);
        String incoming = uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
        if (host == null || !incoming.equalsIgnoreCase(host)) throw new IllegalArgumentException();
      } catch (Exception e) {
        throw new GatewayException(
            HttpStatus.FORBIDDEN,
            "forbidden_error",
            "origin_mismatch",
            "Request origin does not match host");
      }
    }
  }

  void requireAdmin(HttpHeaders headers) {
    if (!"admin".equals(requireSession(headers).get("role"))) {
      throw new GatewayException(
          HttpStatus.FORBIDDEN, "forbidden_error", "admin_required", "Admin role required");
    }
  }

  private Map<String, Object> publicUser(UserRow row) {
    Map<String, Object> user = new HashMap<>();
    user.put("id", row.id.toString());
    user.put("username", row.username);
    user.put("email", row.email);
    user.put("role", row.role);
    user.put("status", row.status);
    user.put("createdAt", row.createdAt.toString());
    user.put("lastLoginAt", row.lastLoginAt == null ? null : row.lastLoginAt.toString());
    user.put(
        "apiKeyCount",
        database.queryForObject(
            "SELECT count(*) FROM gateway_api_key WHERE user_id=?", Long.class, row.id));
    user.put(
        "requestCount24h",
        database.queryForObject(
            "SELECT count(*) FROM gateway_request WHERE user_id=? AND started_at>now()-interval '1"
                + " day'",
            Long.class,
            row.id));
    return user;
  }

  private static String cookieValue(HttpHeaders headers) {
    for (String cookieLine : headers.getOrEmpty(HttpHeaders.COOKIE)) {
      for (String entry : cookieLine.split(";")) {
        String[] pair = entry.trim().split("=", 2);
        if (pair.length == 2 && COOKIE.equals(pair[0])) return pair[1];
      }
    }
    return null;
  }

  private static byte[] hash(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static GatewayException unauthorized() {
    return new GatewayException(
        HttpStatus.UNAUTHORIZED,
        "authentication_error",
        "authentication_failed",
        "Invalid session or credentials");
  }

  record Login(Map<String, Object> user, String token, Instant expiry) {}

  private record UserRow(
      UUID id,
      String username,
      String passwordHash,
      String role,
      String email,
      String status,
      Instant createdAt,
      Instant lastLoginAt) {}
}
