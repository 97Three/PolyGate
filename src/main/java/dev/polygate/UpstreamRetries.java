package dev.polygate;

import java.time.Duration;
import java.util.UUID;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

final class UpstreamRetries {
  private UpstreamRetries() {}

  static <T> Mono<T> apply(
      Mono<T> call, RoutingService.Route route, UUID request, RequestLogService logs) {
    var policy = route.config().path("retry");
    int attempts = policy.path("max_attempts").asInt(1);
    if (attempts <= 1) return call;
    long initial = Math.max(1, policy.path("initial_delay_ms").asLong(250)),
        maximum = Math.max(initial, policy.path("max_delay_ms").asLong(5000));
    return call.retryWhen(
        Retry.backoff(attempts - 1, Duration.ofMillis(initial))
            .maxBackoff(Duration.ofMillis(maximum))
            .jitter(policy.path("jitter_ratio").asDouble(.1))
            .filter(
                error ->
                    error instanceof GatewayException gateway
                        && (gateway.upstreamStatus == 429 || gateway.upstreamStatus == 503))
            .doBeforeRetry(
                signal ->
                    logs.retrying(request, ((GatewayException) signal.failure()).upstreamStatus))
            .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
  }
}
