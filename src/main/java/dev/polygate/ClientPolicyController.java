package dev.polygate;

import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

@RestController
class ClientPolicyController {
  private final ClientAuthService auth;
  private final GovernanceService governance;

  ClientPolicyController(ClientAuthService auth, GovernanceService governance) {
    this.auth = auth;
    this.governance = governance;
  }

  @GetMapping("/v1/effective-policy")
  Object effective(@RequestHeader HttpHeaders headers) {
    var principal = auth.authenticate(headers, null);
    var policy = governance.policy(principal.scope());
    return Map.of(
        "project",
        principal.scope(),
        "effectivePolicy",
        policy,
        "defaultClassification",
        policy.path("defaultClassification"),
        "effectiveMode",
        policy.path("maximumMode"));
  }
}
