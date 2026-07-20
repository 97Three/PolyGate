package dev.polygate;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "polygate.database.enabled", havingValue = "true")
class RequestReconciler {
  private final JdbcTemplate db;
  private final RequestLogService logs;

  RequestReconciler(JdbcTemplate db, RequestLogService logs) {
    this.db = db;
    this.logs = logs;
  }

  @Scheduled(initialDelay = 60000, fixedDelay = 60000)
  void reconcile() {
    var ids =
        db.query(
            "SELECT id FROM gateway_request WHERE state='started' AND started_at<now()-interval '2"
                + " hours' ORDER BY started_at LIMIT 100",
            (rs, i) -> rs.getObject(1, UUID.class));
    for (UUID id : ids) logs.failed(id, 7200000, 504, "stale_request_reconciled");
  }
}
