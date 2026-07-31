package dev.polygate;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DatabaseIsolationTest {
  @Test
  void refusesOriginalOrCredentialBearingDatabase() {
    assertThatThrownBy(
            () -> DatabaseConfig.requireIsolatedDatabase("jdbc:postgresql://localhost/unrelated_database"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                DatabaseConfig.requireIsolatedDatabase(
                    "jdbc:postgresql://user:password@localhost/polygate_release"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsIsolatedReleaseAndRestorationTargets() {
    DatabaseConfig.requireIsolatedDatabase("jdbc:postgresql://localhost/polygate_release");
    DatabaseConfig.requireIsolatedDatabase(
        "jdbc:postgresql://localhost/polygate_restore_manual");
  }
}
