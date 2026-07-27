package dev.polygate;

import static org.assertj.core.api.Assertions.*;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.web.context.request.*;

class AuthCookieTest {
  @Test
  void secureCookieFollowsTrustedHttpsProxyOnly() throws Exception {
    var auth = new AdminAuthService(null, "");
    for (String peer : new String[] {"10.0.0.2", "203.0.113.2"}) {
      var req = new MockHttpServletRequest();
      req.setRemoteAddr(peer);
      req.addHeader("X-Forwarded-Proto", "https");
      new TrustedProxyFilter("10.0.0.0/8")
          .doFilter(
              req,
              new MockHttpServletResponse(),
              (request, response) -> {
                RequestContextHolder.setRequestAttributes(
                    new ServletRequestAttributes((HttpServletRequest) request));
                try {
                  assertThat(auth.sessionCookie("fixture", 43200).contains("; Secure"))
                      .isEqualTo(peer.startsWith("10."));
                } finally {
                  RequestContextHolder.resetRequestAttributes();
                }
              });
    }
  }

  @Test
  void configuredSecureCookieAlsoClearsSecureSession() {
    var auth = new AdminAuthService(null, "");
    org.springframework.test.util.ReflectionTestUtils.setField(auth, "secureCookie", true);
    assertThat(auth.sessionCookie("", 0))
        .contains("Max-Age=0", "; Secure", "HttpOnly", "SameSite=Lax");
  }
}
