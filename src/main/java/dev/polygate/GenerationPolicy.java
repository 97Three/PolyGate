package dev.polygate;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.springframework.http.HttpStatus;

/** Provider-specific generation controls; client fields always take precedence over defaults. */
final class GenerationPolicy {
  private static JsonNode value(JsonNode node, String camel, String snake) {
    return node.has(camel) ? node.path(camel) : node.path(snake);
  }

  private static JsonNode modelValue(
      JsonNode cfg, String key, String snake, RoutingService.Route route) {
    JsonNode map = value(cfg, key, snake);
    return map.has(route.requestedModel())
        ? map.path(route.requestedModel())
        : map.path(route.upstreamModel());
  }

  static String dialect(RoutingService.Route route) {
    return ModelCapabilities.profile(
            route.config(), route.upstreamModel(), route.config().path("_modelOverride"))
        .path("reasoningDialect")
        .asText(
            route
                .config()
                .path("reasoning")
                .path("dialect")
                .asText(
                    "llama_cpp".equals(route.config().path("reasoning").path("mode").asText())
                        ? "llama_cpp"
                        : "none"));
  }

  static ObjectNode apply(ObjectNode input, RoutingService.Route route) {
    ObjectNode body = input.deepCopy();
    if (!"openai_compat".equals(route.protocol())) return body;
    JsonNode cfg = route.config().path("reasoning"),
        profile =
            ModelCapabilities.profile(
                route.config(), route.upstreamModel(), route.config().path("_modelOverride"));
    String dialect =
        profile
            .path("reasoningDialect")
            .asText(
                cfg.path("dialect")
                    .asText("llama_cpp".equals(cfg.path("mode").asText()) ? "llama_cpp" : "none"));
    JsonNode enabled =
        body.has("thinking")
            ? JsonNodeFactory.instance.booleanNode(
                !"disabled".equals(body.path("thinking").path("type").asText()))
            : modelValue(cfg, "modelEnabled", "model_enabled", route);
    if (enabled.isMissingNode()) enabled = value(cfg, "defaultEnabled", "default_enabled");
    String effort =
        body.path("reasoning_effort")
            .asText(
                body.path("reasoning")
                    .path("effort")
                    .asText(body.path("output_config").path("effort").asText("")));
    if (effort.isEmpty()
        && !body.has("thinking")
        && !body.has("enable_thinking")
        && !body.path("chat_template_kwargs").has("enable_thinking"))
      effort =
          modelValue(cfg, "modelEffort", "model_effort", route)
              .asText(
                  profile
                      .path("defaultReasoningEffort")
                      .asText(value(cfg, "defaultEffort", "default_effort").asText("")));
    if (body.has("enable_thinking")) enabled = body.path("enable_thinking");
    if (body.path("chat_template_kwargs").has("enable_thinking"))
      enabled = body.path("chat_template_kwargs").path("enable_thinking");
    JsonNode budget =
        body.has("thinking_budget_tokens")
            ? body.path("thinking_budget_tokens")
            : body.path("thinking").path("budget_tokens");
    boolean explicitBudget = !budget.isMissingNode();
    if (explicitBudget
        && (!budget.isIntegralNumber() || !budget.canConvertToInt() || budget.asLong() < 1))
      throw bad("Invalid thinking budget");
    if (budget.isMissingNode())
      budget = modelValue(cfg, "modelBudgetTokens", "model_budget_tokens", route);
    if (budget.isMissingNode()) budget = value(cfg, "defaultBudgetTokens", "default_budget_tokens");
    if (!dialect.equals("none")) {
      if (explicitBudget && !dialect.equals("llama_cpp"))
        throw bad("Explicit thinking budget requires llama_cpp reasoning dialect");
      if (!effort.isEmpty()
          && !Set.of("off", "none", "minimal", "low", "medium", "high", "xhigh", "max")
              .contains(effort)) throw bad("Invalid reasoning effort");
      boolean hasControl = !effort.isEmpty() || enabled.isBoolean();
      boolean on =
          !effort.isEmpty() ? !Set.of("off", "none").contains(effort) : enabled.asBoolean();
      String selected = effort.isEmpty() ? (on ? "high" : "off") : effort;
      String rendered = profile.path("reasoningEffortMap").path(selected).asText(selected);
      body.remove(List.of("reasoning_effort", "reasoning", "thinking", "output_config"));
      switch (dialect) {
        case "llama_cpp" -> {
          if (hasControl) body.withObject("/chat_template_kwargs").put("enable_thinking", on);
          if ((!hasControl || on) && budget.isIntegralNumber()) {
            if (budget.asLong() < 1) throw bad("Invalid thinking budget");
            body.set("thinking_budget_tokens", budget);
          }
        }
        case "openai" -> {
          if (hasControl) body.put("reasoning_effort", rendered);
        }
        case "deepseek" -> {
          if (hasControl) {
            body.putObject("thinking").put("type", on ? "enabled" : "disabled");
            body.put("reasoning_effort", rendered);
          }
        }
        case "openrouter" -> {
          if (hasControl) body.putObject("reasoning").put("effort", rendered);
        }
        case "qwen" -> {
          if (hasControl) body.put("enable_thinking", on);
        }
        case "zai" -> {
          if (hasControl) {
            ObjectNode t = body.putObject("thinking").put("type", on ? "enabled" : "disabled");
            if (on) {
              t.put("clear_thinking", false);
              body.put("reasoning_effort", rendered);
            }
          }
        }
        case "string_thinking" -> {
          if (hasControl) body.put("thinking", rendered);
        }
        default -> throw bad("Unsupported reasoning dialect");
      }
    }
    JsonNode sampling = route.config().path("sampling");
    if ("llama_cpp".equals(sampling.path("mode").asText())) {
      JsonNode p = sampling.path("profiles").path(route.requestedModel());
      if (!p.isObject()) p = sampling.path("profiles").path(route.upstreamModel());
      for (String field :
          List.of("temperature", "top_p", "top_k", "min_p", "presence_penalty", "repeat_penalty"))
        if (!body.has(field) && p.has(field)) body.set(field, p.path(field));
    }
    return body;
  }

