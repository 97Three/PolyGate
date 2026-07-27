package dev.polygate;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MessagesIntegrationTest {
  private static HttpServer upstream;
  private static final AtomicReference<String> captured = new AtomicReference<>();
  private static final ObjectMapper JSON = new ObjectMapper();
  @LocalServerPort int port;
  @Autowired TestRestTemplate rest;

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry registry) throws Exception {
    upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    upstream.createContext(
        "/v1/messages",
        exchange -> {
          captured.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          String response;
          if (captured.get().contains("\"stream\":true")) {
            response =
                "event: message_start\n"
                    + "data:"
                    + " {\"type\":\"message_start\",\"message\":{\"type\":\"message\",\"model\":\"deepseek-flash\"}}\n\n"
                    + (captured.get().contains("call_tool")
                        ? "event: content_block_start\n"
                              + "data:"
                              + " {\"type\":\"content_block_start\",\"content_block\":{\"type\":\"tool_use\",\"id\":\"tool_1\",\"name\":\"echo\"}}\n\n"
                              + "event: content_block_delta\n"
                              + "data:"
                              + " {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{}\"}}\n\n"
                        : "event: content_block_delta\n"
                              + "data:"
                              + " {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"hello\"}}\n\n")
                    + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
            if (captured.get().contains("truncate")) {
              response =
                  response.replace(
                      "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n", "");
            }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          } else {
            response =
                captured.get().contains("call_tool")
                    ? "{\"id\":\"msg_mock\",\"type\":\"message\",\"model\":\"deepseek-flash\",\"stop_reason\":\"tool_use\",\"content\":[{\"type\":\"tool_use\",\"id\":\"tool_1\",\"name\":\"echo\",\"input\":{\"value\":\"hi\"}}]}"
                    : "{\"id\":\"msg_mock\",\"type\":\"message\",\"model\":\"deepseek-flash\",\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
          }
          byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    upstream.start();
    registry.add("polygate.client-token", () -> "local-test-client-token");
    registry.add("polygate.upstream-key", () -> "local-test-upstream-key");
    registry.add(
        "polygate.upstream-base-url", () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
    registry.add("polygate.public-model", () -> "demo-model");
    registry.add("polygate.upstream-model", () -> "deepseek-flash");
  }

  @AfterAll
  static void stop() {
    if (upstream != null) upstream.stop(0);
  }

  private HttpHeaders headers() {
    HttpHeaders headers = new HttpHeaders();
    headers.set("x-api-key", "local-test-client-token");
    headers.set("anthropic-version", "2023-06-01");
    return headers;
  }

  @Test
  void proxiesTextAndMapsModel() throws Exception {
    ResponseEntity<String> result =
        rest.exchange(
            "/v1/messages",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "model",
                    "demo-model",
                    "max_tokens",
                    32,
                    "messages",
                    new Object[] {Map.of("role", "user", "content", "hi")}),
                headers()),
            String.class);
    assertThat(result.getStatusCode().value()).isEqualTo(200);
    JsonNode body = JSON.readTree(result.getBody());
    assertThat(body.path("model").asText()).isEqualTo("demo-model");
    assertThat(JSON.readTree(captured.get()).path("model").asText()).isEqualTo("deepseek-flash");
  }

  @Test
  void preservesAnthropicImagesAndSignedThinkingReplay() throws Exception {
    JsonNode input =
        JSON.readTree(
            """
{"model":"demo-model","max_tokens":32,"messages":[
{"role":"user","content":[{"type":"image","source":{"type":"base64","media_type":"image/png","data":"aGk="}}]},
{"role":"assistant","content":[{"type":"thinking","thinking":"reason","signature":"signed-value"},{"type":"text","text":"answer"}]},
{"role":"user","content":"continue"}]}
""");
    var response =
        rest.exchange(
            "/v1/messages", HttpMethod.POST, new HttpEntity<>(input, headers()), String.class);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(JSON.readTree(captured.get()).path("messages")).isEqualTo(input.path("messages"));
  }

  @Test
  void rejectsMissingCredentialAndMalformedTools() {
    ResponseEntity<String> unauth =
        rest.postForEntity("/v1/messages", Map.of("model", "demo-model"), String.class);
    assertThat(unauth.getStatusCode().value()).isEqualTo(401);
    ResponseEntity<String> tools =
        rest.exchange(
            "/v1/messages",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "model",
                    "demo-model",
                    "max_tokens",
                    32,
                    "messages",
                    new Object[] {Map.of("role", "user", "content", "hi")},
                    "tools",
                    new Object[] {Map.of("name", "echo")}),
                headers()),
            String.class);
    assertThat(tools.getStatusCode().value()).isEqualTo(400);
    assertThat(tools.getHeaders().getFirst("x-polygate-error-contract")).isEqualTo("v1");
  }

  @Test
  void preservesSameProtocolToolUseAndResult() throws Exception {
    Object[] tools = {Map.of("name", "echo", "input_schema", Map.of("type", "object"))};
    ResponseEntity<String> first =
        rest.exchange(
            "/v1/messages",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "model",
                    "demo-model",
                    "max_tokens",
                    32,
                    "tools",
                    tools,
                    "messages",
                    new Object[] {Map.of("role", "user", "content", "call_tool")}),
                headers()),
            String.class);
    assertThat(first.getStatusCode().value()).isEqualTo(200);
    assertThat(JSON.readTree(first.getBody()).path("content").get(0).path("type").asText())
        .isEqualTo("tool_use");
    Object[] messages = {
      Map.of("role", "user", "content", "call_tool"),
      Map.of(
          "role",
          "assistant",
          "content",
          new Object[] {
            Map.of(
                "type", "tool_use", "id", "tool_1", "name", "echo", "input", Map.of("value", "hi"))
          }),
      Map.of(
          "role",
          "user",
          "content",
          new Object[] {Map.of("type", "tool_result", "tool_use_id", "tool_1", "content", "hi")})
    };
    ResponseEntity<String> second =
        rest.exchange(
            "/v1/messages",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "model", "demo-model", "max_tokens", 32, "tools", tools, "messages", messages),
                headers()),
            String.class);
    assertThat(second.getStatusCode().value()).isEqualTo(200);
    assertThat(
            JSON.readTree(captured.get())
                .path("messages")
                .get(2)
                .path("content")
                .get(0)
                .path("tool_use_id")
                .asText())
        .isEqualTo("tool_1");
  }

  @Test
  void healthShowsReadyWhenConfigured() {
    ResponseEntity<String> result = rest.getForEntity("/actuator/health", String.class);
    assertThat(result.getStatusCode().value()).isEqualTo(200);
    assertThat(result.getBody()).contains("UP");
  }

  @Test
  void modelListRequiresClientCredential() throws Exception {
    assertThat(rest.getForEntity("/v1/models", String.class).getStatusCode().value())
        .isEqualTo(401);
    ResponseEntity<String> result =
        rest.exchange("/v1/models", HttpMethod.GET, new HttpEntity<>(headers()), String.class);
    assertThat(result.getStatusCode().value()).isEqualTo(200);
    assertThat(JSON.readTree(result.getBody()).path("data").get(0).path("id").asText())
        .isEqualTo("demo-model");
  }

  @Test
  void streamsAnthropicEvents() throws Exception {
    String body =
        "{\"model\":\"demo-model\",\"max_tokens\":32,\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/messages"))
            .header("Content-Type", "application/json")
            .header("x-api-key", "local-test-client-token")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    HttpResponse<String> result =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(result.statusCode()).isEqualTo(200);
    assertThat(result.body())
        .contains(
            "event:message_start", "demo-model", "event:content_block_delta", "event:message_stop");
  }

  @Test
  void marksTruncatedStreamAsErrorEvent() throws Exception {
    String body =
        "{\"model\":\"demo-model\",\"max_tokens\":32,\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"truncate\"}]}";
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/messages"))
            .header("Content-Type", "application/json")
            .header("x-api-key", "local-test-client-token")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    HttpResponse<String> result =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(result.statusCode()).isEqualTo(200);
    assertThat(result.body())
        .contains("event:error", "Anthropic stream ended without message_stop");
  }

  @Test
  void streamsToolUseEventsWithoutChangingProtocol() throws Exception {
    String body =
        "{\"model\":\"demo-model\",\"max_tokens\":32,\"stream\":true,\"tools\":[{\"name\":\"echo\",\"input_schema\":{\"type\":\"object\"}}],\"messages\":[{\"role\":\"user\",\"content\":\"call_tool\"}]}";
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/messages"))
            .header("Content-Type", "application/json")
            .header("x-api-key", "local-test-client-token")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    HttpResponse<String> result =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(result.statusCode()).isEqualTo(200);
    assertThat(result.body())
        .contains(
            "event:content_block_start",
            "tool_use",
            "event:content_block_delta",
            "input_json_delta",
            "event:message_stop");
  }

  @Test
  void convertsOpenAiTextAndToolCallsToAnthropic() throws Exception {
    Object[] tools = {
      Map.of(
          "type",
          "function",
          "function",
          Map.of("name", "echo", "parameters", Map.of("type", "object")))
    };
    ResponseEntity<String> first =
        rest.exchange(
            "/v1/chat/completions",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "model",
                    "demo-model",
                    "max_tokens",
                    32,
                    "tools",
                    tools,
                    "messages",
                    new Object[] {Map.of("role", "user", "content", "call_tool")}),
                headers()),
            String.class);
    assertThat(first.getStatusCode().value()).isEqualTo(200);
    JsonNode firstBody = JSON.readTree(first.getBody());
    assertThat(
            firstBody
                .path("choices")
                .get(0)
                .path("message")
                .path("tool_calls")
                .get(0)
                .path("function")
                .path("name")
                .asText())
        .isEqualTo("echo");
    assertThat(
            JSON.readTree(captured.get())
                .path("tools")
                .get(0)
                .path("input_schema")
                .path("type")
                .asText())
        .isEqualTo("object");
    Object[] messages = {
      Map.of("role", "user", "content", "hi"),
      Map.of(
          "role",
          "assistant",
          "tool_calls",
          new Object[] {
            Map.of(
                "id",
                "tool_1",
                "type",
                "function",
                "function",
                Map.of("name", "echo", "arguments", "{\"value\":\"hi\"}"))
          }),
      Map.of("role", "tool", "tool_call_id", "tool_1", "content", "hi")
    };
    ResponseEntity<String> second =
        rest.exchange(
            "/v1/chat/completions",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of("model", "demo-model", "max_tokens", 32, "messages", messages), headers()),
            String.class);
    assertThat(second.getStatusCode().value()).isEqualTo(200);
    JsonNode forwarded = JSON.readTree(captured.get()).path("messages");
    assertThat(forwarded.get(1).path("content").get(0).path("type").asText()).isEqualTo("tool_use");
    assertThat(forwarded.get(2).path("content").get(0).path("tool_use_id").asText())
        .isEqualTo("tool_1");
  }

  @Test
  void rejectsUnpreservableOpenAiReasoning() {
    ResponseEntity<String> result =
        rest.exchange(
            "/v1/chat/completions",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "model",
                    "demo-model",
                    "messages",
                    new Object[] {
                      Map.of(
                          "role", "assistant", "content", "answer", "reasoning_content", "private")
                    }),
                headers()),
            String.class);
    assertThat(result.getStatusCode().value()).isEqualTo(400);
  }

  @Test
  void convertsOpenAiStreamToChatChunks() throws Exception {
    String body =
        "{\"model\":\"demo-model\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer local-test-client-token")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    HttpResponse<String> result =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(result.statusCode()).isEqualTo(200);
    assertThat(result.body()).contains("chat.completion.chunk", "\"content\":\"hello\"", "[DONE]");
  }
}
