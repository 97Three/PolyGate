package dev.polygate;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "polygate.database.enabled", havingValue = "true")
class AdminAuthController {
  private final AdminAuthService auth;
  private final OidcController oidc;

  AdminAuthController(AdminAuthService auth, OidcController oidc) {
    this.auth = auth;
    this.oidc = oidc;
  }

  @GetMapping("/admin/auth/methods")
  ResponseEntity<?> methods() {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(
            Map.of(
                "passwordEnabled",
                true,
                "oidc",
                Map.of(
                    "enabled",
                    oidc.enabled(),
                    "label",
                    "企业单点登录",
                    "startUrl",
                    "/admin/auth/oidc/start")));
  }

  @PostMapping("/admin/auth/login")
  ResponseEntity<?> login(@RequestBody Map<String, String> input) {
    AdminAuthService.Login result = auth.login(input.get("username"), input.get("password"));
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .header(HttpHeaders.SET_COOKIE, auth.sessionCookie(result.token(), 43200))
        .body(
            Map.of(
                "user",
                result.user(),
                "expiresAt",
                String.valueOf(result.expiry().toEpochMilli())));
  }

  @GetMapping("/admin/auth/me")
  Map<String, Object> me(@RequestHeader HttpHeaders headers) {
    return auth.requireSession(headers);
  }

  @PostMapping("/admin/auth/logout")
  ResponseEntity<?> logout(@RequestHeader HttpHeaders headers) {
    auth.requireWrite(headers);
    auth.logout(headers);
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, auth.sessionCookie("", 0))
        .body(Map.of("ok", true));
  }
}