  static void validate(JsonNode cfg) {
    if (cfg.has("reasoning") && !cfg.path("reasoning").isObject())
      throw bad("reasoning must be an object");
    if (cfg.has("sampling") && !cfg.path("sampling").isObject())
      throw bad("sampling must be an object");
    String d = cfg.path("reasoning").path("dialect").asText("none");
    if (!Set.of(
            "none",
            "llama_cpp",
            "openai",
            "deepseek",
            "openrouter",
            "qwen",
            "zai",
            "string_thinking")
        .contains(d)) throw bad("Invalid reasoning dialect");
    if (!Set.of("none", "llama_cpp").contains(cfg.path("reasoning").path("mode").asText("none")))
      throw bad("Invalid reasoning mode");
    if (!Set.of("none", "llama_cpp").contains(cfg.path("sampling").path("mode").asText("none")))
      throw bad("Invalid sampling mode");
    for (String key : List.of("defaultEnabled", "default_enabled"))
      if (cfg.path("reasoning").has(key) && !cfg.path("reasoning").path(key).isBoolean())
        throw bad("Invalid default thinking switch");
    for (JsonNode p : cfg.path("sampling").path("profiles"))
      for (String f :
          List.of("temperature", "top_p", "top_k", "min_p", "presence_penalty", "repeat_penalty"))
        if (p.has(f) && (!p.path(f).isNumber() || !Double.isFinite(p.path(f).asDouble())))
          throw bad("Invalid sampling value");
  }

  private static GatewayException bad(String m) {
    return new GatewayException(
        HttpStatus.BAD_REQUEST, "invalid_request_error", "invalid_generation_policy", m);
  }
}
