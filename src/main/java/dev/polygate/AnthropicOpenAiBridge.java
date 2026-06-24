package dev.polygate;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.*;

@Service
class AnthropicOpenAiBridge {
  private final NativeOpenAiGateway gateway;
  private final ObjectMapper json;
  private final RequestLogService logs;

  AnthropicOpenAiBridge(NativeOpenAiGateway gateway, ObjectMapper json, RequestLogService logs) {
    this.gateway = gateway;
    this.json = json;
    this.logs = logs;
  }

  ResponseEntity<?> forward(HttpHeaders headers, JsonNode input, RoutingService.Route route) {
    ObjectNode converted = request(input, route);
    ResponseEntity<?> response = gateway.forward(headers, converted, route, "anthropic");
    UUID id = UUID.fromString(response.getHeaders().getFirst("x-polygate-request-id"));
    if (!input.path("stream").asBoolean())
      try {
        return ResponseEntity.ok()
            .headers(response.getHeaders())
            .body(response((JsonNode) response.getBody(), route.requestedModel()));
      } catch (RuntimeException error) {
        logs.deliveryFailed(id);
        throw error;
      }
    @SuppressWarnings("unchecked")
    Flux<ServerSentEvent<String>> events = (Flux<ServerSentEvent<String>>) response.getBody();
    return ResponseEntity.ok()
        .headers(response.getHeaders())
        .contentType(MediaType.TEXT_EVENT_STREAM)
        .body(stream(events, route.requestedModel(), id));
  }

  private ObjectNode request(JsonNode input, RoutingService.Route route) {
    for (String field : List.of("context_management"))
      if (input.has(field)) throw bad(field + " cannot be preserved by this OpenAI adapter");
    if (input.has("thinking") && "none".equals(GenerationPolicy.dialect(route)))
      throw bad("thinking requires a configured reasoning dialect");
    ObjectNode output = json.createObjectNode();
    if (input.has("thinking")) output.set("thinking", input.path("thinking"));
    output.put("model", input.path("model").asText());
    output.set("max_tokens", input.path("max_tokens"));
    output.put("stream", input.path("stream").asBoolean());
    for (String field : List.of("temperature", "top_p", "top_k", "metadata", "service_tier"))
      if (input.has(field)) output.set(field, input.path(field));
    if (input.has("stop_sequences")) output.set("stop", input.path("stop_sequences"));
    if (output.path("stream").asBoolean())
      output.putObject("stream_options").put("include_usage", true);
    ArrayNode messages = output.putArray("messages");
    if (input.has("system"))
      messages.addObject().put("role", "system").put("content", text(input.path("system")));
    for (JsonNode message : input.path("messages")) {
      String role = message.path("role").asText();
      JsonNode content = message.path("content");
      if (content.isTextual()) {
        messages.addObject().put("role", role).put("content", content.asText());
        continue;
      }
      if ("assistant".equals(role)) {
        ObjectNode assistant = json.createObjectNode().put("role", "assistant");
        ArrayNode parts = json.createArrayNode(), tools = json.createArrayNode();
        StringBuilder reasoning = new StringBuilder();
        for (JsonNode block : content) {
          rejectCache(block);
          switch (block.path("type").asText()) {
            case "text" ->
                parts.addObject().put("type", "text").put("text", block.path("text").asText());
            case "tool_use" -> {
              ObjectNode call =
                  tools.addObject().put("id", block.path("id").asText()).put("type", "function");
              call.putObject("function")
                  .put("name", block.path("name").asText())
                  .put("arguments", block.path("input").toString());
            }
            case "thinking" -> {
              if (!block.path("signature").asText().isEmpty())
                throw bad("Signed Anthropic reasoning cannot be replayed to an OpenAI provider");
              reasoning.append(block.path("thinking").asText());
            }
            default -> throw bad("Unsupported assistant block for this OpenAI adapter");
          }
        }
        if (parts.isEmpty()) assistant.putNull("content");
        else assistant.set("content", parts);
        if (!tools.isEmpty()) assistant.set("tool_calls", tools);
        if (!reasoning.isEmpty()) assistant.put("reasoning_content", reasoning.toString());
        messages.add(assistant);
      } else {
        ArrayNode parts = json.createArrayNode();
        for (JsonNode block : content) {
          rejectCache(block);
          String type = block.path("type").asText();
          if ("tool_result".equals(type)) {
            if (!parts.isEmpty()) {
              messages.addObject().put("role", "user").set("content", parts);
              parts = json.createArrayNode();
            }
            String result = text(block.path("content"));
            if (block.path("is_error").asBoolean()) result = "[tool error]\n" + result;
            messages
                .addObject()
                .put("role", "tool")
                .put("tool_call_id", block.path("tool_use_id").asText())
                .put("content", result);
          } else if ("text".equals(type))
            parts.addObject().put("type", "text").put("text", block.path("text").asText());
          else if ("image".equals(type)) {
            JsonNode source = block.path("source");
            String url =
                "url".equals(source.path("type").asText())
                    ? source.path("url").asText()
                    : "data:"
                        + source.path("media_type").asText()
                        + ";base64,"
                        + source.path("data").asText();
            parts.addObject().put("type", "image_url").putObject("image_url").put("url", url);
          } else throw bad("Unsupported user block for this OpenAI adapter");
        }
        if (!parts.isEmpty()) messages.addObject().put("role", "user").set("content", parts);
      }
    }
    if (input.has("tools")) {
      ArrayNode tools = output.putArray("tools");
      for (JsonNode tool : input.path("tools")) {
        rejectCache(tool);
        ObjectNode function = tools.addObject().put("type", "function").putObject("function");
        function.put("name", tool.path("name").asText());
        if (tool.has("description")) function.set("description", tool.path("description"));
        function.set("parameters", tool.path("input_schema"));
        if (tool.has("strict")) function.set("strict", tool.path("strict"));
      }
    }
    if (input.has("tool_choice")) {
      JsonNode choice = input.path("tool_choice");
      switch (choice.path("type").asText()) {
        case "auto" -> output.put("tool_choice", "auto");
        case "none" -> output.put("tool_choice", "none");
        case "any" -> output.put("tool_choice", "required");
        case "tool" ->
            output
                .putObject("tool_choice")
                .put("type", "function")
                .putObject("function")
                .put("name", choice.path("name").asText());
        default -> throw bad("Invalid tool choice");
      }
      if (choice.has("disable_parallel_tool_use"))
        output.put("parallel_tool_calls", !choice.path("disable_parallel_tool_use").asBoolean());
    }
    if (input.has("output_config")) {
      JsonNode config = input.path("output_config");
      if (config.has("effort")) output.set("reasoning_effort", config.path("effort"));
      if (config.has("format")) {
        JsonNode format = config.path("format");
        if (!"json_schema".equals(format.path("type").asText())
            || !format.path("schema").isObject()) throw bad("Unsupported output format");
        output
            .putObject("response_format")
            .put("type", "json_schema")
            .putObject("json_schema")
            .put("name", "response")
            .set("schema", format.path("schema"));
      }
    }
    return output;
  }

