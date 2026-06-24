package dev.polygate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
public class MessagesController {
  private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
      new ParameterizedTypeReference<>() {};
  private static final Set<String> SUPPORTED_FIELDS =
      Set.of(
          "model",
          "max_tokens",
          "messages",
          "stream",
          "system",
          "temperature",
          "top_p",
          "top_k",
          "stop_sequences",
          "metadata",
          "tools",
          "tool_choice",
          "thinking",
          "output_config",
          "service_tier",
          "context_management");
  private final GatewaySettings settings;
  private final ObjectMapper json;
  private final WebClient http;
  private final RequestLogService requests;
  private final ClientAuthService clientAuth;
  private final RoutingService routes;
  private final GovernanceService governance;
  private final AnthropicOpenAiBridge bridge;
  private final UpstreamExchange exchange;

  MessagesController(
      GatewaySettings settings,
      ObjectMapper json,
      WebClient.Builder builder,
      RequestLogService requests,
      ClientAuthService clientAuth,
      RoutingService routes,
      GovernanceService governance,
      AnthropicOpenAiBridge bridge,
      UpstreamExchange exchange) {
    this.settings = settings;
    this.json = json;
    this.http = builder.build();
    this.requests = requests;
    this.clientAuth = clientAuth;
    this.routes = routes;
    this.governance = governance;
    this.bridge = bridge;
    this.exchange = exchange;
  }

  @GetMapping("/v1/models")
  public Map<String, Object> models(@RequestHeader HttpHeaders headers) {
    ClientAuthService.Principal principal = clientAuth.authenticate(headers, null);
    var models = routes.models();
    if (!principal.allowedModels().isEmpty()) {
      models =
          models.stream()
              .filter(
                  model ->
                      clientAuth.modelAllowed(principal, model.get("id"), model.get("owned_by")))
              .toList();
    }
    models =
        models.stream()
            .filter(
                model -> clientAuth.modelAllowed(principal, model.get("id"), model.get("owned_by")))
            .toList();
    models =
        models.stream()
            .filter(
                model -> {
                  try {
                    governance.enforce(principal, routes.resolve(model.get("id")), headers);
                    return true;
                  } catch (GatewayException e) {
                    return false;
                  }
                })
            .toList();
    Map<String, Object> result = new java.util.HashMap<>();
    result.put("data", models);
    result.put("object", "list");
    result.put("has_more", false);
    result.put("first_id", models.isEmpty() ? null : models.getFirst().get("id"));
    result.put("last_id", models.isEmpty() ? null : models.getLast().get("id"));
    return result;
  }

  @PostMapping(value = "/v1/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> messages(
      @RequestHeader HttpHeaders headers, @RequestBody JsonNode input) {
    return SseResponses.adapt(forward(headers, input, "anthropic"));
  }

