package dev.polygate;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.*;

@Service
class NativeOpenAiGateway {
  private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE =
      new ParameterizedTypeReference<>() {};
  private static final Set<String> FIELDS =
      Set.of(
          "model",
          "messages",
          "max_tokens",
          "max_completion_tokens",
          "stream",
          "stream_options",
          "temperature",
          "top_p",
          "top_k",
          "min_p",
          "repeat_penalty",
          "chat_template_kwargs",
          "thinking_budget_tokens",
          "enable_thinking",
          "stop",
          "tools",
          "tool_choice",
          "parallel_tool_calls",
          "n",
          "store",
          "response_format",
          "reasoning_effort",
          "thinking",
          "reasoning",
          "frequency_penalty",
          "presence_penalty",
          "seed",
          "logprobs",
          "top_logprobs",
          "logit_bias",
          "user",
          "metadata",
          "service_tier",
          "prediction",
          "modalities",
          "audio",
          "verbosity",
          "prompt_cache_key",
          "prompt_cache_retention");
  private final ClientAuthService auth;
  private final RequestLogService logs;
  private final ObjectMapper json;
  private final WebClient http;
  private final GovernanceService governance;
  private final RoutingService routes;
  private final UpstreamExchange exchange;

  NativeOpenAiGateway(
      ClientAuthService auth,
      RequestLogService logs,
      ObjectMapper json,
      WebClient.Builder builder,
      GovernanceService governance,
      RoutingService routes,
      UpstreamExchange exchange) {
    this.auth = auth;
    this.logs = logs;
    this.json = json;
    http = builder.build();
    this.governance = governance;
    this.routes = routes;
    this.exchange = exchange;
  }

  ResponseEntity<?> forward(HttpHeaders headers, JsonNode input, RoutingService.Route route) {
    return forward(headers, input, route, "openai");
  }

  ResponseEntity<?> forward(
      HttpHeaders headers, JsonNode input, RoutingService.Route route, String clientProtocol) {
    return selected(headers, input, routes.forRequest(route), clientProtocol);
  }

