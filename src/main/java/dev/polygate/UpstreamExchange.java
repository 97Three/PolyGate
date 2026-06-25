package dev.polygate;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Pins eligible routes and price snapshots at admission. Fallback stops before any response body.
 */
@Service
class UpstreamExchange {
  private final RoutingService routes;
  private final ClientAuthService auth;
  private final GovernanceService governance;
  private final PricingService pricing;
  private final LocalScheduler scheduler;
  private final ObjectMapper json;
  private final Map<UUID, Plan> active = new ConcurrentHashMap<>();

  UpstreamExchange(
      RoutingService routes,
      ClientAuthService auth,
      GovernanceService governance,
      PricingService pricing,
      LocalScheduler scheduler,
      ObjectMapper json) {
    this.routes = routes;
    this.auth = auth;
    this.governance = governance;
    this.pricing = pricing;
    this.scheduler = scheduler;
    this.json = json;
  }

  List<Prepared> prepare(
      RoutingService.Route first,
      ClientAuthService.Principal principal,
      HttpHeaders headers,
      JsonNode request) {
    boolean standard =
        Set.of("standard", "default", "auto")
            .contains(request.path("service_tier").asText("standard"));
    standard = standard && !unpriced(request);
    List<Prepared> result = new ArrayList<>();
    result.add(
        new Prepared(
            first, standard ? pricing.card(first.providerId(), first.upstreamModel()) : null));
    // Media admission is tokenizer-specific; never apply one provider's count to a different one.
    if (InputAccounting.media(request)) return result;
    for (var candidate : routes.fallbacks(first))
      try {
        auth.authorizeRoute(principal, candidate);
        governance.enforce(principal, candidate, headers);
        ModelCapabilities.enforce(request, candidate);
        new ToolResponsePolicy(request, candidate, json, "anthropic".equals(candidate.protocol()));
        result.add(
            new Prepared(
                candidate,
                standard ? pricing.card(candidate.providerId(), candidate.upstreamModel()) : null));
      } catch (GatewayException ignored) {
      }
    return List.copyOf(result);
  }

  JsonNode reservationCard(List<Prepared> candidates) {
    ObjectNode maximum = JsonNodeFactory.instance.objectNode();
    for (var candidate : candidates) {
      if (candidate.card == null) return null;
      for (String field :
          List.of(
              "inputPerMillion",
              "outputPerMillion",
              "cacheReadPerMillion",
              "cacheWritePerMillion")) {
        var value = candidate.card.path(field).decimalValue();
        if (!maximum.has(field) || value.compareTo(maximum.path(field).decimalValue()) > 0)
          maximum.put(field, value);
      }
    }
    for (String field : List.of("cacheReadPerMillion", "cacheWritePerMillion"))
      if (maximum
              .path(field)
              .decimalValue()
              .compareTo(maximum.path("inputPerMillion").decimalValue())
          > 0) maximum.set("inputPerMillion", maximum.path(field));
    return maximum;
  }

  void pin(UUID id, List<Prepared> candidates, long timeoutMs) {
    active.put(id, new Plan(candidates, System.nanoTime() + timeoutMs * 1_000_000L));
  }

  void release(UUID id) {
    active.remove(id);
  }