  private ObjectNode response(JsonNode input, String model) {
    if (input.path("choices").size() != 1) throw protocol();
    JsonNode choice = input.path("choices").get(0), message = choice.path("message");
    ObjectNode output = message(input.path("id").asText("msg_" + UUID.randomUUID()), model);
    ArrayNode blocks = (ArrayNode) output.path("content");
    if (message.path("reasoning_content").isTextual()
        && !message.path("reasoning_content").asText().isEmpty())
      blocks
          .addObject()
          .put("type", "thinking")
          .put("thinking", message.path("reasoning_content").asText())
          .put("signature", "");
    JsonNode content = message.path("content");
    if (content.isTextual() && !content.asText().isEmpty())
      blocks.addObject().put("type", "text").put("text", content.asText());
    else if (content.isArray())
      for (JsonNode part : content) {
        if (!"text".equals(part.path("type").asText())) throw protocol();
        blocks.addObject().put("type", "text").put("text", part.path("text").asText());
      }
    for (JsonNode call : message.path("tool_calls")) {
      ObjectNode block =
          blocks
              .addObject()
              .put("type", "tool_use")
              .put("id", call.path("id").asText())
              .put("name", call.path("function").path("name").asText());
      block.set("input", arguments(call.path("function").path("arguments").asText()));
    }
    output.put("stop_reason", stop(choice.path("finish_reason").asText()));
    output.set("usage", usage(input.path("usage")));
    return output;
  }

