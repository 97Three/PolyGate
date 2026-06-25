package dev.polygate;

import java.lang.management.ManagementFactory;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
class HealthEndpoints {
  private final GatewayHealth health;
  private final ClientAuthService auth;
  private final JdbcTemplate db;
  private final GatewayTrafficFilter traffic;

  HealthEndpoints(
      GatewayHealth health,
      ClientAuthService auth,
      ObjectProvider<JdbcTemplate> db,
      GatewayTrafficFilter traffic) {
    this.health = health;
    this.auth = auth;
    this.db = db.getIfAvailable();
    this.traffic = traffic;
  }

  @GetMapping("/livez")
  Object live() {
    return Map.of("status", "ok", "service", "polygate");
  }

  @GetMapping({"/readyz", "/health"})
  Object ready(@RequestHeader HttpHeaders headers) {
    auth.authenticate(headers, null);
    boolean ready = "UP".equals(health.health().getStatus().getCode());
    if (!ready)
      throw new GatewayException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "server_error",
          "not_ready",
          "Gateway storage or credentials are not ready");
    return Map.of(
        "status", "ok", "service", "polygate", "database", db == null ? "disabled" : "ready");
  }

  @GetMapping(value = "/metrics", produces = "text/plain;version=0.0.4;charset=utf-8")
  String metrics(@RequestHeader HttpHeaders headers) {
    auth.authenticate(headers, null);
    StringBuilder out =
        new StringBuilder("# TYPE polygate_active_requests gauge\npolygate_active_requests ")
            .append(traffic.activeCount())
            .append("\n# TYPE process_uptime_seconds gauge\nprocess_uptime_seconds ")
            .append(ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0)
            .append('\n');
    if (db != null) {
      out.append("# TYPE polygate_requests_total counter\n");
      db.query(
          "SELECT state,count(*) amount FROM gateway_request GROUP BY state",
          (org.springframework.jdbc.core.RowCallbackHandler)
              rs ->
                  out.append("polygate_requests_total{state=\"")
                      .append(rs.getString("state"))
                      .append("\"} ")
                      .append(rs.getLong("amount"))
                      .append('\n'));
      var tokens =
          db.queryForMap(
              "SELECT COALESCE(sum(input_tokens),0) input,COALESCE(sum(output_tokens),0)"
                  + " output,COALESCE(sum(cache_read_tokens),0)"
                  + " cache_read,COALESCE(sum(cache_write_tokens),0) cache_write FROM"
                  + " gateway_usage");
      out.append("# TYPE polygate_tokens_total counter\n");
      for (String type : List.of("input", "output", "cache_read", "cache_write"))
        out.append("polygate_tokens_total{type=\"")
            .append(type)
            .append("\"} ")
            .append(tokens.get(type))
            .append('\n');
    }
    DetailedMetrics.append(out, db);
    return out.toString();
  }
}
