package dev.polygate;

import static org.assertj.core.api.Assertions.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class TrustedProxyFilterTest {
  @Test
  void ignoresForgedHeadersFromUntrustedPeer() {
    assertThat(TrustedProxyFilter.clientIp("203.0.113.4", "127.0.0.1", List.of("10.0.0.0/8")))
        .isEqualTo("203.0.113.4");
  }

  @Test
  void walksFromClosestTrustedProxy() {
    assertThat(
            TrustedProxyFilter.clientIp(
                "10.0.0.2", "127.0.0.1, 198.51.100.2, 10.1.1.2", List.of("10.0.0.0/8")))
        .isEqualTo("198.51.100.2");
  }

  @Test
  void malformedChainDoesNotOverrideIdentity() {
    assertThat(
            TrustedProxyFilter.clientIp(
                "10.0.0.2", "spoof.invalid, 198.51.100.2", List.of("10.0.0.0/8")))
        .isEqualTo("10.0.0.2");
  }

  @Test
  void trustedProxyCarriesExternalHostAndScheme() throws Exception {
    var request = new MockHttpServletRequest();
    request.setRemoteAddr("10.0.0.2");
    request.addHeader("Host", "internal:18080");
    request.addHeader("X-Forwarded-Host", "gateway.example.test");
    request.addHeader("X-Forwarded-Proto", "https");
    request.addHeader("X-Forwarded-For", "198.51.100.2");
    new TrustedProxyFilter("10.0.0.0/8")
        .doFilter(
            request,
            new MockHttpServletResponse(),
            (req, res) -> {
              var r = (HttpServletRequest) req;
              assertThat(r.getRemoteAddr()).isEqualTo("198.51.100.2");
              assertThat(r.isSecure()).isTrue();
              assertThat(r.getHeader("Host")).isEqualTo("gateway.example.test");
            });
  }
}