  private ResponseEntity<?> selected(
      HttpHeaders headers, JsonNode input, RoutingService.Route route, String clientProtocol) {
    var principal = auth.authenticate(headers, route.requestedModel());
    auth.authorizeRoute(principal, route);
    governance.enforce(principal, route, headers);
    if (!input.isObject()) throw bad("Request must be an object");
    input
        .fieldNames()
        .forEachRemaining(
            field -> {
              if (!FIELDS.contains(field))
                throw bad("Unsupported Chat Completions field: " + field);
            });
    if (!input.path("messages").isArray()
        || input.path("messages").isEmpty()
        || input.path("messages").size() > 200) throw bad("messages must contain 1 to 200 entries");
    if (input.has("stream") && !input.path("stream").isBoolean())
      throw bad("stream must be a boolean");
    for (String field : List.of("max_tokens", "max_completion_tokens"))
      if (input.has(field)
          && (!input.path(field).isIntegralNumber()
              || !input.path(field).canConvertToInt()
              || input.path(field).asLong() < 1)) throw bad("Invalid " + field);
    if (route.keyRequired() && route.upstreamKey().isBlank())
      throw new GatewayException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "server_error",
          "upstream_not_configured",
          "Upstream credential is not configured");
    boolean stream = input.path("stream").asBoolean(false),
        includeUsage = input.path("stream_options").path("include_usage").asBoolean(false);
    ModelCapabilities.enforce(input, route);
    if (input.has("n") && (!input.path("n").isIntegralNumber() || input.path("n").asInt() != 1))
      throw bad("Only n=1 is supported");
    if (input.has("max_tokens")
        && input.has("max_completion_tokens")
        && !input.path("max_tokens").equals(input.path("max_completion_tokens")))
      throw bad("max_tokens and max_completion_tokens must not disagree");
    ToolResponsePolicy toolPolicy = new ToolResponsePolicy(input, route, json, false);
    ObjectNode request = ((ObjectNode) input).deepCopy();
    request.put("model", route.upstreamModel());
    String tokenField = route.config().path("maxTokensField").asText("max_completion_tokens");
    if (!request.has("max_tokens") && !request.has("max_completion_tokens"))
      request.put("max_tokens".equals(tokenField) ? "max_tokens" : "max_completion_tokens", 1024);
    if ("max_tokens".equals(tokenField) && request.has("max_completion_tokens")) {
      request.set("max_tokens", request.remove("max_completion_tokens"));
    } else if ("max_completion_tokens".equals(tokenField) && request.has("max_tokens")) {
      request.set("max_completion_tokens", request.remove("max_tokens"));
    }
    if (stream) {
      ObjectNode options =
          request.path("stream_options").isObject()
              ? ((ObjectNode) request.path("stream_options")).deepCopy()
              : json.createObjectNode();
      options.put("include_usage", true);
      request.set("stream_options", options);
    }
    GenerationPolicy.apply(request, route);
    UUID id = logs.start(clientProtocol, route, principal, stream, request, headers);
    long started = System.nanoTime();
    if (!stream) {
      JsonNode accumulated = null;
      RoutingService.Route selectedRoute = route;
      try {
        for (int pass = 0; pass < 2; pass++) {
          var result =
              exchange.execute(
                  id,
                  selectedRoute,
                  principal,
                  headers,
                  request,
                  logs,
                  pass == 0,
                  selected ->
                      call(selected, false)
                          .bodyValue(body(request, selected))
                          .exchangeToMono(
                              upstream ->
                                  upstream.statusCode().isError()
                                      ? upstream
                                          .releaseBody()
                                          .then(Mono.error(upstream(upstream.statusCode().value())))
                                      : upstream.bodyToMono(JsonNode.class)));
          selectedRoute = result.route();
          JsonNode response = result.value();
          toolPolicy = new ToolResponsePolicy(input, selectedRoute, json, false);
          if (response == null || !response.isObject() || !response.path("choices").isArray())
            throw new GatewayException(
                HttpStatus.BAD_GATEWAY,
                "upstream_error",
                "upstream_protocol",
                "Invalid Chat Completions response");
          ((ObjectNode) response).put("model", route.requestedModel());
          JsonNode observed = usage(response.path("usage"));
          accumulated = combineUsage(accumulated, observed);
          try {
            toolPolicy.complete(response, false);
          } catch (GatewayException validation) {
            boolean repair =
                pass == 0
                    && RequestLogService.repairEnabled(clientProtocol, false, input, selectedRoute)
                    && "tool_arguments_invalid".equals(validation.code);
            logs.responseAttempt(id, observed, false, repair, false);
            if (!repair) {
              logs.completed(id, elapsed(started), accumulated);
              throw validation;
            }
            ((com.fasterxml.jackson.databind.node.ArrayNode) request.path("messages"))
                .addObject()
                .put("role", "user")
                .put(
                    "content",
                    "PolyGate tool argument repair: Return a corrected tool call only. Previous"
                        + " arguments did not satisfy the declared JSON schema. Respect required"
                        + " properties, types and tool_choice.");
            continue;
          }
          logs.responseAttempt(id, observed, true, false, pass == 1);
          logs.completed(id, elapsed(started), accumulated);
          return ResponseEntity.ok()
              .header("x-polygate-request-id", id.toString())
              .header("x-polygate-resolved-provider", selectedRoute.providerId())
              .header("x-polygate-resolved-model", selectedRoute.upstreamModel())
              .body(response);
        }
        throw new IllegalStateException("Tool repair attempts exhausted");
      } catch (RuntimeException e) {
        GatewayException safe = UpstreamFailures.safe(e);
        logs.upstreamFailed(
            id,
            elapsed(started),
            e,
            accumulated != null
                    && (e instanceof UpstreamExchange.BeforeDispatch
                        || Set.of(400, 401, 402, 403, 404, 413, 422, 429, 503)
                            .contains(safe.upstreamStatus))
                ? accumulated
                : null);
        throw UpstreamFailures.safe(e);
      }
    }
    try {
      var result =
          exchange.execute(
              id,
              route,
              principal,
              headers,
              request,
              logs,
              true,
              selected ->
                  call(selected, true)
                      .bodyValue(body(request, selected))
                      .retrieve()
                      .onStatus(
                          status -> status.isError(),
                          response ->
                              response
                                  .releaseBody()
                                  .then(Mono.error(upstream(response.statusCode().value()))))
                      .toEntityFlux(SSE));
      var entity = result.value();
      var selectedRoute = result.route();
      ToolResponsePolicy streamToolPolicy =
          new ToolResponsePolicy(input, selectedRoute, json, false);
      if (entity == null
          || entity.getHeaders().getContentType() == null
          || !MediaType.TEXT_EVENT_STREAM.isCompatibleWith(entity.getHeaders().getContentType()))
        throw new GatewayException(
            HttpStatus.BAD_GATEWAY,
            "upstream_error",
            "upstream_protocol",
            "Upstream did not return SSE");
      AtomicBoolean done = new AtomicBoolean(), finished = new AtomicBoolean();
      ObjectNode observed = json.createObjectNode();
      StreamTextNormalizer normalizer = new StreamTextNormalizer(json, selectedRoute.config());
      Flux<ServerSentEvent<String>> output =
          entity
              .getBody()
              .map(normalizer::openai)
              .map(streamToolPolicy::openai)
              .timeout(Duration.ofMillis(selectedRoute.streamIdleTimeoutMs()))
              .take(Duration.ofMillis(result.remainingMs()))
              .handle(
                  (event, sink) -> {
                    String data = event.data();
                    if (data == null) return;
                    if ("[DONE]".equals(data)) {
                      if (!finished.get()) {
                        sink.error(
                            new IllegalStateException("Stream ended without a finish reason"));
                        return;
                      }
                      done.set(true);
                      sink.next(ServerSentEvent.<String>builder("[DONE]").build());
                      return;
                    }
                    try {
                      JsonNode value = json.readTree(data);
                      if (!value.isObject() || value.has("error"))
                        throw new IllegalArgumentException();
                      ((ObjectNode) value).put("model", route.requestedModel());
                      if (value.path("usage").isObject())
                        observed.setAll(usage(value.path("usage")));
                      for (JsonNode choice : value.path("choices"))
                        if (choice.hasNonNull("finish_reason")) finished.set(true);
                      if (!includeUsage
                          && value.path("choices").isArray()
                          && value.path("choices").isEmpty()
                          && value.has("usage")) return;
                      if (!includeUsage) ((ObjectNode) value).remove("usage");
                      sink.next(
                          ServerSentEvent.<String>builder(json.writeValueAsString(value)).build());
                    } catch (Exception e) {
                      sink.error(new IllegalStateException("Invalid upstream stream event"));
                    }
                  });
      output =
          output
              .takeUntil(event -> "[DONE]".equals(event.data()))
              .concatWith(
                  Mono.defer(
                      () ->
                          done.get() && finished.get()
                              ? Mono.empty()
                              : Mono.error(
                                  new IllegalStateException("Incomplete upstream stream"))))
              .onErrorResume(
                  error ->
                      Flux.just(
                          ServerSentEvent.<String>builder(
                                  "{\"error\":{\"type\":\"upstream_error\",\"message\":\"Upstream"
                                      + " stream failed\"}}")
                              .build(),
                          ServerSentEvent.<String>builder("[DONE]").build()))
              .doFinally(
                  signal -> {
                    if (done.get() && finished.get())
                      logs.completed(id, elapsed(started), observed);
                    else if (signal == reactor.core.publisher.SignalType.CANCEL)
                      logs.cancelled(id, elapsed(started));
                    else logs.failed(id, elapsed(started), 502, "upstream_stream_failure");
                  });
      return ResponseEntity.ok()
          .header("x-polygate-request-id", id.toString())
          .header("x-polygate-resolved-provider", selectedRoute.providerId())
          .header("x-polygate-resolved-model", selectedRoute.upstreamModel())
          .contentType(MediaType.TEXT_EVENT_STREAM)
          .body(
              StreamTextNormalizer.buffer(
                      output, selectedRoute.config().path("bufferStreamText").asBoolean())
                  .doOnError(e -> logs.deliveryFailed(id)));
    } catch (RuntimeException e) {
      logs.upstreamFailed(id, elapsed(started), e);
      throw UpstreamFailures.safe(e);
    }
  }

  private ObjectNode usage(JsonNode usage) {
    ObjectNode out = json.createObjectNode();
    if (!usage.isObject()
        || !tokenValue(usage.path("prompt_tokens"))
        || !tokenValue(usage.path("completion_tokens"))) return out;
    JsonNode cachedValue = usage.path("prompt_tokens_details").path("cached_tokens");
    if (!cachedValue.isMissingNode()
        && (!tokenValue(cachedValue)
            || cachedValue.asLong() > usage.path("prompt_tokens").asLong())) return out;
    long cached = cachedValue.asLong(0);
    out.put("input_tokens", Math.max(0, usage.path("prompt_tokens").asLong() - cached));
    out.put("output_tokens", Math.max(0, usage.path("completion_tokens").asLong()));
    out.put("cache_read_input_tokens", cached);
    return out;
  }

  private WebClient.RequestBodySpec call(RoutingService.Route route, boolean stream) {
    var call =
        http.post()
            .uri(route.endpoint("/v1/chat/completions"))
            .contentType(MediaType.APPLICATION_JSON)
            .accept(stream ? MediaType.TEXT_EVENT_STREAM : MediaType.APPLICATION_JSON);
    if (!route.upstreamKey().isBlank())
      call.header(HttpHeaders.AUTHORIZATION, "Bearer " + route.upstreamKey());
    route
        .config()
        .path("staticHeaders")
        .fields()
        .forEachRemaining(e -> call.header(e.getKey(), e.getValue().asText()));
    return call;
  }

  private ObjectNode body(ObjectNode input, RoutingService.Route route) {
    ObjectNode body = input.deepCopy();
    body.put("model", route.upstreamModel());
    String field = route.config().path("maxTokensField").asText("max_completion_tokens");
    if ("max_tokens".equals(field) && body.has("max_completion_tokens"))
      body.set("max_tokens", body.remove("max_completion_tokens"));
    else if ("max_completion_tokens".equals(field) && body.has("max_tokens"))
      body.set("max_completion_tokens", body.remove("max_tokens"));
    return GenerationPolicy.apply(body, route);
  }

  private static boolean tokenValue(JsonNode value) {
    return value.isIntegralNumber()
        && value.canConvertToLong()
        && value.asLong() >= 0
        && value.asLong() <= 1_000_000_000L;
  }

  private ObjectNode combineUsage(JsonNode previous, JsonNode current) {
    if (previous == null) return (ObjectNode) current.deepCopy();
    ObjectNode out = json.createObjectNode();
    if (!previous.has("input_tokens")
        || !previous.has("output_tokens")
        || !current.has("input_tokens")
        || !current.has("output_tokens")) return out;
    for (String field :
        List.of(
            "input_tokens",
            "output_tokens",
            "cache_read_input_tokens",
            "cache_creation_input_tokens"))
      out.put(field, Math.addExact(previous.path(field).asLong(0), current.path(field).asLong(0)));
    return out;
  }

  private static long elapsed(long started) {
    return (System.nanoTime() - started) / 1_000_000;
  }

  private static GatewayException bad(String detail) {
    return new GatewayException(
        HttpStatus.BAD_REQUEST, "invalid_request_error", "invalid_request", detail);
  }

  private static GatewayException upstream(int status) {
    return new GatewayException(
        status == 429 ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_GATEWAY,
        "upstream_error",
        "upstream_status",
        "Upstream returned HTTP " + status,
        status);
  }
}
