package dev.polygate;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;

record TenantScope(String organizationId, String projectId, String environmentId) {
  static final TenantScope LOCAL = new TenantScope("org_local", "prj_default", "env_default");

  TenantScope {
    for (String value : new String[] {organizationId, projectId, environmentId})
      if (value == null || !value.matches("[A-Za-z0-9._-]{1,128}"))
        throw new GatewayException(
            HttpStatus.BAD_REQUEST,
            "invalid_request_error",
            "invalid_scope",
            "Invalid tenant scope");
  }

  static TenantScope from(JsonNode input) {
    return new TenantScope(
        input.path("organizationId").asText("org_local"),
        input.path("projectId").asText("prj_default"),
        input.path("environmentId").asText("env_default"));
  }

  String key() {
    return organizationId + "/" + projectId + "/" + environmentId;
  }
}
