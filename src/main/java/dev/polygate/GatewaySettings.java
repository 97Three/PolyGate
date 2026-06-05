package dev.polygate;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GatewaySettings {
  final String clientToken;
  final String upstreamKey;
  final URI upstreamUrl;
  final boolean allowHttpMock;
  final String publicModel;
  final String upstreamModel;
  final String upstreamProtocol;

  GatewaySettings(
      @Value("${polygate.client-token}") String clientToken,
      @Value("${polygate.upstream-key}") String upstreamKey,
      @Value("${polygate.upstream-base-url}") String upstreamUrl,
      @Value("${polygate.upstream-allow-http-mock}") boolean allowHttpMock,
      @Value("${polygate.public-model}") String publicModel,
      @Value("${polygate.upstream-model}") String upstreamModel,
      @Value("${polygate.upstream-protocol:anthropic}") String upstreamProtocol) {
    this.clientToken = clientToken;
    this.upstreamKey = upstreamKey;
    this.upstreamUrl = URI.create(upstreamUrl);
    this.allowHttpMock = allowHttpMock;
    this.publicModel = publicModel;
    this.upstreamModel = upstreamModel;
    if (!java.util.Set.of("anthropic", "openai_compat").contains(upstreamProtocol))
      throw new IllegalArgumentException("Invalid upstream protocol");
    this.upstreamProtocol = upstreamProtocol;
    validateUpstream(this.upstreamUrl, allowHttpMock);
  }

  static void validateUpstream(URI url, boolean allowHttpMock) {
    if (!url.isAbsolute()
        || !"https".equals(url.getScheme())
            && !("http".equals(url.getScheme())
                && (allowHttpMock
                    || "127.0.0.1".equals(url.getHost())
                    || "localhost".equals(url.getHost())))) {
      throw new IllegalArgumentException(
          "Upstream URL must use HTTPS, except explicitly allowed mock URLs");
    }
    if (url.getUserInfo() != null || url.getQuery() != null || url.getFragment() != null) {
      throw new IllegalArgumentException(
          "Upstream URL must not contain credentials, query, or fragment");
    }
    String host = java.util.Objects.toString(url.getHost(), "").toLowerCase(java.util.Locale.ROOT);
    var approved =
        new java.util.HashSet<>(
            java.util.List.of(
                "127.0.0.1",
                "localhost",
                "::1",
                "api.deepseek.com",
                "api.anthropic.com",
                "api.openai.com",
                "openrouter.ai",
                "api.xiaomimimo.com",
                "api.moonshot.cn",
                "api.moonshot.ai",
                "api.siliconflow.cn",
                "api.siliconflow.com",
                "api.z.ai",
                "open.bigmodel.cn",
                "dashscope.aliyuncs.com",
                "dashscope-intl.aliyuncs.com",
                "generativelanguage.googleapis.com",
                "api.groq.com",
                "api.x.ai",
                "api.together.xyz",
                "api.together.ai",
                "api.fireworks.ai",
                "inference.cerebras.ai"));
    String extra = System.getenv("POLYGATE_APPROVED_UPSTREAM_HOSTS");
    if (extra != null)
      for (String value : extra.split(","))
        approved.add(value.trim().toLowerCase(java.util.Locale.ROOT));
    if (allowHttpMock && host.equals("polygate-mock")) approved.add(host);
    if (!approved.contains(host) && !approved.contains("*"))
      throw new GatewayException(
          org.springframework.http.HttpStatus.FORBIDDEN,
          "forbidden_error",
          "forbidden",
          "Provider endpoint requires organization approval; add its exact host to"
              + " POLYGATE_APPROVED_UPSTREAM_HOSTS before enabling it");
  }
}
