package dev.polygate;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@ConditionalOnProperty(name = "polygate.database.enabled", havingValue = "true")
class DatabaseConfig {
  @Bean(destroyMethod = "close")
  HikariDataSource polyGateDataSource(
      @Value("${polygate.database.url}") String url,
      @Value("${polygate.database.user}") String user,
      @Value("${polygate.database.password}") String password) {
    requireIsolatedDatabase(url);
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setUsername(user);
    config.setPassword(password);
    config.setMaximumPoolSize(8);
    config.setMinimumIdle(1);
    config.setPoolName("polygate");
    return new HikariDataSource(config);
  }

  @Bean
  Flyway flyway(DataSource polyGateDataSource) {
    Flyway flyway =
        Flyway.configure()
            .dataSource(polyGateDataSource)
            .locations("classpath:db/migration")
            .cleanDisabled(true)
            .load();
    flyway.migrate();
    return flyway;
  }

  @Bean
  JdbcTemplate jdbcTemplate(DataSource polyGateDataSource, Flyway flyway) {
    return new JdbcTemplate(polyGateDataSource);
  }

  static void requireIsolatedDatabase(String jdbcUrl) {
    if (!jdbcUrl.startsWith("jdbc:postgresql://")) {
      throw new IllegalArgumentException("Java storage requires a PostgreSQL JDBC URL");
    }
    URI uri = URI.create(jdbcUrl.substring(5));
    String database = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
    if (!database.matches("polygate(?:_[a-z0-9]+)*")) {
      throw new IllegalArgumentException(
          "Database name must be polygate or polygate_<suffix>; use a dedicated database");
    }
    if (uri.getUserInfo() != null || uri.getFragment() != null) {
      throw new IllegalArgumentException("Database credentials must be supplied separately");
    }
  }
}
