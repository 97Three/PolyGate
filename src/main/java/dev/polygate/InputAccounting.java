package dev.polygate;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
class InputAccounting {
  private final JdbcTemplate db;
  private final WebClient http;

  InputAccounting(ObjectProvider<JdbcTemplate> db, WebClient.Builder builder) {
    this.db = db.getIfAvailable();
    this.http = builder.build();
  }

  long input(
      String protocol,
      JsonNode request,
      RoutingService.Route route,
      ClientAuthService.Principal principal) {
    long bytes = request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    if (!media(request)) return bytes;
    if ("anthropic".equals(route.config().path("tokenCounting").path("mode").asText())) {
      ObjectNode body = ((ObjectNode) request).deepCopy();
      body.put("model", route.upstreamModel());
      body.remove(List.of("stream", "max_tokens", "metadata", "service_tier"));
      body = GenerationPolicy.apply(body, route);
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
          call.bodyValue(body)
              .exchangeToMono(
                  r ->
                      r.statusCode().isError()
                          ? r.releaseBody().then(Mono.error(unavailable()))
                          : r.bodyToMono(JsonNode.class))
              .timeout(Duration.ofMillis(Math.min(30000, route.requestTimeoutMs())))
              .block();
      if (result == null
          || !result.path("input_tokens").isIntegralNumber()
          || !result.path("input_tokens").canConvertToLong()
          || result.path("input_tokens").asLong() < 0
          || result.path("input_tokens").asLong() > 1_000_000_000L) throw unavailable();
      return result.path("input_tokens").asLong();
    }
    if (capped(principal)) throw unavailable();
    return bytes;
  }

  private boolean capped(ClientAuthService.Principal p) {
    if (db == null) return false;
    TenantScope s = p.scope();
    if (db.queryForObject(
            "SELECT count(*) FROM gateway_budget_account WHERE organization_id=? AND project_id=?"
                + " AND environment_id=? AND limit_microunits IS NOT NULL",
            Integer.class,
            s.organizationId(),
            s.projectId(),
            s.environmentId())
        > 0) return true;
    if (p.userId() != null
        && db.queryForObject(
                "SELECT count(*) FROM gateway_quota WHERE user_id=? AND quota_type IN"
                    + " ('tokens','cost')",
                Integer.class,
                p.userId())
            > 0) return true;
    if (p.apiKeyId() != null) {
      var rows =
          db.queryForList(
              "SELECT k.policy::text policy,t.daily_limit_usd,t.monthly_limit_usd FROM"
                  + " gateway_api_key k LEFT JOIN gateway_team t ON t.id=k.team_id WHERE k.id=?",
              p.apiKeyId());
      for (var r : rows) {
        for (String field : List.of("daily_limit_usd", "monthly_limit_usd"))
          if (r.get(field) instanceof Number n && n.doubleValue() > 0) return true;
        try {
          JsonNode policy = new ObjectMapper().readTree((String) r.get("policy"));
          for (String field :
              List.of(
                  "spendLimitUsd",
                  "fiveHourLimitUsd",
                  "dailyLimitUsd",
                  "weeklyLimitUsd",
                  "monthlyLimitUsd")) if (policy.path(field).asDouble() > 0) return true;
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
      }
    }
    return false;
  }

  static boolean media(JsonNode value) {
    if (value.isObject()) {
      String type = value.path("type").asText();
      if (Set.of("image", "image_url", "input_image", "document", "input_audio", "video")
          .contains(type)) return true;
    }
    if (value.isContainerNode()) for (JsonNode child : value) if (media(child)) return true;
    return false;
  }

  private static GatewayException unavailable() {
    return new GatewayException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "server_error",
        "media_token_count_required",
        "Reliable provider token counting is required before enforcing a token or money limit on"
            + " media inputs");
  }
}