  <T> Result<T> execute(
      UUID id,
      RoutingService.Route initial,
      ClientAuthService.Principal principal,
      HttpHeaders headers,
      JsonNode request,
      RequestLogService logs,
      boolean fallback,
      Function<RoutingService.Route, Mono<T>> call) {
    Plan plan = active.get(id);
    if (plan == null) throw new IllegalStateException("Request routing plan is unavailable");
    int start = 0;
    for (int i = 0; i < plan.candidates.size(); i++)
      if (same(initial, plan.candidates.get(i).route)) {
        start = i;
        break;
      }
    GatewayException last = null;
    for (int i = start; i < plan.candidates.size() && (fallback || i == start); i++) {
      Prepared prepared = plan.candidates.get(i);
      RoutingService.Route candidate = i == start ? initial : prepared.route;
      try {
        auth.authorizeRoute(principal, candidate);
        governance.enforce(principal, candidate, headers);
        ModelCapabilities.enforce(request, candidate);
        if (i != start) candidate = routes.forRequest(candidate);
        if (GovernanceService.local(candidate))
          scheduler.acquire(
              id, principal, candidate, headers.getFirst("x-polygate-traffic-class"), headers);
        else scheduler.release(id);
      } catch (GatewayException denied) {
        last = new BeforeDispatch(denied);
        continue;
      }
      Set<String> rejectedCredentials = new HashSet<>();
      for (int credentialPass = 0; credentialPass < 5; credentialPass++) {
        if (candidate.credentialId() != null) rejectedCredentials.add(candidate.credentialId());
        logs.dispatch(id, candidate, prepared.card);
        long remaining = Math.max(0, (plan.deadline - System.nanoTime()) / 1_000_000L);
        if (remaining <= 0)
          throw new BeforeDispatch(
              new GatewayException(
                  HttpStatus.GATEWAY_TIMEOUT,
                  "upstream_error",
                  "upstream_timeout",
                  "Request deadline expired before dispatch"));
        try {
          T response =
              UpstreamRetries.apply(call.apply(candidate), candidate, id, logs)
                  .timeout(Duration.ofMillis(Math.min(remaining, candidate.requestTimeoutMs())))
                  .block();
          return new Result<>(
              response, candidate, Math.max(1, (plan.deadline - System.nanoTime()) / 1_000_000L));
        } catch (RuntimeException failure) {
          GatewayException safe = UpstreamFailures.safe(failure);
          last = safe;
          if (credentialPass < 4 && Set.of(401, 402, 403, 429, 503).contains(safe.upstreamStatus)) {
            RoutingService.Route alternative =
                routes.nextCredential(candidate, rejectedCredentials);
            while (alternative != null) {
              try {
                auth.authorizeRoute(principal, alternative);
                governance.enforce(principal, alternative, headers);
                if (GovernanceService.local(alternative))
                  scheduler.acquire(
                      id,
                      principal,
                      alternative,
                      headers.getFirst("x-polygate-traffic-class"),
                      headers);
                else scheduler.release(id);
                break;
              } catch (GatewayException denied) {
                rejectedCredentials.add(alternative.credentialId());
                alternative = routes.nextCredential(candidate, rejectedCredentials);
              }
            }
            if (alternative != null) {
              logs.retrying(id, safe.upstreamStatus);
              candidate = alternative;
              continue;
            }
          }
          if (!fallback
              || i + 1 >= plan.candidates.size()
              || !Set.of(401, 402, 403, 429, 503).contains(safe.upstreamStatus)) throw safe;
          logs.retrying(id, safe.upstreamStatus);
          break;
        }
      }
    }
    if (last != null) throw last;
    throw new BeforeDispatch(
        new GatewayException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "server_error",
            "no_eligible_route",
            "No eligible fallback route remains"));
  }

  private static boolean same(RoutingService.Route a, RoutingService.Route b) {
    return a.providerId().equals(b.providerId()) && a.upstreamModel().equals(b.upstreamModel());
  }

  record Prepared(RoutingService.Route route, JsonNode card) {}

  record Plan(List<Prepared> candidates, long deadline) {}

  record Result<T>(T value, RoutingService.Route route, long remainingMs) {}

  static final class BeforeDispatch extends GatewayException {
    BeforeDispatch(GatewayException error) {
      super(error.status, error.type, error.code, error.getMessage());
    }
  }

  private static boolean unpriced(JsonNode input) {
    if (input.has("audio") || input.has("prediction") || input.has("prompt_cache_retention"))
      return true;
    for (JsonNode modality : input.path("modalities"))
      if (!"text".equals(modality.asText())) return true;
    for (JsonNode tool : input.path("tools"))
      if (tool.has("type") && !"function".equals(tool.path("type").asText())) return true;
    return specialMedia(input.path("messages"));
  }

  private static boolean specialMedia(JsonNode input) {
    if (Set.of("input_audio", "audio", "video", "input_video")
        .contains(input.path("type").asText())) return true;
    if (input.isContainerNode()) for (JsonNode child : input) if (specialMedia(child)) return true;
    return false;
  }
}
