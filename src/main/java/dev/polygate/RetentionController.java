package dev.polygate;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "polygate.database.enabled", havingValue = "true")
class RetentionController {
  private final JdbcTemplate db;
  private final AdminAuthService auth;
  private final ObjectMapper json;

  RetentionController(JdbcTemplate db, AdminAuthService auth, ObjectMapper json) {
    this.db = db;
    this.auth = auth;
    this.json = json;
  }

  @PostMapping("/admin/retention/run")
  @Transactional
  ObjectNode run(@RequestHeader HttpHeaders headers, @RequestBody JsonNode input) {
    auth.requireWrite(headers);
    auth.requireAdmin(headers);
    if (!input.isObject() || input.has("dryRun") && !input.path("dryRun").isBoolean())
      throw bad("dryRun must be boolean");
    var actor = UUID.fromString((String) auth.requireSession(headers).get("id"));
    ObjectNode policy =
        json.createObjectNode()
            .put("requestDetailDays", days("POLYGATE_RETENTION_REQUEST_DETAIL_DAYS", 30))
            .put("userUsageDays", days("POLYGATE_RETENTION_USER_USAGE_DAYS", 90))
            .put("auditDays", days("POLYGATE_RETENTION_AUDIT_DAYS", 395))
            .put("legalHold", Boolean.parseBoolean(System.getenv("POLYGATE_RETENTION_LEGAL_HOLD")))
            .put("contentPersistence", false);
    if (policy.path("requestDetailDays").asInt() > policy.path("userUsageDays").asInt()
        || policy.path("userUsageDays").asInt() > policy.path("auditDays").asInt())
      throw bad("Retention periods must satisfy detail <= usage <= audit");
    boolean dry = input.path("dryRun").asBoolean(true);
    ObjectNode result;
    if (dry) {
      long now = System.currentTimeMillis();
      result =
          json.createObjectNode()
              .put("dryRun", true)
              .put("applied", false)
              .put("evaluatedAtMs", now)
              .put(
                  "requestDetailCutoffMs",
                  now - policy.path("requestDetailDays").asLong() * 86400000L)
              .put("userUsageCutoffMs", now - policy.path("userUsageDays").asLong() * 86400000L)
              .put("auditCutoffMs", now - policy.path("auditDays").asLong() * 86400000L)
              .put("immutableBudgetEventsRetained", true);
      result.set("policy", policy);
      result.set("counts", counts(result));
      if (policy.path("legalHold").asBoolean()) {
        result
            .put("skippedReason", "legal_hold")
            .putNull("previewToken")
            .putNull("previewExpiresAtMs");
      } else {
        result.putNull("skippedReason");
        String token = UUID.randomUUID() + "-" + UUID.randomUUID();
        result.put("previewExpiresAtMs", now + 300000);
        db.update(
            "INSERT INTO gateway_retention_preview(token_hash,actor_id,document,expires_at) VALUES"
                + " (?,?,?::jsonb,?)",
            hash(token),
            actor,
            result.toString(),
            new Timestamp(now + 300000));
        result.put("previewToken", token);
      }
      return result;
    }
    String token = input.path("previewToken").asText("");
    if (token.length() != 73) throw bad("Run a new retention preview before applying");
    var previews =
        db.query(
            "SELECT document::text FROM gateway_retention_preview WHERE token_hash=? AND actor_id=?"
                + " AND consumed_at IS NULL AND expires_at>now() FOR UPDATE",
            (rs, i) -> parse(rs.getString(1)),
            hash(token),
            actor);
    if (previews.isEmpty())
      throw bad(
          "Retention preview is missing, expired, consumed or belongs to another administrator");
    result = previews.getFirst();
    if (!policy.equals(result.path("policy")) || policy.path("legalHold").asBoolean())
      throw bad("Retention policy changed; run a new preview");
    Timestamp detail = new Timestamp(result.path("requestDetailCutoffMs").asLong()),
        usage = new Timestamp(result.path("userUsageCutoffMs").asLong()),
        audit = new Timestamp(result.path("auditCutoffMs").asLong());
    ObjectNode counts = json.createObjectNode();
    counts.put(
        "requestDetailsRedacted",
        db.update(
            "UPDATE gateway_request SET client_ip=NULL,credential_id=NULL,detail_redacted=true"
                + " WHERE state<>'started' AND started_at<? AND NOT detail_redacted",
            detail));
    counts.put(
        "providerAttemptsRedacted",
        db.update(
            "UPDATE gateway_provider_attempt SET credential_id=NULL,detail_redacted=true WHERE"
                + " started_at<? AND NOT detail_redacted",
            detail));
    counts.put(
        "routingDecisionsDeleted",
        db.update("DELETE FROM gateway_routing_decision WHERE occurred_at<?", detail));
    // Quota subjects, prices, totals and the append-only budget ledger remain available for
    // financial reconciliation.
    counts.put(
        "userUsageRowsDeidentified",
        db.update(
            "UPDATE gateway_request SET"
                + " user_id=NULL,api_key_id=NULL,team_id=NULL,identity_redacted=true WHERE"
                + " state<>'started' AND started_at<? AND NOT identity_redacted",
            usage));
    counts.put(
        "auditEventsDeleted", db.update("DELETE FROM gateway_audit WHERE occurred_at<?", audit));
    db.update(
        "UPDATE gateway_retention_preview SET consumed_at=now() WHERE token_hash=?", hash(token));
    result.set("counts", counts);
    result
        .put("dryRun", false)
        .put("applied", true)
        .putNull("previewToken")
        .putNull("previewExpiresAtMs");
    return result;
  }

  private ObjectNode counts(JsonNode r) {
    ObjectNode c = json.createObjectNode();
    c.put(
        "requestDetailsRedacted",
        db.queryForObject(
            "SELECT count(*) FROM gateway_request WHERE state<>'started' AND started_at<? AND NOT"
                + " detail_redacted",
            Long.class,
            new Timestamp(r.path("requestDetailCutoffMs").asLong())));
    c.put(
        "providerAttemptsRedacted",
        db.queryForObject(
            "SELECT count(*) FROM gateway_provider_attempt WHERE started_at<? AND NOT"
                + " detail_redacted",
            Long.class,
            new Timestamp(r.path("requestDetailCutoffMs").asLong())));
    c.put(
        "routingDecisionsDeleted",
        db.queryForObject(
            "SELECT count(*) FROM gateway_routing_decision WHERE occurred_at<?",
            Long.class,
            new Timestamp(r.path("requestDetailCutoffMs").asLong())));
    c.put(
        "userUsageRowsDeidentified",
        db.queryForObject(
            "SELECT count(*) FROM gateway_request WHERE state<>'started' AND started_at<? AND NOT"
                + " identity_redacted",
            Long.class,
            new Timestamp(r.path("userUsageCutoffMs").asLong())));
    c.put(
        "auditEventsDeleted",
        db.queryForObject(
            "SELECT count(*) FROM gateway_audit WHERE occurred_at<?",
            Long.class,
            new Timestamp(r.path("auditCutoffMs").asLong())));
    return c;
  }

  private ObjectNode parse(String value) {
    try {
      return (ObjectNode) json.readTree(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static int days(String name, int fallback) {
    String v = System.getenv(name);
    int n = v == null ? fallback : Integer.parseInt(v);
    if (n < 1 || n > 36500) throw new IllegalStateException("Invalid retention period");
    return n;
  }

  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static GatewayException bad(String message) {
    return new GatewayException(
        HttpStatus.BAD_REQUEST, "invalid_request_error", "invalid_retention_preview", message);
  }
}
