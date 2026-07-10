package dev.polygate;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "polygate.database.enabled", havingValue = "true")
class AdminGovernanceController {
  private final AdminAuthService auth;
  private final GovernanceService governance;
  private final ConfigurationService config;
  private final GatewayTrafficFilter traffic;
  private final LocalScheduler localScheduler;

  AdminGovernanceController(
      AdminAuthService auth,
      GovernanceService governance,
      ConfigurationService config,
      GatewayTrafficFilter traffic,
      LocalScheduler localScheduler) {
    this.auth = auth;
    this.governance = governance;
    this.config = config;
    this.traffic = traffic;
    this.localScheduler = localScheduler;
  }

  @GetMapping("/admin/governance")
  Map<String, Object> overview(@RequestHeader HttpHeaders headers) {
    auth.requireAdmin(headers);
    return Map.of(
        "view",
        "administrator-control-plane",
        "ready",
        true,
        "dualApprovalRequired",
        governance.requiresDualApproval(),
        "projectPolicies",
        governance.policies(),
        "changeRequests",
        governance.changes(),
        "scheduler",
        scheduler(),
        "highRiskActions",
        GovernanceService.ACTIONS);
  }

  @GetMapping("/admin/governance/change-requests")
  Object changes(@RequestHeader HttpHeaders headers) {
    auth.requireAdmin(headers);
    return governance.changes();
  }

  @PostMapping("/admin/governance/change-requests")
  Object create(@RequestHeader HttpHeaders headers, @RequestBody JsonNode input) {
    auth.requireWrite(headers);
    auth.requireAdmin(headers);
    return governance.create(input, auth.requireSession(headers));
  }

  @PostMapping("/admin/governance/change-requests/{id}/approve")
  Object approve(@RequestHeader HttpHeaders headers, @PathVariable UUID id) {
    auth.requireWrite(headers);
    auth.requireAdmin(headers);
    return governance.approve(id, auth.requireSession(headers));
  }

  @PostMapping("/admin/governance/change-requests/{id}/apply")
  Object apply(@RequestHeader HttpHeaders headers, @PathVariable UUID id) {
    auth.requireWrite(headers);
    auth.requireAdmin(headers);
    return governance.apply(id, auth.requireSession(headers));
  }

  @GetMapping("/admin/self-service/governance")
  Object self(@RequestHeader HttpHeaders headers) {
    return Map.of(
        "view",
        "user-self-service",
        "user",
        auth.requireSession(headers),
        "effectivePolicy",
        governance.policy(TenantScope.LOCAL),
        "scheduler",
        scheduler(),
        "capabilities",
        Map.of(
            "mayRestrictRoutingMode",
            true,
            "mayExpandRoutingMode",
            false,
            "maySubmitHighRiskChange",
            "admin".equals(auth.requireSession(headers).get("role"))));
  }

  private Map<String, Object> scheduler() {
    return localScheduler.snapshot();
  }
}
