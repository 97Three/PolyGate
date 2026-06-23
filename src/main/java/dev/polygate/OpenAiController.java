package dev.polygate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
class OpenAiController {
  private static final Set<String> FIELDS =
      Set.of(
          "model",
          "messages",
          "max_tokens",
          "max_completion_tokens",
          "stream",
          "temperature",
          "top_p",
          "stop",
          "tools",
          "tool_choice",
          "n",
          "store",
          "response_format");
  private final MessagesController anthropic;
  private final ObjectMapper json;
  private final RoutingService routes;
  private final NativeOpenAiGateway nativeGateway;
  private final ClientAuthService clientAuth;

  OpenAiController(
      MessagesController anthropic,
      ObjectMapper json,
      RoutingService routes,
      NativeOpenAiGateway nativeGateway,
      ClientAuthService clientAuth) {
    this.anthropic = anthropic;
    this.json = json;
    this.routes = routes;
    this.nativeGateway = nativeGateway;
    this.clientAuth = clientAuth;
  }

  @PostMapping(value = "/v1/chat/completions", consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> completions(@RequestHeader HttpHeaders headers, @RequestBody JsonNode input) {
    SmartRoutingService.requestInput(input);
    clientAuth.authenticate(headers, input.path("model").asText(""));
    var route = routes.resolve(input.path("model").asText(""));
    if ("openai_compat".equals(route.protocol()))
      return SseResponses.adapt(nativeGateway.forward(headers, input, route));
    ObjectNode converted = convertRequest(input);
    String requestedModel = input.path("model").asText();
    ResponseEntity<?> result = anthropic.forward(headers, converted, "openai");
    if (!converted.path("stream").asBoolean(false)) {
      return ResponseEntity.ok()
          .headers(result.getHeaders())
          .body(convertResponse((JsonNode) result.getBody(), requestedModel));
    }
    @SuppressWarnings("unchecked")
    Flux<ServerSentEvent<String>> events = (Flux<ServerSentEvent<String>>) result.getBody();
    return SseResponses.adapt(
        ResponseEntity.ok()
            .headers(result.getHeaders())
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .body(convertStream(events, requestedModel)));
  }

  private ObjectNode convertRequest(JsonNode input) {
    if (!input.isObject()) throw bad("Request must be a JSON object");
    input
        .fieldNames()
        .forEachRemaining(
            field -> {
              if (!FIELDS.contains(field))
                throw bad("Unsupported Chat Completions field: " + field);
            });
    routes.resolve(input.path("model").asText(""));
    if (input.hasNonNull("n") && input.path("n").asInt(-1) != 1) throw bad("Only n=1 is supported");
    if (input.hasNonNull("store") && !input.path("store").isBoolean()
        || input.path("store").asBoolean(false)) throw bad("store must be false or null");
    if (input.hasNonNull("response_format")
        && !"text".equals(input.path("response_format").path("type").asText()))
      throw bad("Only text response_format is supported");
    if (input.has("stream") && !input.path("stream").isBoolean())
      throw bad("stream must be a boolean");
    int maxTokens =
        input.has("max_completion_tokens")
            ? input.path("max_completion_tokens").asInt(-1)
            : input.path("max_tokens").asInt(1024);
    if (maxTokens < 1
        || input.has("max_tokens")
            && input.has("max_completion_tokens")
            && input.path("max_tokens").asInt(-1) != maxTokens)
      throw bad("Invalid or conflicting max token limit");
    JsonNode messages = input.path("messages");
    if (!messages.isArray() || messages.isEmpty() || messages.size() > 200)
      throw bad("messages must contain 1 to 200 entries");
    ObjectNode converted =
        json.createObjectNode()
            .put("model", input.path("model").asText())
            .put("max_tokens", maxTokens)
            .put("stream", input.path("stream").asBoolean(false));
    for (String name : List.of("temperature", "top_p"))
      if (input.has(name)) converted.set(name, input.path(name));
    if (input.has("stop")) {
      JsonNode stop = input.path("stop");
      if (stop.isTextual()) converted.putArray("stop_sequences").add(stop.asText());
      else if (stop.isArray()) converted.set("stop_sequences", stop);
      else throw bad("stop must be text or an array");
    }
    if (input.has("tools")) converted.set("tools", convertTools(input.path("tools")));
    if (input.has("tool_choice"))
      converted.set("tool_choice", convertToolChoice(input.path("tool_choice")));
    ArrayNode output = converted.putArray("messages");
    List<String> system = new ArrayList<>();
    boolean conversationStarted = false;
    for (JsonNode message : messages) {
      String role = message.path("role").asText("");
      if ("system".equals(role) || "developer".equals(role)) {
        if (conversationStarted || !message.path("content").isTextual())
          throw bad("System/developer messages must be text before the conversation");
        system.add(message.path("content").asText());
        continue;
      }
      conversationStarted = true;
      if (message.hasNonNull("reasoning_content"))
        throw bad("reasoning_content cannot be preserved by the Anthropic provider");
      switch (role) {
        case "user" -> output.add(convertTextMessage("user", message.path("content")));
        case "assistant" -> output.add(convertAssistant(message));
        case "tool" -> output.add(convertToolResult(message));
        default -> throw bad("Unsupported message role: " + role);
      }
    }
    if (output.isEmpty()) throw bad("At least one conversation message is required");
    if (!system.isEmpty()) converted.put("system", String.join("\n\n", system));
    return converted;
  }

  private ObjectNode convertTextMessage(String role, JsonNode content) {
    ObjectNode result = json.createObjectNode().put("role", role);
    if (content.isTextual()) return result.put("content", content.asText());
    if (!content.isArray() || content.isEmpty())
      throw bad("Only text message content is supported");
    ArrayNode blocks = result.putArray("content");
    for (JsonNode part : content) {
      if (!"text".equals(part.path("type").asText()) || !part.path("text").isTextual())
        throw bad("Only text content parts are supported");
      blocks.addObject().put("type", "text").put("text", part.path("text").asText());
    }
    return result;
  }

  private ObjectNode convertAssistant(JsonNode message) {
    ObjectNode result = json.createObjectNode().put("role", "assistant");
    ArrayNode blocks = result.putArray("content");
    JsonNode content = message.path("content");
    if (content.isTextual() && !content.asText().isEmpty())
      blocks.addObject().put("type", "text").put("text", content.asText());
    else if (!content.isNull() && !content.isMissingNode() && !content.isTextual()) {
      if (!content.isArray()) throw bad("Assistant content must be text or text parts");
      for (JsonNode part : content) {
        if (!"text".equals(part.path("type").asText()) || !part.path("text").isTextual())
          throw bad("Only text assistant parts are supported");
        blocks.addObject().put("type", "text").put("text", part.path("text").asText());
      }
    }
    JsonNode calls = message.path("tool_calls");
    if (!calls.isMissingNode()) {
      if (!calls.isArray()) throw bad("tool_calls must be an array");
      for (JsonNode call : calls) {
        if (!"function".equals(call.path("type").asText())
            || !call.path("id").isTextual()
            || !call.path("function").path("name").isTextual())
          throw bad("Invalid function tool call");
        try {
          JsonNode args = json.readTree(call.path("function").path("arguments").asText("{}"));
          if (!args.isObject()) throw bad("Tool arguments must be a JSON object");
          blocks
              .addObject()
              .put("type", "tool_use")
              .put("id", call.path("id").asText())
              .put("name", call.path("function").path("name").asText())
              .set("input", args);
        } catch (GatewayException e) {
          throw e;
        } catch (Exception e) {
          throw bad("Tool arguments must be valid JSON");
        }
      }
    }
    if (blocks.isEmpty()) throw bad("Assistant message has no content");
    return result;
  }

  private ObjectNode convertToolResult(JsonNode message) {
    if (!message.path("tool_call_id").isTextual() || !message.path("content").isTextual())
      throw bad("Tool result needs tool_call_id and text content");
    ObjectNode result = json.createObjectNode().put("role", "user");
    result
        .putArray("content")
        .addObject()
        .put("type", "tool_result")
        .put("tool_use_id", message.path("tool_call_id").asText())
        .put("content", message.path("content").asText());
    return result;
  }

  private ArrayNode convertTools(JsonNode tools) {
    if (!tools.isArray()) throw bad("tools must be an array");
    ArrayNode result = json.createArrayNode();
    Set<String> names = new HashSet<>();
    for (JsonNode tool : tools) {
      JsonNode function = tool.path("function");
      String name = function.path("name").asText("");
      if (!"function".equals(tool.path("type").asText())
          || name.isBlank()
          || !names.add(name)
          || !function.path("parameters").isObject()
          || function.path("strict").asBoolean(false))
        throw bad("Invalid or strict function tool definition");
      ObjectNode converted = result.addObject().put("name", name);
      if (function.path("description").isTextual())
        converted.put("description", function.path("description").asText());
      converted.set("input_schema", function.path("parameters"));
    }
    return result;
  }

  private ObjectNode convertToolChoice(JsonNode choice) {
    ObjectNode result = json.createObjectNode();
    if (choice.isTextual()) {
      String type = choice.asText();
      if ("auto".equals(type)) return result.put("type", "auto");
      if ("required".equals(type)) return result.put("type", "any");
      throw bad("Unsupported tool_choice");
    }
    if ("function".equals(choice.path("type").asText())
        && choice.path("function").path("name").isTextual()) {
      return result.put("type", "tool").put("name", choice.path("function").path("name").asText());
    }
    throw bad("Unsupported tool_choice");
  }

  private ObjectNode convertResponse(JsonNode response, String requestedModel) {
    if (!response.path("content").isArray()) throw upstreamProtocol();
    StringBuilder content = new StringBuilder();
    ArrayNode toolCalls = json.createArrayNode();
    for (JsonNode block : response.path("content")) {
      String type = block.path("type").asText();
      if ("text".equals(type)) content.append(block.path("text").asText(""));
      else if ("tool_use".equals(type)) {
        ObjectNode call =
            toolCalls.addObject().put("id", block.path("id").asText()).put("type", "function");
        call.putObject("function")
            .put("name", block.path("name").asText())
            .put("arguments", block.path("input").toString());
      } else throw upstreamProtocol();
    }
    ObjectNode result =
        json.createObjectNode()
            .put("id", completionId())
            .put("object", "chat.completion")
            .put("created", Instant.now().getEpochSecond())
            .put("model", requestedModel);
    ObjectNode choice = result.putArray("choices").addObject().put("index", 0);
    ObjectNode message = choice.putObject("message").put("role", "assistant");
    if (content.isEmpty() && !toolCalls.isEmpty()) message.putNull("content");
    else message.put("content", content.toString());
    if (!toolCalls.isEmpty()) message.set("tool_calls", toolCalls);
    choice.put("finish_reason", finishReason(response.path("stop_reason").asText("end_turn")));
    JsonNode usage = response.path("usage");
    int prompt =
        usage.path("input_tokens").asInt(0)
            + usage.path("cache_creation_input_tokens").asInt(0)
            + usage.path("cache_read_input_tokens").asInt(0);
    int completion = usage.path("output_tokens").asInt(0);
    result
        .putObject("usage")
        .put("prompt_tokens", prompt)
        .put("completion_tokens", completion)
        .put("total_tokens", prompt + completion);
    return result;
  }

  private Flux<ServerSentEvent<String>> convertStream(
      Flux<ServerSentEvent<String>> events, String requestedModel) {
    String id = completionId();
    Map<Integer, Integer> toolIndexes = new HashMap<>();
    int[] nextTool = {0};
    return events
        .<ServerSentEvent<String>>handle(
            (event, sink) -> {
              try {
                String kind = event.event();
                JsonNode data =
                    event.data() == null ? json.createObjectNode() : json.readTree(event.data());
                ObjectNode delta = json.createObjectNode();
                String finish = null;
                if ("message_start".equals(kind)) delta.put("role", "assistant");
                else if ("content_block_start".equals(kind)
                    && "tool_use".equals(data.path("content_block").path("type").asText())) {
                  int tool = nextTool[0]++;
                  toolIndexes.put(data.path("index").asInt(), tool);
                  ObjectNode call =
                      delta
                          .putArray("tool_calls")
                          .addObject()
                          .put("index", tool)
                          .put("id", data.path("content_block").path("id").asText())
                          .put("type", "function");
                  call.putObject("function")
                      .put("name", data.path("content_block").path("name").asText())
                      .put("arguments", "");
                } else if ("content_block_delta".equals(kind)) {
                  JsonNode fragment = data.path("delta");
                  if ("text_delta".equals(fragment.path("type").asText()) || fragment.has("text"))
                    delta.put("content", fragment.path("text").asText());
                  else if ("input_json_delta".equals(fragment.path("type").asText())) {
                    Integer index = toolIndexes.get(data.path("index").asInt());
                    if (index == null) throw upstreamProtocol();
                    delta
                        .putArray("tool_calls")
                        .addObject()
                        .put("index", index)
                        .putObject("function")
                        .put("arguments", fragment.path("partial_json").asText());
                  }
                } else if ("message_delta".equals(kind))
                  finish = finishReason(data.path("delta").path("stop_reason").asText("end_turn"));
                else if ("error".equals(kind)) {
                  sink.next(ServerSentEvent.builder(event.data()).build());
                  return;
                }
                if (delta.isEmpty() && finish == null) return;
                ObjectNode chunk =
                    json.createObjectNode()
                        .put("id", id)
                        .put("object", "chat.completion.chunk")
                        .put("created", Instant.now().getEpochSecond())
                        .put("model", requestedModel);
                ObjectNode choice = chunk.putArray("choices").addObject().put("index", 0);
                choice.set("delta", delta);
                if (finish == null) choice.putNull("finish_reason");
                else choice.put("finish_reason", finish);
                sink.next(ServerSentEvent.builder(chunk.toString()).build());
              } catch (Exception error) {
                sink.error(upstreamProtocol());
              }
            })
        .concatWith(Mono.just(ServerSentEvent.<String>builder("[DONE]").build()));
  }

  private static String finishReason(String reason) {
    return switch (reason) {
      case "max_tokens" -> "length";
      case "tool_use" -> "tool_calls";
      default -> "stop";
    };
  }

  private static String completionId() {
    return "chatcmpl-" + UUID.randomUUID().toString().replace("-", "");
  }

  private static GatewayException bad(String detail) {
    return new GatewayException(
        HttpStatus.BAD_REQUEST, "invalid_request_error", "invalid_request", detail);
  }

  private static GatewayException upstreamProtocol() {
    return new GatewayException(
        HttpStatus.BAD_GATEWAY,
        "upstream_error",
        "upstream_protocol",
        "Invalid Anthropic response from upstream");
  }
}
