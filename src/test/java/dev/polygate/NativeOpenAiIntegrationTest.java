package dev.polygate;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NativeOpenAiIntegrationTest {
  static HttpServer upstream;
  static final ObjectMapper JSON = new ObjectMapper();
  static final AtomicReference<JsonNode> captured = new AtomicReference<>();
  @Autowired TestRestTemplate rest;

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry properties) throws Exception {
    upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    upstream.createContext(
        "/v1/chat/completions",
        exchange -> {
          JsonNode request = JSON.readTree(exchange.getRequestBody());
          captured.set(request);
          boolean stream = request.path("stream").asBoolean();
          String body;
          if (stream) {
            body =
                "data:"
                    + " {\"id\":\"chat-test\",\"object\":\"chat.completion.chunk\",\"model\":\"upstream-model\",\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"reason\",\"tool_calls\":[{\"index\":0,\"id\":\"tool-1\",\"type\":\"function\",\"function\":{\"name\":\"echo\",\"arguments\":\"{\"}}]},\"finish_reason\":null}]}\n\n"
                    + "data:"
                    + " {\"id\":\"chat-test\",\"model\":\"upstream-model\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n"
                    + "data:"
                    + " {\"id\":\"chat-test\",\"choices\":[],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":2}}\n\n";
            if (!request.toString().contains("truncate")) body += "data: [DONE]\n\n";
          } else
            body =
                "{\"id\":\"chat-test\",\"object\":\"chat.completion\",\"model\":\"upstream-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"hello\",\"reasoning_content\":\"reason\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":2}}";
          exchange
              .getResponseHeaders()
              .set("Content-Type", stream ? "text/event-stream" : "application/json");
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    upstream.start();
    properties.add("polygate.client-token", () -> "native-client");
    properties.add("polygate.upstream-key", () -> "native-upstream");
    properties.add(
        "polygate.upstream-base-url",
        () -> "http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1");
    properties.add("polygate.upstream-protocol", () -> "openai_compat");
    properties.add("polygate.public-model", () -> "native-model");
    properties.add("polygate.upstream-model", () -> "upstream-model");
  }

  @AfterAll
  static void stop() {
    if (upstream != null) upstream.stop(0);
  }

  HttpHeaders headers() {
    var headers = new HttpHeaders();
    headers.setBearerAuth("native-client");
    return headers;
  }

  @Test
  void preservesReasoningImagesAndToolReplay() throws Exception {
    JsonNode input =
        JSON.readTree(
            """
{"model":"native-model","max_tokens":16,"reasoning_effort":"low","messages":[
  {"role":"user","content":[{"type":"text","text":"look"},{"type":"image_url","image_url":{"url":"data:image/png;base64,aGk="}}]},
  {"role":"assistant","content":null,"reasoning_content":"reason","tool_calls":[{"id":"tool-1","type":"function","function":{"name":"echo","arguments":"{}"}}]},
  {"role":"tool","tool_call_id":"tool-1","content":"ok"}]}
""");
    var response =
        rest.exchange(
            "/v1/chat/completions",
            HttpMethod.POST,
            new HttpEntity<>(input, headers()),
            String.class);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(captured.get().path("messages")).isEqualTo(input.path("messages"));
    assertThat(captured.get().path("max_completion_tokens").asInt()).isEqualTo(16);
    assertThat(captured.get().path("model").asText()).isEqualTo("upstream-model");
    assertThat(
            JSON.readTree(response.getBody())
                .path("choices")
                .get(0)
                .path("message")
                .path("reasoning_content")
                .asText())
        .isEqualTo("reason");
    assertThat(JSON.readTree(response.getBody()).path("model").asText()).isEqualTo("native-model");
  }

  @Test
  void preservesStreamingToolsReasoningAndRequestedUsage() {
    var input =
        Map.of(
            "model",
            "native-model",
            "stream",
            true,
            "stream_options",
            Map.of("include_usage", true),
            "messages",
            List.of(Map.of("role", "user", "content", "hi")));
    var response =
        rest.exchange(
            "/v1/chat/completions",
            HttpMethod.POST,
            new HttpEntity<>(input, headers()),
            String.class);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody())
        .contains("reasoning_content", "tool_calls", "prompt_tokens", "[DONE]")
        .doesNotContain("\"model\":\"upstream-model\"");
  }

  @Test
  void emitsAnErrorForTruncatedStream() {
    var response =
        rest.exchange(
            "/v1/chat/completions",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "model",
                    "native-model",
                    "stream",
                    true,
                    "messages",
                    List.of(Map.of("role", "user", "content", "truncate"))),
                headers()),
            String.class);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).contains("Upstream stream failed");
  }

  @Test
  void adaptsAnthropicImagesAndToolContinuationToOpenAi() throws Exception {
    JsonNode input =
        JSON.readTree(
            """
{"model":"native-model","max_tokens":16,"tools":[{"name":"echo","input_schema":{"type":"object"}}],"messages":[
{"role":"user","content":[{"type":"image","source":{"type":"base64","media_type":"image/png","data":"aGk="}}]},
{"role":"assistant","content":[{"type":"thinking","thinking":"reason","signature":""},{"type":"tool_use","id":"tool-1","name":"echo","input":{}}]},
{"role":"user","content":[{"type":"tool_result","tool_use_id":"tool-1","content":"ok"}]}]}
""");
    var response =
        rest.exchange(
            "/v1/messages", HttpMethod.POST, new HttpEntity<>(input, headers()), String.class);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    JsonNode body = JSON.readTree(response.getBody());
    assertThat(body.path("type").asText()).isEqualTo("message");
    assertThat(body.path("model").asText()).isEqualTo("native-model");
    assertThat(body.path("content").get(0).path("type").asText()).isEqualTo("thinking");
    assertThat(
            captured
                .get()
                .path("messages")
                .get(0)
                .path("content")
                .get(0)
                .path("image_url")
                .path("url")
                .asText())
        .isEqualTo("data:image/png;base64,aGk=");
    assertThat(captured.get().path("messages").get(1).path("tool_calls").get(0).path("id").asText())
        .isEqualTo("tool-1");
    assertThat(captured.get().path("messages").get(2).path("tool_call_id").asText())
        .isEqualTo("tool-1");
  }

  @Test
  void convertsOpenAiToolStreamToAnthropicEvents() {
    var input =
        Map.of(
            "model",
            "native-model",
            "max_tokens",
            16,
            "stream",
            true,
            "tools",
            List.of(Map.of("name", "echo", "input_schema", Map.of("type", "object"))),
            "messages",
            List.of(Map.of("role", "user", "content", "hi")));
    var response =
        rest.exchange(
            "/v1/messages", HttpMethod.POST, new HttpEntity<>(input, headers()), String.class);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody())
        .contains(
            "event:message_start",
            "thinking_delta",
            "tool_use",
            "input_json_delta",
            "event:message_stop")
        .doesNotContain("event:error");
  }

  @Test
  void rejectsSignedAnthropicReasoningAcrossProtocols() {
    var input =
        Map.of(
            "model",
            "native-model",
            "max_tokens",
            16,
            "messages",
            List.of(
                Map.of(
                    "role",
                    "assistant",
                    "content",
                    List.of(
                        Map.of(
                            "type",
                            "thinking",
                            "thinking",
                            "reason",
                            "signature",
                            "authentic-signature"))),
                Map.of("role", "user", "content", "continue")));
    var response =
        rest.exchange(
            "/v1/messages", HttpMethod.POST, new HttpEntity<>(input, headers()), String.class);
    assertThat(response.getStatusCode().value()).isEqualTo(400);
  }
}
