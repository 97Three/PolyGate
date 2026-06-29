package dev.polygate;

import com.fasterxml.jackson.databind.*;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@RestController
@ConditionalOnProperty(name = "polygate.database.enabled", havingValue = "true")
class ProviderProbeController {
  private final AdminAuthService auth;
  private final AdminModelsController providers;
  private final RoutingService routes;
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final WebClient http;

  ProviderProbeController(
      AdminAuthService auth,
      AdminModelsController providers,
      RoutingService routes,
      JdbcTemplate db,
      ObjectMapper json,
      WebClient.Builder builder) {
    this.auth = auth;
    this.providers = providers;
    this.routes = routes;
    this.db = db;
    this.json = json;
    http = builder.build();
  }

  @PostMapping("/admin/providers/{provider}/models")
  Object discover(@RequestHeader HttpHeaders headers, @PathVariable String provider) {
    auth.requireWrite(headers);
    auth.requireAdmin(headers);
    return probe(provider);
  }

  @PostMapping("/admin/settings/test-provider")
  Object test(@RequestHeader HttpHeaders headers, @RequestBody JsonNode input) {
    auth.requireWrite(headers);
    auth.requireAdmin(headers);
    return probe(input.path("providerId").asText());
  }

  private Map<String, Object> probe(String provider) {
    Map<String, Object> result = new HashMap<>();
    result.put("providerId", provider);
    result.put("models", List.of());
    result.put("modelCount", 0);
    result.put("success", false);
    result.put("testedCredentialId", null);
    try {
      JsonNode metadata = json.valueToTree(providers.provider(provider));
      String model = metadata.path("defaultModel").asText();
      RoutingService.Route route = routes.forRequest(routes.resolve(provider + ":" + model));
      String url = route.endpoint("/v1/models");
      boolean deepseek = "api.deepseek.com".equals(route.upstreamUrl().getHost());
      if (deepseek) url = "https://api.deepseek.com/models";
      var call = http.get().uri(url).accept(MediaType.APPLICATION_JSON);
      if (deepseek || "openai_compat".equals(route.protocol()))
        call.header(HttpHeaders.AUTHORIZATION, "Bearer " + route.upstreamKey());
      else call.header("x-api-key", route.upstreamKey()).header("anthropic-version", "2023-06-01");
      JsonNode value =
          call.exchangeToMono(
                  response ->
                      response.statusCode().isError()
                          ? response
                              .releaseBody()
                              .then(
                                  Mono.error(
                                      new GatewayException(
                                          HttpStatus.BAD_GATEWAY,
                                          "upstream_error",
                                          "provider_probe_failed",
                                          "Provider returned HTTP "
                                              + response.statusCode().value())))
                          : response.bodyToMono(JsonNode.class))
              .timeout(Duration.ofSeconds(15))
              .block();
      if (value == null || !value.path("data").isArray())
        throw new GatewayException(
            HttpStatus.BAD_GATEWAY,
            "upstream_error",
            "provider_probe_failed",
            "Provider returned an invalid model catalog");
      List<String> models = new ArrayList<>();
      for (JsonNode item : value.path("data")) {
        String id = item.path("id").asText();
        if (!id.isBlank() && id.length() <= 240) models.add(id);
      }
      result.put("success", true);
      result.put("message", "Discovered " + models.size() + " model(s)");
      result.put("models", models);
      result.put("modelCount", models.size());
      result.put("testedCredentialId", route.credentialId());
    } catch (GatewayException error) {
      result.put("message", error.getMessage());
    } catch (RuntimeException error) {
      result.put("message", "Provider connection failed or timed out");
    }
    String timestamp = Long.toString(System.currentTimeMillis());
    result.put("testedAt", timestamp);
    result.put("discoveredAt", timestamp);
    db.update(
        "UPDATE gateway_provider SET"
            + " config=jsonb_set(config,'{lastTest}',?::jsonb),updated_at=now() WHERE id=?",
        json.valueToTree(result).toString(),
        provider);
    return result;
  }

  @PostMapping("/admin/providers/{provider}/balance")
  Object balance(@RequestHeader HttpHeaders headers, @PathVariable String provider) {
    auth.requireWrite(headers);
    auth.requireAdmin(headers);
    JsonNode metadata = json.valueToTree(providers.provider(provider));
    var route =
        routes.forRequest(routes.resolve(provider + ":" + metadata.path("defaultModel").asText()));
    if (!"api.deepseek.com".equals(route.upstreamUrl().getHost()))
      throw new GatewayException(
          HttpStatus.BAD_REQUEST,
          "invalid_request_error",
          "balance_not_supported",
          "Online balance lookup is available only for the official DeepSeek API");
    JsonNode value =
        http.get()
            .uri("https://api.deepseek.com/user/balance")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + route.upstreamKey())
            .retrieve()
            .onStatus(
                status -> status.isError(),
                response ->
                    response
                        .releaseBody()
                        .then(
                            Mono.error(
                                new GatewayException(
                                    HttpStatus.BAD_GATEWAY,
                                    "upstream_error",
                                    "balance_lookup_failed",
                                    "Provider balance lookup failed"))))
            .bodyToMono(JsonNode.class)
            .timeout(Duration.ofSeconds(15))
            .block();
    List<Map<String, String>> balances = new ArrayList<>();
    for (JsonNode item : value.path("balance_infos"))
      balances.add(
          Map.of(
              "currency",
              item.path("currency").asText(),
              "totalBalance",
              item.path("total_balance").asText(),
              "grantedBalance",
              item.path("granted_balance").asText(),
              "toppedUpBalance",
              item.path("topped_up_balance").asText()));
    return Map.of(
        "providerId",
        provider,
        "isAvailable",
        value.path("is_available").asBoolean(),
        "balanceInfos",
        balances,
        "checkedAt",
        Long.toString(System.currentTimeMillis()),
        "managementScope",
        "read-monitor-alert",
        "billingAuthority",
        "deepseek-console");
  }
}
