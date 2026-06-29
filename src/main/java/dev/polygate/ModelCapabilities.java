package dev.polygate;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.springframework.http.HttpStatus;

final class ModelCapabilities {
  private ModelCapabilities() {}

  static ObjectNode profile(JsonNode config, String model, JsonNode override) {
    ObjectNode result = JsonNodeFactory.instance.objectNode();
    ConfigurationService.merge(result, config.path("modelProfileDefaults"));
    ConfigurationService.merge(result, config.path("modelProfiles").path(model));
    ConfigurationService.merge(result, override);
    var fields = new ArrayList<String>();
    result.fieldNames().forEachRemaining(fields::add);
    for (String field : fields)
      if (field.contains("_")) {
        StringBuilder key = new StringBuilder();
        boolean upper = false;
        for (char c : field.toCharArray()) {
          if (c == '_') {
            upper = true;
            continue;
          }
          key.append(upper ? Character.toUpperCase(c) : c);
          upper = false;
        }
        result.set(key.toString(), result.remove(field));
      }
    return result;
  }

  static void enforce(JsonNode input, RoutingService.Route route) {
    ObjectNode p =
        profile(route.config(), route.upstreamModel(), route.config().path("_modelOverride"));
    long max = input.path("max_tokens").asLong(input.path("max_completion_tokens").asLong(1024));
    if (p.path("maxOutputTokens").asLong(0) > 0 && max > p.path("maxOutputTokens").asLong())
      throw bad("Requested output exceeds the configured model limit");
    if (p.path("inputModalities").isArray() && image(input)) {
      boolean supported = false;
      for (JsonNode m : p.path("inputModalities")) if ("image".equals(m.asText())) supported = true;
      if (!supported) throw bad("The selected model does not support images");
    }
    if (input.path("tools").isArray()
        && !input.path("tools").isEmpty()
        && ("unsupported".equals(p.path("toolUse").asText())
            || route.config().path("toolUse").has("supported")
                && !route.config().path("toolUse").path("supported").asBoolean()))
      throw bad("The selected model does not support tools");
    if (input.has("tool_choice") && "unsupported".equals(p.path("toolChoice").asText()))
      throw bad("The selected model does not support tool choice");
    if (input.path("parallel_tool_calls").asBoolean()
        && "unsupported".equals(p.path("parallelToolCalls").asText()))
      throw bad("The selected model does not support parallel tools");
    if ((input.has("reasoning_effort")
            || input.has("reasoning")
            || input.has("thinking")
                && !"disabled".equals(input.path("thinking").path("type").asText()))
        && "unsupported".equals(p.path("reasoning").asText()))
      throw bad("The selected model does not support reasoning controls");
  }

  private static boolean image(JsonNode node) {
    if (Set.of("image", "image_url", "input_image").contains(node.path("type").asText()))
      return true;
    if (node.isContainerNode()) for (JsonNode child : node) if (image(child)) return true;
    return false;
  }

  private static GatewayException bad(String message) {
    return new GatewayException(
        HttpStatus.BAD_REQUEST, "invalid_request_error", "model_capability_unsupported", message);
  }
}
