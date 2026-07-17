package dev.polygate;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "polygate.database.enabled", havingValue = "true")
class IdempotencyService {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private byte[] secret;

  IdempotencyService(JdbcTemplate db, ObjectMapper json) {
    this.db = db;
    this.json = json;
  }

  @PostConstruct
  void initialize() {
    byte[] candidate = new byte[32];
    new SecureRandom().nextBytes(candidate);
    String encoded = Base64.getEncoder().encodeToString(candidate);
    db.update(
        "INSERT INTO gateway_setting(name,document) VALUES"
            + " ('private.fingerprintKey',jsonb_build_object('key',?::text)) ON CONFLICT DO"
            + " NOTHING",
        encoded);
    String stored =
        db.queryForObject(
            "SELECT document->>'key' FROM gateway_setting WHERE name='private.fingerprintKey'",
            String.class);
    secret = Base64.getDecoder().decode(stored);
    if (secret.length != 32)
      throw new IllegalStateException("Invalid private request fingerprint key");
  }

  boolean reserve(
      UUID request,
      ClientAuthService.Principal principal,
      String protocol,
      JsonNode payload,
      HttpHeaders headers) {
    String key = headers.getFirst("Idempotency-Key");
    if (key == null) return false;
    key = key.trim();
    if (key.isEmpty() || key.length() > 200)
      throw bad("Idempotency-Key must contain 1 to 200 visible ASCII characters");
    for (char c : key.toCharArray())
      if (c < 0x21 || c > 0x7e)
        throw bad("Idempotency-Key cannot contain whitespace or non-ASCII characters");
    byte[]
        scope =
            digest(
                principal.scope().key()
                    + "\0"
                    + Objects.toString(principal.userId(), "legacy")
                    + "\0"
                    + key),
        fingerprint = digest(protocol + "\0" + canonical(payload));
    int inserted =
        db.update(
            "INSERT INTO gateway_idempotency(scope_hash,request_fingerprint,request_id) VALUES"
                + " (?,?,?) ON CONFLICT(scope_hash) DO NOTHING",
            scope,
            fingerprint,
            request);
    if (inserted == 0) {
      var previous =
          db.queryForMap(
              "SELECT request_fingerprint,terminal FROM gateway_idempotency WHERE scope_hash=?",
              scope);
      boolean same =
          MessageDigest.isEqual(fingerprint, (byte[]) previous.get("request_fingerprint"));
      String message =
          !same
              ? "The idempotency key was already used with a different request body"
              : Boolean.TRUE.equals(previous.get("terminal"))
                  ? "The original request is terminal; response replay is not available"
                  : "The original request is still in progress";
      throw new GatewayException(
          HttpStatus.CONFLICT, "invalid_request_error", "idempotency_conflict", message);
    }
    return true;
  }

  void finish(UUID request) {
    db.update("UPDATE gateway_idempotency SET terminal=true WHERE request_id=?", request);
  }

  private byte[] digest(String value) {
    try {
      Mac hmac = Mac.getInstance("HmacSHA256");
      hmac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return hmac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private JsonNode canonical(JsonNode input) {
    if (input.isObject()) {
      ObjectNode out = json.createObjectNode();
      List<String> names = new ArrayList<>();
      input.fieldNames().forEachRemaining(names::add);
      Collections.sort(names);
      for (String name : names) out.set(name, canonical(input.path(name)));
      return out;
    }
    if (input.isArray()) {
      ArrayNode out = json.createArrayNode();
      for (JsonNode value : input) out.add(canonical(value));
      return out;
    }
    return input;
  }

  private static GatewayException bad(String message) {
    return new GatewayException(
        HttpStatus.BAD_REQUEST, "invalid_request_error", "invalid_request", message);
  }
}
