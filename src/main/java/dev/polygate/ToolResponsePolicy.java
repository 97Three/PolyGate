package dev.polygate;

import com.fasterxml.jackson.databind.*;
import com.networknt.schema.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.http.codec.ServerSentEvent;

/** Request-local validation; errors never contain generated argument values. */
final class ToolResponsePolicy {
  private final ObjectMapper json;
  private final boolean strict;
  private final Map<String, JsonSchema> schemas = new HashMap<>();
  private final Map<String, Call> calls = new HashMap<>();
  private final int minimum, maximum;
  private Set<String> allowed;

  ToolResponsePolicy(
      JsonNode request, RoutingService.Route route, ObjectMapper json, boolean anthropic) {
    this.json = json;
    this.strict =
        "strict"
            .equals(
                route.config().path("toolUse").path("responseValidation").asText("best_effort"));
    allowed = new HashSet<>();
    for (JsonNode tool : request.path("tools")) {
      JsonNode definition = anthropic ? tool : tool.path("function");
      String name = definition.path("name").asText();
      allowed.add(name);
      if (strict) {
        JsonNode schema = definition.path(anthropic ? "input_schema" : "parameters");
        if (!schema.isObject()) throw bad("A strict tool requires an object JSON schema");
        checkRefs(schema, 0);
        try {
          schemas.put(
              name,
              JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                  .getSchema(
                      schema,
                      SchemaValidatorsConfig.builder()
                          .preloadJsonSchemaRefMaxNestingDepth(64)
                          .regularExpressionFactory(
                              pattern -> {
                                var compiled = com.google.re2j.Pattern.compile(pattern);
                                return value -> compiled.matcher(value).find();
                              })
                          .build()));
        } catch (Exception e) {
          throw bad("Invalid strict tool schema");
        }
      }
    }
    JsonNode choice = request.path("tool_choice");
    String type =
        anthropic
            ? choice.path("type").asText()
            : choice.isTextual() ? choice.asText() : choice.path("type").asText();
    String required =
        anthropic ? choice.path("name").asText() : choice.path("function").path("name").asText();
    minimum = Set.of("any", "required", "tool", "function").contains(type) ? 1 : 0;
    maximum =
        "none".equals(type)
            ? 0
            : choice.path("disable_parallel_tool_use").asBoolean()
                    || request.has("parallel_tool_calls")
                        && !request.path("parallel_tool_calls").asBoolean()
                    || route.config().path("toolUse").has("parallelToolCalls")
                        && !route.config().path("toolUse").path("parallelToolCalls").asBoolean()
                ? 1
                : 256;
    if (!required.isBlank()) allowed = new HashSet<>(Set.of(required));
  }

  void complete(JsonNode response, boolean anthropic) {
    if (!strict) return;
    calls.clear();
    if (anthropic) {
      long n = 0;
      for (JsonNode block : response.path("content"))
        if ("tool_use".equals(block.path("type").asText())) {
          check(block.path("name").asText(), block.path("input"));
          n++;
        }
      count(n);
    } else {
      for (JsonNode choice : response.path("choices")) {
        for (JsonNode tool : choice.path("message").path("tool_calls"))
          check(
              tool.path("function").path("name").asText(),
              parseArguments(tool.path("function").path("arguments").asText()));
        count(choice.path("message").path("tool_calls").size());
      }
    }
  }

  ServerSentEvent<String> anthropic(ServerSentEvent<String> event) {
    if (!strict || event.data() == null) return event;
    JsonNode value = parse(event.data());
    String type = value.path("type").asText(), index = value.path("index").asText();
    if ("content_block_start".equals(type)
        && "tool_use".equals(value.path("content_block").path("type").asText())) {
      JsonNode block = value.path("content_block");
      calls.put(index, new Call(block.path("name").asText()));
      if (!block.path("input").isEmpty()) calls.get(index).args.append(block.path("input"));
    }
    if ("content_block_delta".equals(type)
        && "input_json_delta".equals(value.path("delta").path("type").asText()))
      append(index, value.path("delta").path("partial_json").asText());
    if ("content_block_stop".equals(type) && calls.containsKey(index)) {
      Call call = calls.get(index);
      check(call.name, parseArguments(call.args.isEmpty() ? "{}" : call.args.toString()));
    }
    if ("message_stop".equals(type)) count(calls.size());
    return event;
  }

