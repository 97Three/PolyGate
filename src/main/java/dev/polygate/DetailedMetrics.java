package dev.polygate;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.jdbc.core.JdbcTemplate;

final class DetailedMetrics {
  static final Map<String, LongAdder> REJECTIONS = new ConcurrentHashMap<>();

  static void rejected(String code) {
    REJECTIONS.computeIfAbsent(code, k -> new LongAdder()).increment();
  }

  static String label(Object value) {
    return Objects.toString(value, "unresolved")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n");
  }

  static void append(StringBuilder out, JdbcTemplate db) {
    out.append("# TYPE polygate_rejections_total counter\n");
    REJECTIONS.forEach(
        (k, v) ->
            out.append("polygate_rejections_total{reason=\"")
                .append(label(k))
                .append("\"} ")
                .append(v.sum())
                .append('\n'));
    if (db == null) return;
    out.append("# TYPE polygate_message_latency_seconds histogram\n");
    for (double bound : new double[] {.1, .25, .5, 1, 2.5, 5, 10, 30, 60, 120}) {
      Long n =
          db.queryForObject(
              "SELECT count(*) FROM gateway_request WHERE latency_ms<=?", Long.class, bound * 1000);
      out.append("polygate_message_latency_seconds_bucket{le=\"")
          .append(bound)
          .append("\"} ")
          .append(n)
          .append('\n');
    }
    Map<String, Object> latency =
        db.queryForMap(
            "SELECT count(latency_ms) n,COALESCE(sum(latency_ms),0)/1000.0 s FROM gateway_request");
    out.append("polygate_message_latency_seconds_bucket{le=\"+Inf\"} ")
        .append(latency.get("n"))
        .append("\npolygate_message_latency_seconds_count ")
        .append(latency.get("n"))
        .append("\npolygate_message_latency_seconds_sum ")
        .append(latency.get("s"))
        .append('\n');
    for (var r :
        db.queryForList(
            "SELECT provider_id,upstream_model,traffic_class,stream,state,count(*)"
                + " n,COALESCE(sum(u.input_tokens+u.output_tokens+u.cache_read_tokens+u.cache_write_tokens),0)"
                + " tokens,COALESCE(sum(u.cost_estimate),0) cost FROM gateway_request r LEFT JOIN"
                + " gateway_usage u ON u.request_id=r.id GROUP BY"
                + " provider_id,upstream_model,traffic_class,stream,state ORDER BY n DESC LIMIT"
                + " 512")) {
      String labels =
          "{provider=\""
              + label(r.get("provider_id"))
              + "\",model=\""
              + label(r.get("upstream_model"))
              + "\",traffic_class=\""
              + label(r.get("traffic_class"))
              + "\",stream=\""
              + r.get("stream")
              + "\",state=\""
              + r.get("state")
              + "\"}";
      out.append("polygate_messages_total")
          .append(labels)
          .append(' ')
          .append(r.get("n"))
          .append('\n');
      out.append("polygate_message_tokens_total")
          .append(labels)
          .append(' ')
          .append(r.get("tokens"))
          .append('\n');
      out.append("polygate_cost_estimate_usd_total")
          .append(labels)
          .append(' ')
          .append(r.get("cost"))
          .append('\n');
    }
    for (var r :
        db.queryForList("SELECT mode,count(*) n FROM gateway_routing_decision GROUP BY mode"))
      out.append("polygate_routing_decisions_total{mode=\"")
          .append(label(r.get("mode")))
          .append("\"} ")
          .append(r.get("n"))
          .append('\n');
    for (var r :
        db.queryForList(
            "SELECT event_type,count(*) n FROM gateway_budget_event GROUP BY event_type"))
      out.append("polygate_ledger_operations_total{operation=\"")
          .append(label(r.get("event_type")))
          .append("\"} ")
          .append(r.get("n"))
          .append('\n');
    out.append("polygate_unfinished_requests ")
        .append(
            db.queryForObject(
                "SELECT count(*) FROM gateway_request WHERE state='started'", Long.class))
        .append('\n');
  }
}