  ResponseEntity<?> forward(HttpHeaders headers, JsonNode input, String protocol) {
    SmartRoutingService.requestInput(input);
    String requestedModel = input.path("model").asText("");
    ClientAuthService.Principal principal = clientAuth.authenticate(headers, requestedModel);
    RoutingService.Route resolved = routes.resolve(requestedModel);
    RoutingService.Route route =
        "anthropic".equals(resolved.protocol()) ? routes.forRequest(resolved) : resolved;
    clientAuth.authorizeRoute(principal, route);
    if ("anthropic".equals(route.protocol())) governance.enforce(principal, route, headers);
    ObjectNode request = validateAndMap(input, route);
    boolean stream = request.path("stream").asBoolean(false);
    if ("openai_compat".equals(route.protocol())) return bridge.forward(headers, input, route);
    ToolResponsePolicy toolPolicy = new ToolResponsePolicy(request, route, json, true);
    if (route.keyRequired() && route.upstreamKey().isBlank()) {
      throw new GatewayException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "server_error",
          "upstream_not_configured",
          "Upstream credential is not configured");
    }
    UUID requestId = requests.start(protocol, route, principal, stream, request, headers);
    long started = System.nanoTime();
    if (!stream) {
      try {
        var result =
            exchange.execute(
                requestId,
                route,
                principal,
                headers,
                request,
                requests,
                true,
                selected ->
                    call(selected, false, headers)
                        .bodyValue(body(request, selected))
                        .exchangeToMono(
                            response -> {
                              if (response.statusCode().isError())
                                return response
                                    .releaseBody()
                                    .then(Mono.error(upstreamError(response.statusCode().value())));
                              return response.bodyToMono(String.class);
                            }));
        String raw = result.value();
        var selectedRoute = result.route();
        try {
          JsonNode response = json.readTree(raw);
          if (!response.isObject()
              || !response.path("content").isArray()
              || !"message".equals(response.path("type").asText())) {
            throw new GatewayException(
                HttpStatus.BAD_GATEWAY,
                "upstream_error",
                "upstream_protocol",
                "Invalid Anthropic response from upstream");
          }
          ((ObjectNode) response).put("model", route.requestedModel());
          requests.completed(requestId, elapsedMillis(started), response.path("usage"));
          new ToolResponsePolicy(request, selectedRoute, json, true).complete(response, true);
          return ResponseEntity.ok()
              .header("x-polygate-request-id", requestId.toString())
              .header("x-polygate-resolved-provider", selectedRoute.providerId())
              .header("x-polygate-resolved-model", selectedRoute.upstreamModel())
              .body(response);
        } catch (GatewayException e) {
          throw e;
        } catch (Exception e) {
          throw new GatewayException(
              HttpStatus.BAD_GATEWAY,
              "upstream_error",
              "upstream_protocol",
              "Invalid JSON from upstream");
        }
      } catch (RuntimeException error) {
        requests.upstreamFailed(requestId, elapsedMillis(started), error);
        throw UpstreamFailures.safe(error);
      }
    }
    try {
      var result =
          exchange.execute(
              requestId,
              route,
              principal,
              headers,
              request,
              requests,
              true,
              selected ->
                  call(selected, true, headers)
                      .bodyValue(body(request, selected))
                      .retrieve()
                      .onStatus(
                          status -> status.isError(),
                          response ->
                              response
                                  .releaseBody()
                                  .then(Mono.error(upstreamError(response.statusCode().value()))))
                      .toEntityFlux(SSE_TYPE));
      var entity = result.value();
      var selectedRoute = result.route();
      ToolResponsePolicy streamToolPolicy =
          new ToolResponsePolicy(request, selectedRoute, json, true);
      if (!MediaType.TEXT_EVENT_STREAM.isCompatibleWith(
          entity.getHeaders().getContentType() == null
              ? MediaType.APPLICATION_OCTET_STREAM
              : entity.getHeaders().getContentType())) {
        throw new GatewayException(
            HttpStatus.BAD_GATEWAY,
            "upstream_error",
            "upstream_protocol",
            "Upstream did not return SSE");
      }
      Flux<ServerSentEvent<String>> upstream = entity.getBody();
      AtomicBoolean stopped = new AtomicBoolean(false);
      StreamTextNormalizer normalizer = new StreamTextNormalizer(json, selectedRoute.config());
      ObjectNode usage = json.createObjectNode();
      Flux<ServerSentEvent<String>> output =
          upstream
              .map(normalizer::anthropic)
              .map(streamToolPolicy::anthropic)
              .timeout(Duration.ofMillis(selectedRoute.streamIdleTimeoutMs()))
              .take(Duration.ofMillis(result.remainingMs()))
              .map(
                  event -> {
                    if ("error".equals(event.event()))
                      throw new GatewayException(
                          HttpStatus.BAD_GATEWAY,
                          "upstream_error",
                          "upstream_stream_failure",
                          "Upstream stream failed");
                    String data = event.data();
                    if (data != null
                        && ("message_start".equals(event.event())
                            || "message_delta".equals(event.event()))) {
                      try {
                        JsonNode value = json.readTree(data);
                        JsonNode observed = value.path("usage");
                        if (observed.isMissingNode())
                          observed = value.path("message").path("usage");
                        if (observed.isObject()) {
                          observed
                              .fields()
                              .forEachRemaining(
                                  entry -> usage.set(entry.getKey(), entry.getValue()));
                        }
                      } catch (Exception ignored) {
                        /* malformed event is handled by the stream decoder below */
                      }
                    }
                    if ("message_start".equals(event.event()) && data != null) {
                      try {
                        JsonNode value = json.readTree(data);
                        if (value.path("message").isObject())
                          ((ObjectNode) value.path("message")).put("model", route.requestedModel());
                        data = json.writeValueAsString(value);
                      } catch (Exception e) {
                        throw new GatewayException(
                            HttpStatus.BAD_GATEWAY,
                            "upstream_error",
                            "upstream_protocol",
                            "Invalid SSE message_start JSON");
                      }
                    }
                    if ("message_stop".equals(event.event())) stopped.set(true);
                    return ServerSentEvent.<String>builder(data)
                        .event(event.event())
                        .id(event.id())
                        .build();
                  })
              .takeUntil(event -> "message_stop".equals(event.event()))
              .concatWith(
                  Mono.defer(
                      () ->
                          stopped.get()
                              ? Mono.empty()
                              : Mono.just(
                                  streamError("Anthropic stream ended without message_stop"))))
              .onErrorResume(error -> Mono.just(streamError("Upstream stream failed")))
              .doFinally(
                  signal -> {
                    if (stopped.get()) requests.completed(requestId, elapsedMillis(started), usage);
                    else if (signal == reactor.core.publisher.SignalType.CANCEL)
                      requests.cancelled(requestId, elapsedMillis(started));
                    else
                      requests.failed(
                          requestId, elapsedMillis(started), 502, "upstream_stream_failure");
                  });
      return ResponseEntity.ok()
          .header("x-polygate-request-id", requestId.toString())
          .header("x-polygate-resolved-provider", selectedRoute.providerId())
          .header("x-polygate-resolved-model", selectedRoute.upstreamModel())
          .contentType(MediaType.TEXT_EVENT_STREAM)
          .body(
              StreamTextNormalizer.buffer(
                      output, selectedRoute.config().path("bufferStreamText").asBoolean())
                  .doOnError(e -> requests.deliveryFailed(requestId)));
    } catch (RuntimeException error) {
      requests.upstreamFailed(requestId, elapsedMillis(started), error);
      throw UpstreamFailures.safe(error);
    }
  }

  ObjectNode validateAndMap(JsonNode input, RoutingService.Route route) {
    if (!input.isObject()) throw bad("Request must be a JSON object");
    input
        .fieldNames()
        .forEachRemaining(
            field -> {
              if (!SUPPORTED_FIELDS.contains(field))
                throw bad("Unsupported request field: " + field);
            });
    String model = input.path("model").asText("");
    if (!route.requestedModel().equals(model)) throw bad("Unknown model");
    if (!input.path("max_tokens").isIntegralNumber()
        || !input.path("max_tokens").canConvertToInt()
        || input.path("max_tokens").asInt() < 1) throw bad("max_tokens must be a positive integer");
    if (input.has("thinking")) {
      JsonNode thinking = input.path("thinking");
      String type = thinking.path("type").asText();
      if (!Set.of("enabled", "disabled", "adaptive").contains(type))
        throw bad("Invalid thinking configuration");
      if ("enabled".equals(type)
          && (!thinking.path("budget_tokens").isIntegralNumber()
              || thinking.path("budget_tokens").asLong() < 1
              || thinking.path("budget_tokens").asLong() >= input.path("max_tokens").asLong()))
        throw bad("thinking budget must be positive and below max_tokens");
    }
    JsonNode messages = input.path("messages");
    if (!messages.isArray() || messages.isEmpty() || messages.size() > 200)
      throw bad("messages must contain 1 to 200 entries");
    Set<String> toolNames = new HashSet<>();
    if (input.has("tools")) {
      JsonNode tools = input.path("tools");
      if (!tools.isArray() || tools.size() > 256)
        throw bad("tools must be an array of at most 256 entries");
      for (JsonNode tool : tools) {
        String name = tool.path("name").asText("");
        if (name.isBlank() || !tool.path("input_schema").isObject() || !toolNames.add(name))
          throw bad("Invalid or duplicate tool definition");
      }
    }
    if (input.has("tool_choice")) {
      JsonNode choice = input.path("tool_choice");
      String type = choice.path("type").asText("");
      if (!choice.isObject() || !Set.of("auto", "any", "tool", "none").contains(type))
        throw bad("Invalid tool_choice");
      if ("tool".equals(type) && !toolNames.contains(choice.path("name").asText("")))
        throw bad("tool_choice references an unknown tool");
    }
    if (input.has("stream") && !input.path("stream").isBoolean())
      throw bad("stream must be a boolean");
    if (input.has("system")) {
      JsonNode system = input.path("system");
      if (!system.isTextual()) {
        if (!system.isArray()) throw bad("system must be text or text blocks");
        for (JsonNode block : system) {
          if (!"text".equals(block.path("type").asText()) || !block.path("text").isTextual())
            throw bad("Only text system blocks are supported");
        }
      }
    }
    Set<String> pendingToolIds = new HashSet<>();
    for (JsonNode message : messages) {
      String role = message.path("role").asText("");
      if (!"user".equals(role) && !"assistant".equals(role))
        throw bad("Only user and assistant messages are supported");
      JsonNode content = message.path("content");
      if (content.isTextual()) continue;
      if (!content.isArray() || content.isEmpty())
        throw bad("Message content must be text or text blocks");
      for (JsonNode block : content) {
        String type = block.path("type").asText("");
        if ("text".equals(type) && block.path("text").isTextual()) continue;
        if ("image".equals(type) && "user".equals(role) && validImage(block)) continue;
        if ("thinking".equals(type)
            && "assistant".equals(role)
            && block.path("thinking").isTextual()
            && block.path("signature").isTextual()) continue;
        if ("redacted_thinking".equals(type)
            && "assistant".equals(role)
            && block.path("data").isTextual()) continue;
        if ("tool_use".equals(type) && "assistant".equals(role)) {
          String id = block.path("id").asText("");
          String name = block.path("name").asText("");
          if (id.isBlank()
              || name.isBlank()
              || !block.path("input").isObject()
              || !pendingToolIds.add(id)) throw bad("Invalid tool_use block");
          continue;
        }
        if ("tool_result".equals(type) && "user".equals(role)) {
          String id = block.path("tool_use_id").asText("");
          if (!pendingToolIds.remove(id) || !validToolResult(block.path("content")))
            throw bad("Invalid or unmatched tool_result block");
          continue;
        }
        throw bad("Unsupported message content block: " + type);
      }
    }
    if (!pendingToolIds.isEmpty())
      throw bad("Every tool_use must have a matching tool_result before the next request");
    ModelCapabilities.enforce(input, route);
    ObjectNode mapped = ((ObjectNode) input).deepCopy();
    mapped.put("model", route.upstreamModel());
    return mapped;
  }

  private WebClient.RequestBodySpec call(
      RoutingService.Route route, boolean stream, HttpHeaders headers) {
    var call =
        http.post()
            .uri(route.endpoint("/v1/messages"))
            .contentType(MediaType.APPLICATION_JSON)
            .accept(stream ? MediaType.TEXT_EVENT_STREAM : MediaType.APPLICATION_JSON)
            .header("x-api-key", route.upstreamKey())
            .header("anthropic-version", version(headers));
    if (headers.containsKey("anthropic-beta"))
      call.header("anthropic-beta", headers.getFirst("anthropic-beta"));
    route
        .config()
        .path("staticHeaders")
        .fields()
        .forEachRemaining(e -> call.header(e.getKey(), e.getValue().asText()));
    return call;
  }

  private ObjectNode body(ObjectNode request, RoutingService.Route route) {
    ObjectNode value = request.deepCopy();
    value.put("model", route.upstreamModel());
    return value;
  }

  private static String version(HttpHeaders headers) {
    String value = headers.getFirst("anthropic-version");
    return value != null && value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") ? value : "2023-06-01";
  }

  private static boolean validToolResult(JsonNode content) {
    if (content.isTextual()) return true;
    if (!content.isArray()) return false;
    for (JsonNode block : content) {
      if ("text".equals(block.path("type").asText()) && block.path("text").isTextual()) continue;
      if ("image".equals(block.path("type").asText()) && validImage(block)) continue;
      return false;
    }
    return true;
  }

  private static boolean validImage(JsonNode block) {
    JsonNode source = block.path("source");
    if ("url".equals(source.path("type").asText())) {
      try {
        java.net.URI url = java.net.URI.create(source.path("url").asText());
        return "https".equals(url.getScheme())
            && url.getHost() != null
            && url.getUserInfo() == null;
      } catch (Exception e) {
        return false;
      }
    }
    if (!"base64".equals(source.path("type").asText())
        || !Set.of("image/jpeg", "image/png", "image/gif", "image/webp")
            .contains(source.path("media_type").asText())) return false;
    try {
      return java.util.Base64.getDecoder().decode(source.path("data").asText()).length > 0;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static GatewayException bad(String detail) {
    return new GatewayException(
        HttpStatus.BAD_REQUEST, "invalid_request_error", "invalid_request", detail);
  }

  private static GatewayException upstreamError(int status) {
    HttpStatus result = status == 429 ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_GATEWAY;
    return new GatewayException(
        result, "upstream_error", "upstream_status", "Upstream returned HTTP " + status, status);
  }

  private static ServerSentEvent<String> streamError(String message) {
    return ServerSentEvent.<String>builder(
            "{\"type\":\"error\",\"error\":{\"type\":\"upstream_error\",\"message\":\""
                + message
                + "\"}}")
        .event("error")
        .build();
  }

  private static long elapsedMillis(long started) {
    return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
  }
}