  private Flux<ServerSentEvent<String>> stream(
      Flux<ServerSentEvent<String>> input, String model, UUID id) {
    return Flux.defer(
        () -> {
          StreamState state = new StreamState(model);
          return input
              .concatMap(
                  event -> {
                    String data = event.data();
                    if (data == null) return Flux.empty();
                    List<ServerSentEvent<String>> out = new ArrayList<>();
                    try {
                      if ("[DONE]".equals(data)) {
                        state.finish(out);
                        return Flux.fromIterable(out);
                      }
                      JsonNode value = json.readTree(data);
                      if (value.has("error")) throw protocol();
                      state.start(value.path("id").asText(), out);
                      if (value.path("usage").isObject()) state.usage = usage(value.path("usage"));
                      for (JsonNode choice : value.path("choices")) {
                        if (choice.path("index").asInt() != 0) throw protocol();
                        JsonNode delta = choice.path("delta");
                        if (delta.path("reasoning_content").isTextual())
                          state.delta("thinking", delta.path("reasoning_content").asText(), out);
                        if (delta.path("content").isTextual())
                          state.delta("text", delta.path("content").asText(), out);
                        for (JsonNode call : delta.path("tool_calls")) {
                          int index = call.path("index").asInt(-1);
                          if (index < 0) throw protocol();
                          Tool tool = state.tools.computeIfAbsent(index, key -> new Tool());
                          if (call.has("id")) tool.id = call.path("id").asText();
                          if (call.path("function").has("name")) {
                            String name = call.path("function").path("name").asText();
                            if (tool.name.isEmpty()) tool.name = name;
                            else if (!tool.name.equals(name)) throw protocol();
                          }
                          if (call.path("function").has("arguments"))
                            tool.arguments.append(call.path("function").path("arguments").asText());
                        }
                        if (choice.hasNonNull("finish_reason"))
                          state.reason = choice.path("finish_reason").asText();
                      }
                      return Flux.fromIterable(out);
                    } catch (Exception e) {
                      return Flux.error(protocol());
                    }
                  })
              .concatWith(Mono.defer(() -> state.ended ? Mono.empty() : Mono.error(protocol())))
              .onErrorResume(
                  error -> {
                    logs.deliveryFailed(id);
                    return Flux.just(
                        event(
                            "error",
                            json.createObjectNode()
                                .put("type", "error")
                                .set(
                                    "error",
                                    json.createObjectNode()
                                        .put("type", "upstream_error")
                                        .put(
                                            "message",
                                            "Upstream stream could not be converted faithfully"))));
                  });
        });
  }

  private class StreamState {
    final String model;
    final Map<Integer, Tool> tools = new TreeMap<>();
    int nextIndex, current = -1;
    String currentType = "", reason = "";
    boolean started, ended;
    ObjectNode usage = json.createObjectNode().put("input_tokens", 0).put("output_tokens", 0);

    StreamState(String model) {
      this.model = model;
    }

    void start(String id, List<ServerSentEvent<String>> out) {
      if (started) return;
      started = true;
      out.add(
          event(
              "message_start",
              json.createObjectNode()
                  .put("type", "message_start")
                  .set("message", message(id.isBlank() ? "msg_" + UUID.randomUUID() : id, model))));
    }

    void close(List<ServerSentEvent<String>> out) {
      if (current < 0) return;
      if ("thinking".equals(currentType))
        out.add(
            event(
                "content_block_delta",
                json.createObjectNode()
                    .put("type", "content_block_delta")
                    .put("index", current)
                    .set(
                        "delta",
                        json.createObjectNode()
                            .put("type", "signature_delta")
                            .put("signature", ""))));
      out.add(
          event(
              "content_block_stop",
              json.createObjectNode().put("type", "content_block_stop").put("index", current)));
      current = -1;
    }

    void delta(String type, String text, List<ServerSentEvent<String>> out) {
      if (text.isEmpty()) return;
      if (!type.equals(currentType) || current < 0) {
        close(out);
        current = nextIndex++;
        currentType = type;
        ObjectNode block = json.createObjectNode().put("type", type);
        if ("thinking".equals(type)) block.put("thinking", "").put("signature", "");
        else block.put("text", "");
        out.add(
            event(
                "content_block_start",
                json.createObjectNode()
                    .put("type", "content_block_start")
                    .put("index", current)
                    .set("content_block", block)));
      }
      ObjectNode delta =
          json.createObjectNode()
              .put("type", "thinking".equals(type) ? "thinking_delta" : "text_delta")
              .put("thinking".equals(type) ? "thinking" : "text", text);
      out.add(
          event(
              "content_block_delta",
              json.createObjectNode()
                  .put("type", "content_block_delta")
                  .put("index", current)
                  .set("delta", delta)));
    }

