package dev.polygate;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.http.*;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/** Per-request stream rewriting. Reasoning signatures are never altered. */
final class StreamTextNormalizer {
  private final Map<Integer, StringBuilder> blocks = new HashMap<>();
  private final ObjectMapper json;
  private final boolean deduplicate;
  private final String argumentMode;
  private final Map<String, StringBuilder> arguments = new HashMap<>();

  StreamTextNormalizer(ObjectMapper json, boolean deduplicate) {
    this.json = json;
    this.deduplicate = deduplicate;
    this.argumentMode = "native";
  }

  StreamTextNormalizer(ObjectMapper json, JsonNode config) {
    this.json = json;
    deduplicate = config.path("deduplicateStreamText").asBoolean();
    argumentMode = config.path("toolUse").path("streamingArguments").asText("native");
  }

  private boolean rewriteArguments() {
    return Set.of("cumulative", "best_effort").contains(argumentMode);
  }

  ServerSentEvent<String> anthropic(ServerSentEvent<String> event) {
    if ((!deduplicate && !rewriteArguments()) || event.data() == null) return event;
    try {
      JsonNode node = json.readTree(event.data());
      int index = node.path("index").asInt();
      if ("content_block_start".equals(node.path("type").asText())
          && "text".equals(node.path("content_block").path("type").asText()))
        blocks.put(index, new StringBuilder(node.path("content_block").path("text").asText("")));
      if (deduplicate
          && "content_block_delta".equals(node.path("type").asText())
          && "text_delta".equals(node.path("delta").path("type").asText())
          && blocks.containsKey(index))
        ((ObjectNode) node.path("delta"))
            .put("text", delta(blocks.get(index), node.path("delta").path("text").asText("")));
      if (rewriteArguments() && "input_json_delta".equals(node.path("delta").path("type").asText()))
        ((ObjectNode) node.path("delta"))
            .put(
                "partial_json",
                argument("block:" + index, node.path("delta").path("partial_json").asText()));
      return ServerSentEvent.<String>builder(node.toString())
          .event(event.event())
          .id(event.id())
          .build();
    } catch (GatewayException e) {
      throw e;
    } catch (Exception e) {
      throw invalid();
    }
  }

  ServerSentEvent<String> openai(ServerSentEvent<String> event) {
    if ((!deduplicate && !rewriteArguments())
        || event.data() == null
        || "[DONE]".equals(event.data())) return event;
    try {
      JsonNode node = json.readTree(event.data());
      for (JsonNode choice : node.path("choices")) {
        JsonNode d = choice.path("delta");
        if (deduplicate && d.path("content").isTextual())
          ((ObjectNode) d)
              .put(
                  "content",
                  delta(
                      blocks.computeIfAbsent(
                          choice.path("index").asInt(), i -> new StringBuilder()),
                      d.path("content").asText()));
        if (rewriteArguments())
          for (JsonNode tool : d.path("tool_calls"))
            if (tool.path("function").path("arguments").isTextual())
              ((ObjectNode) tool.path("function"))
                  .put(
                      "arguments",
                      argument(
                          choice.path("index").asInt() + ":" + tool.path("index").asInt(),
                          tool.path("function").path("arguments").asText()));
      }
      return ServerSentEvent.<String>builder(node.toString())
          .event(event.event())
          .id(event.id())
          .build();
    } catch (GatewayException e) {
      throw e;
    } catch (Exception e) {
      throw invalid();
    }
  }

  static String delta(StringBuilder seen, String content) {
    String prior = seen.toString();
    if (content.isEmpty()) return "";
    String result;
    if (content.startsWith(prior)) {
      result = content.substring(prior.length());
      seen.setLength(0);
      seen.append(content);
    } else if (prior.startsWith(content)
        || prior.endsWith(content)
        || content.codePointCount(0, content.length()) >= 3 && prior.contains(content)) return "";
    else {
      int overlap = 0;
      for (int i = 1; i <= content.length(); i++) {
        if (i < content.length() && Character.isLowSurrogate(content.charAt(i))) continue;
        if (content.codePointCount(0, i) >= 2 && prior.endsWith(content.substring(0, i)))
          overlap = i;
      }
      result = content.substring(overlap);
      seen.append(result);
    }
    if (seen.length() > 4_194_304) throw invalid();
    return result;
  }

  private String argument(String id, String content) {
    StringBuilder seen = arguments.computeIfAbsent(id, k -> new StringBuilder());
    if ("best_effort".equals(argumentMode)) return delta(seen, content);
    String prior = seen.toString();
    if (prior.startsWith(content)) return "";
    if (!content.startsWith(prior) || content.length() > 1048576) throw invalid();
    seen.setLength(0);
    seen.append(content);
    return content.substring(prior.length());
  }

  static Flux<ServerSentEvent<String>> buffer(
      Flux<ServerSentEvent<String>> source, boolean enabled) {
    if (!enabled) return source;
    return Flux.defer(
        () -> {
          long[] bytes = {0};
          return source
              .doOnNext(
                  e -> {
                    bytes[0] += e.data() == null ? 0 : e.data().length();
                    if (bytes[0] > 4_194_304) throw invalid();
                  })
              .collectList()
              .flatMapMany(Flux::fromIterable);
        });
  }

  private static GatewayException invalid() {
    return new GatewayException(
        HttpStatus.BAD_GATEWAY,
        "upstream_error",
        "stream_normalization_failure",
        "Upstream stream could not be safely normalized within the configured response limit");
  }
}
