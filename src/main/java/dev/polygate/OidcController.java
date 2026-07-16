package dev.polygate;

import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@ConditionalOnProperty(name = "polygate.database.enabled", havingValue = "true")
class OidcController {
  static final String FLOW_COOKIE = "polygate_oidc_flow";
  private final JdbcTemplate db;
  private final AdminAuthService auth;
  private final WebClient http;
  private final String issuer, clientId, clientSecret, redirectUri;
  private final boolean provision, allowLocal;
  private final TransactionTemplate transaction;

  OidcController(
      JdbcTemplate db,
      AdminAuthService auth,
      WebClient.Builder builder,
      @Value("${POLYGATE_OIDC_ISSUER:}") String issuer,
      @Value("${POLYGATE_OIDC_CLIENT_ID:}") String clientId,
      @Value("${POLYGATE_OIDC_CLIENT_SECRET:}") String clientSecret,
      @Value("${POLYGATE_OIDC_REDIRECT_URI:}") String redirectUri,
      @Value("${POLYGATE_OIDC_AUTO_PROVISION:false}") boolean provision,
      @Value("${POLYGATE_OIDC_ALLOW_LOCAL_HTTP:false}") boolean allowLocal) {
    this.db = db;
    this.auth = auth;
    http = builder.build();
    this.issuer = issuer;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.redirectUri = redirectUri;
    this.provision = provision;
    this.allowLocal = allowLocal;
    transaction = new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()));
    if (enabled()) {
      endpoint(issuer);
      endpoint(redirectUri);
    }
  }

  boolean enabled() {
    return !issuer.isBlank() && !clientId.isBlank() && !redirectUri.isBlank();
  }

  @GetMapping("/admin/auth/oidc/start")
  ResponseEntity<?> start(@RequestParam(defaultValue = "/dashboard") String returnTo) {
    requireEnabled();
    if (!returnTo.matches(
        "/(dashboard|guide|api-keys|users|quotas|models|logs|enterprise|governance|operations|settings)(\\?[^#\\\\\\r"
            + "\\n"
            + "]*)?")) returnTo = "/dashboard";
    JsonNode discovery = discovery();
    String state = secret(), browser = secret(), nonce = secret(), verifier = secret();
    db.update("DELETE FROM gateway_oidc_flow WHERE expires_at<now()");
    db.update(
        "INSERT INTO"
            + " gateway_oidc_flow(state_hash,browser_hash,nonce,verifier,return_path,expires_at)"
            + " VALUES (?,?,?,?,?,now()+interval '5 minutes')",
        digest(state),
        digest(browser),
        nonce,
        verifier,
        returnTo);
    String location =
        UriComponentsBuilder.fromUri(endpoint(discovery.path("authorization_endpoint").asText()))
            .queryParam("response_type", "code")
            .queryParam("client_id", clientId)
            .queryParam("redirect_uri", redirectUri)
            .queryParam("scope", "openid profile email")
            .queryParam("state", state)
            .queryParam("nonce", nonce)
            .queryParam("code_challenge", challenge(verifier))
            .queryParam("code_challenge_method", "S256")
            .build()
            .encode()
            .toUriString();
    return ResponseEntity.status(302)
        .header(HttpHeaders.LOCATION, location)
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .header(
            HttpHeaders.SET_COOKIE,
            FLOW_COOKIE
                + "="
                + browser
                + "; Path=/admin/auth/oidc; HttpOnly; SameSite=Lax; Max-Age=300"
                + (redirectUri.startsWith("https://") ? "; Secure" : ""))
        .build();
  }

  @GetMapping("/admin/auth/oidc/callback")
  ResponseEntity<?> callback(
      @RequestParam(required = false) String code,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String error,
      HttpServletRequest servlet) {
    requireEnabled();
    if (error != null
        || code == null
        || code.length() > 8192
        || state == null
        || state.length() > 256) throw failed();
    String browser = null;
    if (servlet.getCookies() != null)
      for (var cookie : servlet.getCookies())
        if (FLOW_COOKIE.equals(cookie.getName())) browser = cookie.getValue();
    if (browser == null || browser.length() > 256) throw failed();
    var flows =
        db.queryForList(
            "DELETE FROM gateway_oidc_flow WHERE state_hash=? AND browser_hash=? AND"
                + " expires_at>now() RETURNING nonce,verifier,return_path",
            digest(state),
            digest(browser));
    if (flows.isEmpty()) throw failed();
    var flow = flows.getFirst();
    JsonNode discovery = discovery();
    var form = new LinkedMultiValueMap<String, String>();
    form.add("grant_type", "authorization_code");
    form.add("code", code);
    form.add("redirect_uri", redirectUri);
    form.add("code_verifier", flow.get("verifier").toString());
    form.add("client_id", clientId);
    var call =
        http.post()
            .uri(endpoint(discovery.path("token_endpoint").asText()))
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .accept(MediaType.APPLICATION_JSON);
    if (!clientSecret.isBlank())
      call.headers(h -> h.setBasicAuth(clientId, clientSecret, StandardCharsets.UTF_8));
    try {
      JsonNode tokens =
          call.body(BodyInserters.fromFormData(form))
              .retrieve()
              .bodyToMono(JsonNode.class)
              .timeout(Duration.ofSeconds(15))
              .block();
      if (tokens == null || !tokens.path("id_token").isTextual()) throw failed();
      NimbusJwtDecoder decoder =
          NimbusJwtDecoder.withJwkSetUri(endpoint(discovery.path("jwks_uri").asText()).toString())
              .build();
      OAuth2TokenValidator<Jwt> audience =
          jwt ->
              jwt.getAudience().contains(clientId)
                  ? OAuth2TokenValidatorResult.success()
                  : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_audience"));
      decoder.setJwtValidator(
          new DelegatingOAuth2TokenValidator<>(
              JwtValidators.createDefaultWithIssuer(issuer), audience));
      Jwt jwt = decoder.decode(tokens.path("id_token").asText());
      if (!constant(jwt.getClaimAsString("nonce"), flow.get("nonce").toString())
          || jwt.getSubject() == null
          || jwt.getSubject().isBlank()
          || jwt.getSubject().length() > 1024
          || jwt.getAudience().size() > 1 && !clientId.equals(jwt.getClaimAsString("azp")))
        throw failed();
      UUID user = transaction.execute(tx -> resolveUser(jwt));
      var login = auth.federatedLogin(user);
      return ResponseEntity.status(302)
          .header(HttpHeaders.LOCATION, flow.get("return_path").toString())
          .header(HttpHeaders.CACHE_CONTROL, "no-store")
          .header(
              HttpHeaders.SET_COOKIE,
              auth.sessionCookie(login.token(), 43200, redirectUri.startsWith("https://")),
              FLOW_COOKIE + "=; Path=/admin/auth/oidc; HttpOnly; SameSite=Lax; Max-Age=0")
          .build();
    } catch (GatewayException e) {
      throw e;
    } catch (Exception e) {
      throw failed();
    }
  }

  private UUID resolveUser(Jwt jwt) {
    db.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtext(?))",
        Object.class,
        "oidc:" + issuer + ":" + jwt.getSubject());
    var bound =
        db.query(
            "SELECT f.user_id FROM gateway_federated_identity f JOIN gateway_user u ON"
                + " u.id=f.user_id WHERE f.issuer=? AND f.subject=? AND u.status='active'",
            (rs, i) -> rs.getObject(1, UUID.class),
            issuer,
            jwt.getSubject());
    if (!bound.isEmpty()) return bound.getFirst();
    if (db.queryForObject(
            "SELECT count(*) FROM gateway_federated_identity WHERE issuer=? AND subject=?",
            Integer.class,
            issuer,
            jwt.getSubject())
        > 0) throw failed();
    String email = jwt.getClaimAsString("email");
    if (!Boolean.TRUE.equals(jwt.getClaim("email_verified"))
        || email == null
        || email.length() > 320
        || !email.matches("[^\\s@]+@[^\\s@]+")) throw failed();
    db.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtext(?))",
        Object.class,
        "oidc-email:" + email.toLowerCase(Locale.ROOT));
    var users =
        db.queryForList(
            "SELECT id,role,status FROM gateway_user WHERE lower(email)=lower(?) FOR UPDATE",
            email);
    UUID id;
    if (!users.isEmpty()) {
      if (users.size() != 1) throw failed();
      var u = users.getFirst();
      id = (UUID) u.get("id");
      if ("admin".equals(u.get("role"))
          || !"active".equals(u.get("status"))
          || db.queryForObject(
                  "SELECT count(*) FROM gateway_federated_identity WHERE user_id=?",
                  Integer.class,
                  id)
              > 0) throw failed();
    } else {
      if (!provision) throw failed();
      String username = jwt.getClaimAsString("preferred_username");
      if (username == null || !username.matches("[A-Za-z0-9_.-]{3,64}")) throw failed();
      if (db.queryForObject(
              "SELECT count(*) FROM gateway_user WHERE lower(username)=lower(?)",
              Integer.class,
              username)
          > 0) throw failed();
      id = UUID.randomUUID();
      db.update(
          "INSERT INTO gateway_user(id,username,email,password_hash,role,status) VALUES"
              + " (?,?,?,?,'user','active')",
          id,
          username,
          email,
          new BCryptPasswordEncoder(12).encode(secret()));
    }
    db.update(
        "INSERT INTO gateway_federated_identity(issuer,subject,user_id) VALUES (?,?,?)",
        issuer,
        jwt.getSubject(),
        id);
    return id;
  }

  private JsonNode discovery() {
    try {
      JsonNode node =
          http.get()
              .uri(endpoint(issuer.replaceAll("/+$", "") + "/.well-known/openid-configuration"))
              .retrieve()
              .bodyToMono(JsonNode.class)
              .timeout(Duration.ofSeconds(10))
              .block();
      if (node == null || !issuer.equals(node.path("issuer").asText())) throw failed();
      return node;
    } catch (Exception e) {
      throw failed();
    }
  }

  private URI endpoint(String value) {
    try {
      URI uri = URI.create(value);
      if (uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getFragment() != null
          || !"https".equals(uri.getScheme())
              && !(allowLocal
                  && "http".equals(uri.getScheme())
                  && Set.of("localhost", "127.0.0.1", "::1").contains(uri.getHost())))
        throw failed();
      return uri;
    } catch (Exception e) {
      throw failed();
    }
  }

  private void requireEnabled() {
    if (!enabled())
      throw new GatewayException(
          HttpStatus.NOT_FOUND,
          "not_found_error",
          "oidc_disabled",
          "Enterprise login is not configured");
  }

  private static String secret() {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(SecureRandomHolder.RANDOM.generateSeed(32));
  }

  private static String challenge(String value) {
    try {
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(StandardCharsets.US_ASCII)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String digest(String value) {
    return challenge(value);
  }

  private static boolean constant(String a, String b) {
    return a != null
        && MessageDigest.isEqual(
            a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }

  private static GatewayException failed() {
    return new GatewayException(
        HttpStatus.UNAUTHORIZED,
        "authentication_error",
        "oidc_failed",
        "Enterprise sign-in could not be verified; start a new sign-in");
  }

  private static final class SecureRandomHolder {
    static final SecureRandom RANDOM = new SecureRandom();
  }
}
