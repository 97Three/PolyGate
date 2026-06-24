package dev.polygate;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@RestController
class TokenCountController {
  private final ClientAuthService auth;
  private final RoutingService routes;
  private final GovernanceService governance;
  private final MessagesController messages;
  private final WebClient http;

  TokenCountController(
      ClientAuthService auth,
      RoutingService routes,
      GovernanceService governance,
      MessagesController messages,
      WebClient.Builder builder) {
    this.auth = auth;
    this.routes = routes;
    this.governance = governance;
    this.messages = messages;
    http = builder.build();
  }

  @PostMapping("/v1/messages/count_tokens")
  Object count(@RequestHeader HttpHeaders headers, @RequestBody JsonNode input) {
    String model = input.path("model").asText();
    var principal = auth.authenticate(headers, model);
    var route = routes.forRequest(routes.resolve(model));
    auth.authorizeRoute(principal, route);
    governance.enforce(principal, route, headers);
    if (!"anthropic".equals(route.config().path("tokenCounting").path("mode").asText()))
      throw new GatewayException(
          HttpStatus.BAD_REQUEST,
          "invalid_request_error",
          "token_counting_not_enabled",
          "Provider does not explicitly enable Anthropic token counting");
    if (!input.isObject() || input.has("stream") || input.has("max_tokens"))
      throw new GatewayException(
          HttpStatus.BAD_REQUEST,
          "invalid_request_error",
          "invalid_request",
          "Token-count requests must not include stream or max_tokens");
    ObjectNode check = ((ObjectNode) input).deepCopy();
    check.put("max_tokens", Integer.MAX_VALUE);
    ObjectNode request = messages.validateAndMap(check, route);
    request.remove("max_tokens");
    request = GenerationPolicy.apply(request, route);
    if (route.keyRequired() && route.upstreamKey().isBlank())
      throw new GatewayException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "server_error",
          "missing_credential",
          "Provider credential is not configured");
    var call =
        http.post()
            .uri(
                route.endpoint(
                    "openai_compat".equals(route.protocol())
                        ? "/messages/count_tokens"
                        : "/v1/messages/count_tokens"))
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON);
    if ("openai_compat".equals(route.protocol()))
      call.header(HttpHeaders.AUTHORIZATION, "Bearer " + route.upstreamKey());
    else call.header("x-api-key", route.upstreamKey()).header("anthropic-version", "2023-06-01");
    route
        .config()
        .path("staticHeaders")
        .fields()
        .forEachRemaining(e -> call.header(e.getKey(), e.getValue().asText()));
    JsonNode result =
        call.bodyValue(request)
            .exchangeToMono(
                response ->
                    response.statusCode().isError()
                        ? response
                            .releaseBody()
                            .then(
                                Mono.error(
                                    new GatewayException(
                                        HttpStatus.BAD_GATEWAY,
                                        "upstream_error",
                                        "token_count_failed",
                                        "Provider token counting failed")))
                        : response.bodyToMono(JsonNode.class))
            .timeout(Duration.ofMillis(Math.min(route.requestTimeoutMs(), 30000)))
            .block();
    if (result == null
        || !result.path("input_tokens").isIntegralNumber()
        || !result.path("input_tokens").canConvertToLong()
        || result.path("input_tokens").asLong() < 0)
      throw new GatewayException(
          HttpStatus.BAD_GATEWAY,
          "upstream_error",
          "token_count_protocol",
          "Provider returned an invalid token count");
    return result;
  }
}
