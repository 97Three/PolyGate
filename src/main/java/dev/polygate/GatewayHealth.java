package dev.polygate;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component("gateway")
class GatewayHealth implements HealthIndicator {
  private final GatewaySettings settings;
  private final JdbcTemplate database;
  private final RoutingService routes;

  GatewayHealth(
      GatewaySettings settings, ObjectProvider<JdbcTemplate> database, RoutingService routes) {
    this.settings = settings;
    this.database = database.getIfAvailable();
    this.routes = routes;
  }

  @Override
  public Health health() {
    try {
      boolean hasClientCredential = !settings.clientToken.isBlank();
      if (!hasClientCredential && database != null) {
        Integer keys =
            database.queryForObject(
                "SELECT count(*) FROM gateway_api_key k WHERE enabled=true AND"
                    + " rotation_state='active' AND COALESCE(purpose,'')<>'polygate_ops_agent' AND"
                    + " (expires_at IS NULL OR expires_at > now()) AND EXISTS(SELECT 1 FROM"
                    + " gateway_user u WHERE u.id=k.user_id AND u.status='active')",
                Integer.class);
        hasClientCredential = keys != null && keys > 0;
      }
      boolean upstream = database == null && !settings.upstreamKey.isBlank();
      if (database != null) {
        database.queryForObject("SELECT 1", Integer.class);
        for (var model : routes.models())
          try {
            var route = routes.resolve(model.get("id"));
            if (!route.keyRequired() || !route.upstreamKey().isBlank()) upstream = true;
          } catch (GatewayException ignored) {
          }
      }
      return !hasClientCredential || !upstream
          ? Health.down().withDetail("configuration", "credentials missing").build()
          : Health.up().build();
    } catch (RuntimeException error) {
      return Health.down().withDetail("storage", "unavailable").build();
    }
  }
}
