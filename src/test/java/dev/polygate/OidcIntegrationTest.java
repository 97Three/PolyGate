package dev.polygate;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "POLYGATE_TEST_PG_URL", matches = "jdbc:postgresql:.*")
@DirtiesContext
class OidcIntegrationTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final AtomicReference<String> NONCE = new AtomicReference<>(),
      CHALLENGE = new AtomicReference<>();
  static final AtomicReference<String> MODE = new AtomicReference<>("valid");
  static final AtomicReference<String> SUBJECT = new AtomicReference<>(),
      EMAIL = new AtomicReference<>();
  static HttpServer issuerServer;
  static RSAKey key;
  static String issuer;
  static final HttpClient HTTP =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
  @LocalServerPort int port;
  @Autowired JdbcTemplate db;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) throws Exception {
    key = new RSAKeyGenerator(2048).keyID("java-oidc-fixture").generate();
    issuerServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    issuer = "http://127.0.0.1:" + issuerServer.getAddress().getPort();
    issuerServer.createContext(
        "/.well-known/openid-configuration",
        e ->
            reply(
                e,
                Map.of(
                    "issuer",
                    issuer,
                    "authorization_endpoint",
                    issuer + "/authorize",
                    "token_endpoint",
                    issuer + "/token",
                    "jwks_uri",
                    issuer + "/jwks")));
    issuerServer.createContext(
        "/jwks", e -> reply(e, Map.of("keys", List.of(key.toPublicJWK().toJSONObject()))));
    issuerServer.createContext(
        "/token",
        e -> {
          try {
            var form = query(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String verifier = form.get("code_verifier");
            String challenge =
                Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                        java.security.MessageDigest.getInstance("SHA-256")
                            .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
            if (!challenge.equals(CHALLENGE.get())) throw new IllegalArgumentException();
            Instant now = Instant.now();
            var claims =
                new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .subject(SUBJECT.get())
                    .audience("audience".equals(MODE.get()) ? "wrong-client" : "java-test-client")
                    .issueTime(Date.from(now))
                    .expirationTime(
                        Date.from(
                            "expired".equals(MODE.get())
                                ? now.minusSeconds(3600)
                                : now.plusSeconds(300)))
                    .claim("nonce", "nonce".equals(MODE.get()) ? "wrong-nonce" : NONCE.get())
                    .claim("email", EMAIL.get())
                    .claim("email_verified", !"unverified".equals(MODE.get()))
                    .claim("preferred_username", "oidc_" + SUBJECT.get())
                    .build();
            var jwt =
                new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                    claims);
            jwt.sign(new RSASSASigner(key));
            reply(e, Map.of("id_token", jwt.serialize(), "token_type", "Bearer"));
          } catch (Exception x) {
            e.sendResponseHeaders(400, -1);
            e.close();
          }
        });
    issuerServer.start();
    r.add("polygate.database.enabled", () -> true);
    r.add("polygate.database.url", () -> System.getenv("POLYGATE_TEST_PG_URL"));
    r.add("polygate.database.user", () -> "postgres");
    r.add("polygate.database.password", () -> "");
    r.add("polygate.admin-bootstrap-password", () -> "Isolated-oidc-test-admin-password");
    r.add("POLYGATE_OIDC_ISSUER", () -> issuer);
    r.add("POLYGATE_OIDC_CLIENT_ID", () -> "java-test-client");
    r.add("POLYGATE_OIDC_REDIRECT_URI", () -> "http://127.0.0.1/callback");
    r.add("POLYGATE_OIDC_ALLOW_LOCAL_HTTP", () -> true);
    r.add("POLYGATE_OIDC_AUTO_PROVISION", () -> true);
  }

  @BeforeEach
  void fixture() {
    MODE.set("valid");
    String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    SUBJECT.set(id);
    EMAIL.set(id + "@example.test");
  }

  @AfterAll
  static void cleanup() {
    if (issuerServer != null) issuerServer.stop(0);
  }

  @Test
  void publishesConfiguredCapability() throws Exception {
    var r = get("/admin/auth/methods", null);
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(r.body()).path("oidc").path("enabled").asBoolean()).isTrue();
  }

  @Test
  void completesPkceNonceAndSignedIdentityFlowAndRejectsReplay() throws Exception {
    Flow flow = start();
    assertThat(flow.parameters.get("code_challenge_method")).isEqualTo("S256");
    assertThat(flow.parameters).doesNotContainKey("code_verifier");
    var callback = get(flow.callback, flow.cookie);
    assertThat(callback.statusCode()).isEqualTo(302);
    assertThat(callback.headers().firstValue("Location")).contains("/dashboard");
    String cookie =
        callback.headers().allValues("Set-Cookie").stream()
            .filter(c -> c.startsWith(AdminAuthService.COOKIE + "="))
            .findFirst()
            .orElseThrow()
            .split(";", 2)[0];
    var me = get("/admin/auth/me", cookie);
    assertThat(me.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(me.body()).path("role").asText()).isEqualTo("user");
    assertThat(get(flow.callback, flow.cookie).statusCode()).isEqualTo(401);
  }

  @Test
  void browserBindingPreventsAnotherBrowserConsumingTheLogin() throws Exception {
    Flow flow = start();
    assertThat(get(flow.callback, OidcController.FLOW_COOKIE + "=different-browser").statusCode())
        .isEqualTo(401);
    assertThat(get(flow.callback, flow.cookie).statusCode()).isEqualTo(302);
  }

  @Test
  void wrongNonceAudienceExpiryAndUnverifiedEmailCannotCreateSessions() throws Exception {
    for (String mode : List.of("nonce", "audience", "expired", "unverified")) {
      MODE.set(mode);
      Flow flow = start();
      long sessions = db.queryForObject("SELECT count(*) FROM gateway_session", Long.class);
      assertThat(get(flow.callback, flow.cookie).statusCode()).as(mode).isEqualTo(401);
      assertThat(db.queryForObject("SELECT count(*) FROM gateway_session", Long.class))
          .isEqualTo(sessions);
    }
  }

  @Test
  void verifiedEmailCannotBindAnExistingAdministrator() throws Exception {
    String email = "admin-protected@example.test";
    db.update("UPDATE gateway_user SET email=? WHERE username='admin'", email);
    EMAIL.set(email);
    Flow flow = start();
    assertThat(get(flow.callback, flow.cookie).statusCode()).isEqualTo(401);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM gateway_federated_identity f JOIN gateway_user u ON"
                    + " u.id=f.user_id WHERE u.role='admin'",
                Long.class))
        .isZero();
  }

  @Test
  void disabledBoundUserCannotBeReprovisioned() throws Exception {
    Flow flow = start();
    assertThat(get(flow.callback, flow.cookie).statusCode()).isEqualTo(302);
    db.update("UPDATE gateway_user SET status='disabled' WHERE email=?", EMAIL.get());
    flow = start();
    assertThat(get(flow.callback, flow.cookie).statusCode()).isEqualTo(401);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM gateway_user WHERE email=?", Long.class, EMAIL.get()))
        .isEqualTo(1);
  }

  private Flow start() throws Exception {
    var r = get("/admin/auth/oidc/start?returnTo=https://outside.example", null);
    assertThat(r.statusCode()).isEqualTo(302);
    var values = query(URI.create(r.headers().firstValue("Location").orElseThrow()).getRawQuery());
    NONCE.set(values.get("nonce"));
    CHALLENGE.set(values.get("code_challenge"));
    String cookie = r.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    return new Flow(
        values, cookie, "/admin/auth/oidc/callback?code=fixture-code&state=" + values.get("state"));
  }

  private HttpResponse<String> get(String path, String cookie) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
    if (cookie != null) request.header("Cookie", cookie);
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static Map<String, String> query(String query) {
    Map<String, String> out = new HashMap<>();
    for (String entry : query.split("&")) {
      var pair = entry.split("=", 2);
      out.put(
          URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
          pair.length > 1 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
    }
    return out;
  }

  private static void reply(HttpExchange exchange, Object value) throws java.io.IOException {
    byte[] data = JSON.writeValueAsBytes(value);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, data.length);
    exchange.getResponseBody().write(data);
    exchange.close();
  }

  private record Flow(Map<String, String> parameters, String cookie, String callback) {}
}
