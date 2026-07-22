package dev.polygate;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.net.URI;
import org.junit.jupiter.api.Test;

class GenerationPolicyTest {
  final ObjectMapper json = new ObjectMapper();

  RoutingService.Route route(String config) throws Exception {
    return new RoutingService.Route(
        "deep",
        "local",
        "qwen",
        URI.create("http://localhost:19090"),
        "",
        "openai_compat",
        json.readTree(config));
  }

  @Test
  void rendersVendorDialects() throws Exception {
    for (String dialect :
        new String[] {
          "openai", "deepseek", "openrouter", "qwen", "zai", "string_thinking", "llama_cpp"
        }) {
      var result =
          GenerationPolicy.apply(
              (ObjectNode) json.readTree("{\"reasoning_effort\":\"high\"}"),
              route("{\"reasoning\":{\"dialect\":\"" + dialect + "\"}}"));
      String field =
          switch (dialect) {
            case "openrouter" -> "reasoning";
            case "qwen" -> "enable_thinking";
            case "zai", "string_thinking" -> "thinking";
            case "llama_cpp" -> "chat_template_kwargs";
            default -> "reasoning_effort";
          };
      assertThat(result.has(field)).as(dialect).isTrue();
      if (dialect.equals("qwen")) assertThat(result.path(field).asBoolean()).isTrue();
    }
  }

  @Test
  void preservesClientSamplingAndAppliesAliasDefaults() throws Exception {
    var cfg =
        route(
            "{\"reasoning\":{\"mode\":\"llama_cpp\",\"model_enabled\":{\"deep\":true},\"model_budget_tokens\":{\"deep\":2048}},\"sampling\":{\"mode\":\"llama_cpp\",\"profiles\":{\"deep\":{\"temperature\":0.6,\"min_p\":0.05}}}}");
    var result = GenerationPolicy.apply((ObjectNode) json.readTree("{\"temperature\":0.2}"), cfg);
    assertThat(result.path("temperature").asDouble()).isEqualTo(.2);
    assertThat(result.path("min_p").asDouble()).isEqualTo(.05);
    assertThat(result.path("thinking_budget_tokens").asInt()).isEqualTo(2048);
    var off =
        GenerationPolicy.apply(
            (ObjectNode) json.readTree("{\"thinking\":{\"type\":\"disabled\"}}"), cfg);
    assertThat(off.path("chat_template_kwargs").path("enable_thinking").asBoolean()).isFalse();
    assertThat(off.has("thinking_budget_tokens")).isFalse();
  }

  @Test
  void rejectsUnrepresentableBudget() throws Exception {
    var cfg = route("{\"reasoning\":{\"dialect\":\"qwen\"}}");
    assertThatThrownBy(
            () ->
                GenerationPolicy.apply(
                    (ObjectNode)
                        json.readTree("{\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":10}}"),
                    cfg))
        .isInstanceOf(GatewayException.class);
  }

  @Test
  void explicitNativeDisableOverridesHighDefault() throws Exception {
    var cfg = route("{\"reasoning\":{\"dialect\":\"qwen\",\"defaultEffort\":\"high\"}}");
    var result =
        GenerationPolicy.apply((ObjectNode) json.readTree("{\"enable_thinking\":false}"), cfg);
    assertThat(result.path("enable_thinking").asBoolean()).isFalse();
  }
}
