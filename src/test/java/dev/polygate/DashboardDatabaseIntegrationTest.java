package dev.polygate;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "POLYGATE_TEST_PG_URL", matches = "jdbc:postgresql:.*")
@DirtiesContext
class DashboardDatabaseIntegrationTest {
  @Autowired JdbcTemplate db;
  @Autowired AdminAuthService auth;
  @Autowired AdminDashboardController dashboard;
  @Autowired AdminLogsController logs;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("polygate.database.enabled", () -> true);
    r.add("polygate.database.url", () -> System.getenv("POLYGATE_TEST_PG_URL"));
    r.add("polygate.database.user", () -> "postgres");
    r.add("polygate.database.password", () -> "");
    r.add("polygate.admin-bootstrap-password", () -> "Isolated-oidc-test-admin-password");
  }

  @Test
  void rejectedRequestWithoutProviderRemainsVisibleInDashboard() {
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO gateway_request(id,client_protocol,requested_model,state,http_status) VALUES"
            + " (?,'anthropic','legacy-unresolved','failed',400)",
        id);
    var login = auth.login("admin", "Isolated-oidc-test-admin-password");
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.COOKIE, AdminAuthService.COOKIE + "=" + login.token());
    try {
      var result = new ObjectMapper().valueToTree(dashboard.dashboard(headers, "1d", null, null));
      assertThat(result.path("totalRequests").asLong()).isPositive();
      boolean found = false;
      for (var model : result.path("modelUsage")) {
        if (model.path("model").asText().equals("legacy-unresolved")) {
          assertThat(model.path("provider").asText()).isEqualTo("unresolved");
          assertThat(model.path("requests").asLong()).isEqualTo(1);
          found = true;
        }
      }
      assertThat(found).isTrue();
    } finally {
      db.update("DELETE FROM gateway_request WHERE id=?", id);
      auth.logout(headers);
    }
  }

  @Test
  void completedTerminalReasonIsNotAnErrorAndPredispatchFailureRemainsReadable() {
    UUID success = UUID.randomUUID(), failure = UUID.randomUUID();
    db.update(
        "INSERT INTO"
            + " gateway_request(id,client_protocol,requested_model,state,http_status,safe_error_code)"
            + " VALUES"
            + " (?,'anthropic','legacy-success','completed',200,'completed'),(?,'anthropic','legacy-rejected','failed',400,'failed_before_response')",
        success,
        failure);
    var login = auth.login("admin", "Isolated-oidc-test-admin-password");
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.COOKIE, AdminAuthService.COOKIE + "=" + login.token());
    try {
      var completed = logs.byId(headers, success);
      assertThat(completed.get("errorMessage")).isNull();
      assertThat(completed.get("terminalReason")).isEqualTo("completed");
      var rejected = logs.byId(headers, failure);
      assertThat(rejected.get("provider")).isNull();
      assertThat(rejected.get("errorMessage")).isEqualTo("failed_before_response");
    } finally {
      db.update("DELETE FROM gateway_request WHERE id IN (?,?)", success, failure);
      auth.logout(headers);
    }
  }
}
