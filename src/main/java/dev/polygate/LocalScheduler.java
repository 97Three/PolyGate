package dev.polygate;

import java.util.*;
import org.springframework.http.*;
import org.springframework.stereotype.Service;

/** One local inference execution slot, bounded per-user queues, interactive work before batch. */
@Service
class LocalScheduler {
  private final Map<UUID, Lease> running = new HashMap<>();
  private final LinkedList<Ticket> interactive = new LinkedList<>(), batch = new LinkedList<>();
  private long serviceMs = 1000;

  synchronized void acquire(
      UUID request,
      ClientAuthService.Principal principal,
      RoutingService.Route route,
      String traffic,
      HttpHeaders headers) {
    if (!GovernanceService.local(route)) return;
    if (running.containsKey(request)) return;
    String user =
        Objects.toString(principal.userId(), Objects.toString(principal.apiKeyId(), "legacy"));
    if (running.isEmpty() && interactive.isEmpty() && batch.isEmpty()) {
      running.put(request, new Lease(user, System.nanoTime()));
      return;
    }
    long userQueued =
        interactive.stream().filter(t -> t.user.equals(user)).count()
            + batch.stream().filter(t -> t.user.equals(user)).count();
    boolean isBatch = "batch".equals(traffic);
    var queue = isBatch ? batch : interactive;
    if (userQueued >= 2 || queue.size() >= 16)
      throw limited("Local inference queue is full; retry later");
    Ticket ticket = new Ticket(request, user, System.nanoTime());
    queue.add(ticket);
    long wait =
        "local_strict"
                .equals(
                    route
                        .config()
                        .path("_effectiveHybridMode")
                        .asText(headers.getFirst("x-polygate-hybrid-mode")))
            ? 60_000
            : 5_000;
    long deadline = System.nanoTime() + wait * 1_000_000L;
    try {
      while (true) {
        if (running.isEmpty()
            && queue.peekFirst() == ticket
            && (!isBatch || interactive.isEmpty())) {
          queue.removeFirst();
          running.put(request, new Lease(user, System.nanoTime()));
          return;
        }
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw limited("Local inference queue wait expired; retry later");
        try {
          wait(Math.max(1, remaining / 1_000_000L));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw limited("Queued request was interrupted");
        }
      }
    } finally {
      queue.remove(ticket);
      notifyAll();
    }
  }

  synchronized void release(UUID request) {
    Lease lease = running.remove(request);
    if (lease != null) {
      long elapsed = Math.max(1, (System.nanoTime() - lease.started) / 1_000_000L);
      serviceMs = Math.round(.8 * serviceMs + .2 * elapsed);
      notifyAll();
    }
  }

  synchronized Map<String, Object> snapshot() {
    return Map.of(
        "running",
        running.size(),
        "interactiveQueued",
        interactive.size(),
        "batchQueued",
        batch.size(),
        "usersQueued",
        java.util.stream.Stream.concat(interactive.stream(), batch.stream())
            .map(t -> t.user)
            .distinct()
            .count(),
        "estimatedServiceMs",
        serviceMs,
        "oldestInteractiveWaitMs",
        age(interactive),
        "oldestBatchWaitMs",
        age(batch),
        "limits",
        Map.of(
            "executingPerUser",
            1,
            "queuedPerUser",
            2,
            "globalInteractiveQueue",
            16,
            "globalBatchQueue",
            16,
            "overflowAfterSeconds",
            5,
            "strictWaitSeconds",
            60));
  }

  private static long age(LinkedList<Ticket> queue) {
    return queue.isEmpty() ? 0 : (System.nanoTime() - queue.getFirst().started) / 1_000_000L;
  }

  private record Lease(String user, long started) {}

  private record Ticket(UUID request, String user, long started) {}

  private static GatewayException limited(String message) {
    return new GatewayException(
        HttpStatus.TOO_MANY_REQUESTS, "rate_limit_error", "local_admission_limited", message);
  }
}