    void finish(List<ServerSentEvent<String>> out) {
      if (ended || reason.isBlank()) throw protocol();
      start("", out);
      close(out);
      for (Tool tool : tools.values()) {
        if (tool.id.isBlank() || tool.name.isBlank()) throw protocol();
        JsonNode args = arguments(tool.arguments.toString());
        int index = nextIndex++;
        ObjectNode block =
            json.createObjectNode()
                .put("type", "tool_use")
                .put("id", tool.id)
                .put("name", tool.name);
        block.set("input", json.createObjectNode());
        out.add(
            event(
                "content_block_start",
                json.createObjectNode()
                    .put("type", "content_block_start")
                    .put("index", index)
                    .set("content_block", block)));
        out.add(
            event(
                "content_block_delta",
                json.createObjectNode()
                    .put("type", "content_block_delta")
                    .put("index", index)
                    .set(
                        "delta",
                        json.createObjectNode()
                            .put("type", "input_json_delta")
                            .put("partial_json", args.toString()))));
        out.add(
            event(
                "content_block_stop",
                json.createObjectNode().put("type", "content_block_stop").put("index", index)));
      }
      out.add(
          event(
              "message_delta",
              json.createObjectNode()
                  .put("type", "message_delta")
                  .set(
                      "delta",
                      json.createObjectNode()
                          .put("stop_reason", stop(reason))
                          .putNull("stop_sequence"))));
      ObjectNode delta = (ObjectNode) parse(out.removeLast().data());
      delta.set("usage", usage);
      out.add(event("message_delta", delta));
      out.add(event("message_stop", json.createObjectNode().put("type", "message_stop")));
      ended = true;
    }
  }

  private static class Tool {
    String id = "", name = "";
    StringBuilder arguments = new StringBuilder();
  }

  private ObjectNode message(String id, String model) {
    ObjectNode output =
        json.createObjectNode()
            .put("id", id)
            .put("type", "message")
            .put("role", "assistant")
            .put("model", model);
    output.putArray("content");
    output.putNull("stop_reason").putNull("stop_sequence");
    output.putObject("usage").put("input_tokens", 0).put("output_tokens", 0);
    return output;
  }

  private ObjectNode usage(JsonNode value) {
    long cached = value.path("prompt_tokens_details").path("cached_tokens").asLong(0);
    return json.createObjectNode()
        .put("input_tokens", Math.max(0, value.path("prompt_tokens").asLong() - cached))
        .put("output_tokens", value.path("completion_tokens").asLong())
        .put("cache_read_input_tokens", cached);
  }

  private JsonNode arguments(String value) {
    try {
      JsonNode input = json.readTree(value.isBlank() ? "{}" : value);
      if (!input.isObject()) throw protocol();
      return input;
    } catch (Exception e) {
      throw protocol();
    }
  }

  private JsonNode parse(String value) {
    try {
      return json.readTree(value);
    } catch (Exception e) {
      throw protocol();
    }
  }

  private String text(JsonNode value) {
    if (value.isTextual()) return value.asText();
    if (!value.isArray()) throw bad("Tool results must contain text for this OpenAI adapter");
    List<String> text = new ArrayList<>();
    for (JsonNode block : value) {
      rejectCache(block);
      if (!"text".equals(block.path("type").asText()))
        throw bad("This OpenAI adapter cannot preserve non-text tool results");
      text.add(block.path("text").asText());
    }
    return String.join("\n", text);
  }

  private static void rejectCache(JsonNode value) {
    if (value.has("cache_control"))
      throw bad("Explicit Anthropic cache controls cannot be preserved by this OpenAI adapter");
  }

  private static String stop(String value) {
    return switch (value) {
      case "length" -> "max_tokens";
      case "tool_calls", "function_call" -> "tool_use";
      case "content_filter" -> "refusal";
      default -> "end_turn";
    };
  }

  private ServerSentEvent<String> event(String name, JsonNode value) {
    return ServerSentEvent.<String>builder(value.toString()).event(name).build();
  }

  private static GatewayException bad(String message) {
    return new GatewayException(
        HttpStatus.BAD_REQUEST, "invalid_request_error", "invalid_request", message);
  }

  private static GatewayException protocol() {
    return new GatewayException(
        HttpStatus.BAD_GATEWAY,
        "upstream_error",
        "upstream_protocol",
        "Upstream response cannot be converted faithfully");
  }
}
