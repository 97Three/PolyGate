package dev.polygate;

import org.springframework.http.HttpStatus;

final class UpstreamFailures {
  private UpstreamFailures() {}

  static GatewayException safe(RuntimeException error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof GatewayException gateway) return gateway;
      if (cause instanceof java.util.concurrent.TimeoutException)
        return new GatewayException(
            HttpStatus.GATEWAY_TIMEOUT,
            "upstream_error",
            "upstream_timeout",
            "Upstream request timed out");
    }
    return new GatewayException(
        HttpStatus.BAD_GATEWAY,
        "upstream_error",
        "upstream_transport",
        "Upstream connection or response failed");
  }
}