  ServerSentEvent<String> openai(ServerSentEvent<String> event) {
    if (!strict || event.data() == null || "[DONE]".equals(event.data())) return event;
    JsonNode value = parse(event.data());
    for (JsonNode choice : value.path("choices")) {
      for (JsonNode tool : choice.path("delta").path("tool_calls")) {
        String id = choice.path("index").asInt() + ":" + tool.path("index").asInt();
        Call c = calls.computeIfAbsent(id, k -> new Call(""));
        if (tool.path("function").has("name"))
          c.name += tool.path("function").path("name").asText();
        append(id, tool.path("function").path("arguments").asText(""));
      }
      if (choice.hasNonNull("finish_reason")) {
        for (Call call : calls.values())
          check(call.name, parseArguments(call.args.isEmpty() ? "{}" : call.args.toString()));
        count(calls.size());
      }
    }
    return event;
  }

  private void append(String id, String chunk) {
    Call c = calls.get(id);
    if (c == null) throw invalid();
    c.args.append(chunk);
    if (c.args.length() > 1048576) throw invalid();
  }

  private void check(String name, JsonNode arguments) {
    if (!allowed.contains(name)) throw invalid();
    if (!arguments.isObject()) throw invalidArguments();
    JsonSchema schema = schemas.get(name);
    try {
      if (schema != null && !schema.validate(arguments).isEmpty()) throw invalidArguments();
    } catch (GatewayException e) {
      throw e;
    } catch (Exception e) {
      throw invalidArguments();
    }
  }

  private void count(long n) {
    if (n < minimum || n > maximum) throw invalid();
  }

  private JsonNode parse(String text) {
    try {
      JsonNode value = json.readTree(text);
      if (value == null) throw invalid();
      return value;
    } catch (Exception e) {
      throw invalid();
    }
  }

  private JsonNode parseArguments(String text) {
    try {
      JsonNode value = json.readTree(text.isBlank() ? "{}" : text);
      if (value == null) throw invalidArguments();
      return value;
    } catch (Exception e) {
      throw invalidArguments();
    }
  }

  private static void checkRefs(JsonNode node, int depth) {
    if (depth > 64) throw bad("Tool schema is too deeply nested");
    if (node.isObject()) {
      if (node.has("$schema")
          && !Set.of(
                  "https://json-schema.org/draft/2020-12/schema",
                  "https://json-schema.org/draft/2019-09/schema",
                  "http://json-schema.org/draft-07/schema#",
                  "http://json-schema.org/draft-06/schema#",
                  "http://json-schema.org/draft-04/schema#")
              .contains(node.path("$schema").asText()))
        throw bad("Custom external schema dialects are not permitted");
      for (String ref : List.of("$ref", "$dynamicRef", "$recursiveRef"))
        if (node.has(ref)
            && (!node.path(ref).isTextual() || !node.path(ref).asText().startsWith("#")))
          throw bad("Tool schemas may reference only local definitions");
    }
    if (node.isContainerNode()) for (JsonNode child : node) checkRefs(child, depth + 1);
  }

  private static final class Call {
    String name;
    final StringBuilder args = new StringBuilder();

    Call(String name) {
      this.name = name;
    }
  }

  private static GatewayException invalid() {
    return new GatewayException(
        HttpStatus.BAD_GATEWAY,
        "upstream_error",
        "tool_validation_failed",
        "Upstream tool response did not satisfy the declared tool contract");
  }

  private static GatewayException bad(String message) {
    return new GatewayException(
        HttpStatus.BAD_REQUEST, "invalid_request_error", "invalid_tool_schema", message);
  }

  private static GatewayException invalidArguments() {
    return new GatewayException(
        HttpStatus.BAD_GATEWAY,
        "upstream_error",
        "tool_arguments_invalid",
        "Upstream tool arguments did not satisfy the declared JSON schema");
  }
}
