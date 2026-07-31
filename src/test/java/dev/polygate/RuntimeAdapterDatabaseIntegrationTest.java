package dev.polygate;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "POLYGATE_TEST_PG_URL", matches = "jdbc:postgresql:.*")
@DirtiesContext
class RuntimeAdapterDatabaseIntegrationTest {
  @Autowired RuntimeAdapterController adapters;
  @Autowired AdminAuthService auth;
  @Autowired JdbcTemplate db;
  @Autowired ConfigurationService config;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("polygate.database.enabled", () -> true);
    r.add("polygate.database.url", () -> System.getenv("POLYGATE_TEST_PG_URL"));
    r.add("polygate.database.user", () -> "postgres");
    r.add("polygate.database.password", () -> "");
    r.add("polygate.admin-bootstrap-password", () -> "Isolated-oidc-test-admin-password");
  }

  @Test
  void authenticatesQueriesPersistsAndMarksStale() throws Exception {
    ObjectMapper json = new ObjectMapper();
    String id = "qwen-llama-cpp-reference";
    var caps =
        json.readTree(
            getClass()
                .getResourceAsStream(
                    "/runtime-adapters/qwen-llama-cpp-capabilities-v1alpha1.json"));
    var inventory =
        (ObjectNode)
            json.readTree(
                getClass()
                    .getResourceAsStream(
                        "/runtime-adapters/qwen-llama-cpp-compute-inventory-v1alpha1.json"));
    ((ObjectNode) inventory.path("metadata")).put("observedAt", Instant.now().toString());
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/runtime-adapter/v1alpha1/",
        exchange -> {
          boolean allowed =
              ("Bearer " + System.getenv("POLYGATE_TEST_ADAPTER_TOKEN"))
                  .equals(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] data =
              (exchange.getRequestURI().getPath().endsWith("/capabilities") ? caps : inventory)
                  .toString()
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(allowed ? 200 : 401, data.length);
          exchange.getResponseBody().write(data);
          exchange.close();
        });
    server.start();
    var login = auth.login("admin", "Isolated-oidc-test-admin-password");
    HttpHeaders h = new HttpHeaders();
    h.add(HttpHeaders.COOKIE, AdminAuthService.COOKIE + "=" + login.token());
    h.add("x-polygate-csrf", "1");
    var before = config.read("runtime_adapters");
    try {
      var body = json.createObjectNode();
      body.putArray("adapters")
          .addObject()
          .put("id", id)
          .put("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort())
          .put("bearerTokenEnv", "POLYGATE_TEST_ADAPTER_TOKEN")
          .put("staleAfterSeconds", 60);
      adapters.update(h, body);
      adapters.refresh(id);
      JsonNode result = json.valueToTree(adapters.snapshot(h, id));
      assertThat(result.path("status").asText()).isEqualTo("fresh");
      assertThat(result.path("inventory").path("nodes").size()).isEqualTo(1);
      db.update(
          "UPDATE gateway_runtime_observation SET observed_at=now()-interval '5 minutes' WHERE"
              + " adapter_id=?",
          id);
      result = json.valueToTree(adapters.snapshot(h, id));
      assertThat(result.path("status").asText()).isEqualTo("stale");
      server.stop(0);
      assertThatThrownBy(() -> adapters.refresh(id)).isInstanceOf(GatewayException.class);
      result = json.valueToTree(adapters.snapshot(h, id));
      assertThat(result.path("status").asText()).isEqualTo("unavailable");
      assertThat(result.path("inventory").path("nodes").size()).isEqualTo(1);
    } finally {
      server.stop(0);
      db.update("DELETE FROM gateway_runtime_observation WHERE adapter_id=?", id);
      config.write("runtime_adapters", before);
      auth.logout(h);
    }
  }
}
