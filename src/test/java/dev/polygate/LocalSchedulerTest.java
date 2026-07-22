package dev.polygate;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class LocalSchedulerTest {
  private static RoutingService.Route route() {
    return new RoutingService.Route(
        "local:test",
        "local",
        "test",
        URI.create("http://127.0.0.1"),
        "",
        "anthropic",
        JsonNodeFactory.instance.objectNode());
  }

  private static ClientAuthService.Principal user() {
    return new ClientAuthService.Principal(UUID.randomUUID(), null, List.of());
  }

  @Test
  void onlyOneExecutionAndTwoQueuedRequestsPerUser() throws Exception {
    var scheduler = new LocalScheduler();
    var user = user();
    UUID first = UUID.randomUUID(), second = UUID.randomUUID(), third = UUID.randomUUID();
    scheduler.acquire(first, user, route(), "business", new HttpHeaders());
    try (var threads = Executors.newFixedThreadPool(2)) {
      var b =
          threads.submit(
              () -> scheduler.acquire(second, user, route(), "business", new HttpHeaders()));
      awaitQueue(scheduler, 1);
      var c =
          threads.submit(
              () -> scheduler.acquire(third, user, route(), "business", new HttpHeaders()));
      awaitQueue(scheduler, 2);
      assertThatThrownBy(
              () ->
                  scheduler.acquire(
                      UUID.randomUUID(), user, route(), "business", new HttpHeaders()))
          .isInstanceOf(GatewayException.class);
      scheduler.release(first);
      b.get(1, TimeUnit.SECONDS);
      assertThat(c.isDone()).isFalse();
      scheduler.release(second);
      c.get(1, TimeUnit.SECONDS);
      scheduler.release(third);
      assertThat(scheduler.snapshot().get("running")).isEqualTo(0);
    } finally {
      scheduler.release(first);
      scheduler.release(second);
      scheduler.release(third);
    }
  }

  @Test
  void interactiveWorkPrecedesEarlierBatchWork() throws Exception {
    var scheduler = new LocalScheduler();
    UUID first = UUID.randomUUID(), batch = UUID.randomUUID(), interactive = UUID.randomUUID();
    scheduler.acquire(first, user(), route(), "business", new HttpHeaders());
    try (var threads = Executors.newFixedThreadPool(2)) {
      var b =
          threads.submit(
              () -> scheduler.acquire(batch, user(), route(), "batch", new HttpHeaders()));
      awaitQueue(scheduler, 1);
      var i =
          threads.submit(
              () -> scheduler.acquire(interactive, user(), route(), "business", new HttpHeaders()));
      awaitQueue(scheduler, 2);
      scheduler.release(first);
      i.get(1, TimeUnit.SECONDS);
      assertThat(b.isDone()).isFalse();
      scheduler.release(interactive);
      b.get(1, TimeUnit.SECONDS);
      scheduler.release(batch);
    } finally {
      scheduler.release(first);
      scheduler.release(batch);
      scheduler.release(interactive);
    }
  }

  @Test
  void cancellationRemovesQueuedWork() throws Exception {
    var scheduler = new LocalScheduler();
    UUID first = UUID.randomUUID();
    scheduler.acquire(first, user(), route(), "business", new HttpHeaders());
    try (var threads = Executors.newSingleThreadExecutor()) {
      var pending =
          threads.submit(
              () ->
                  scheduler.acquire(
                      UUID.randomUUID(), user(), route(), "batch", new HttpHeaders()));
      awaitQueue(scheduler, 1);
      pending.cancel(true);
    } finally {
      scheduler.release(first);
    }
    assertThat(scheduler.snapshot().get("batchQueued")).isEqualTo(0);
  }

  private static void awaitQueue(LocalScheduler scheduler, int expected) throws Exception {
    long until = System.nanoTime() + 1_000_000_000L;
    while (System.nanoTime() < until) {
      var s = scheduler.snapshot();
      if (((Number) s.get("interactiveQueued")).intValue()
              + ((Number) s.get("batchQueued")).intValue()
          == expected) return;
      Thread.sleep(5);
    }
    throw new AssertionError("Queue did not reach expected state");
  }
}
