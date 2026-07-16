package dev.polygate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
class ClientAuthService {
  private final GatewaySettings settings;
  private final JdbcTemplate database;
  private final ObjectMapper json;
  private final ObjectProvider<RoutingService> routes;

  ClientAuthService(
      GatewaySettings settings,
      ObjectProvider<JdbcTemplate> database,
      ObjectMapper json,
      ObjectProvider<RoutingService> routes) {
    this.settings = settings;
    this.database = database.getIfAvailable();
    this.json = json;
    this.routes = routes;
  }

  Principal authenticate(HttpHeaders headers, String requestedModel) {
    return authenticate(headers, requestedModel, false);
  }

  Principal authenticateAgent(HttpHeaders headers) {
    return authenticate(headers, null, true);
  }

  private Principal authenticate(HttpHeaders headers, String requestedModel, boolean agent) {
    String candidate = headers.getFirst("x-api-key");
    String bearer = headers.getFirst(HttpHeaders.AUTHORIZATION);
    if (candidate == null && bearer != null && bearer.regionMatches(true, 0, "Bearer ", 0, 7))
      candidate = bearer.substring(7);
    if (candidate == null || candidate.length() > 4096) throw unauthorized();
    if (!settings.clientToken.isBlank()
        && MessageDigest.isEqual(
            candidate.getBytes(StandardCharsets.UTF_8),
            settings.clientToken.getBytes(StandardCharsets.UTF_8))) {
      if (agent)
        throw new GatewayException(
            HttpStatus.FORBIDDEN,
            "forbidden_error",
            "agent_key_required",
            "A dedicated operations service account is required");
      return new Principal(null, null, List.of());
    }
    if (database != null) {
      byte[] digest = digest(candidate);
      var rows =
          database.query(
              "SELECT k.id,k.user_id,k.allowed_models::text allowed_models,k.policy::text"
                  + " policy,k.organization_id,k.project_id,k.environment_id,k.purpose,k.principal_type,t.allowed_models::text"
                  + " team_models,t.status team_status FROM gateway_api_key k LEFT JOIN"
                  + " gateway_team t ON t.id=k.team_id WHERE secret_hash=? AND enabled=true AND"
                  + " rotation_state='active' AND (expires_at IS NULL OR expires_at > now()) AND"
                  + " (user_id IS NULL OR EXISTS (SELECT 1 FROM gateway_user u WHERE u.id=k.user_id"
                  + " AND u.status='active'))",
              (rs, index) ->
                  new KeyRow(
                      (UUID) rs.getObject("id"),
                      (UUID) rs.getObject("user_id"),
                      rs.getString("allowed_models"),
                      rs.getString("policy"),
                      rs.getString("team_models"),
                      rs.getString("team_status"),
                      new TenantScope(
                          rs.getString("organization_id"),
                          rs.getString("project_id"),
                          rs.getString("environment_id")),
                      rs.getString("purpose"),
                      rs.getString("principal_type")),
              digest);
      if (!rows.isEmpty()) {
        KeyRow key = rows.getFirst();
        if (agent
            && (!"polygate_ops_agent".equals(key.purpose)
                || !"service_account".equals(key.principalType)))
          throw new GatewayException(
              HttpStatus.FORBIDDEN,
              "forbidden_error",
              "agent_key_required",
              "A dedicated operations service account is required");
        if (!agent && "polygate_ops_agent".equals(key.purpose))
          throw new GatewayException(
              HttpStatus.FORBIDDEN,
              "forbidden_error",
              "control_only_key",
              "Operations control keys cannot access model inference");
        List<String> allowedNames;
        try {
          var allowed = json.readTree(key.allowedModels);
          allowedNames = Arrays.asList(json.treeToValue(allowed, String[].class));
          if (key.teamStatus != null && !"active".equals(key.teamStatus))
            throw new GatewayException(
                HttpStatus.FORBIDDEN,
                "forbidden_error",
                "team_disabled",
                "The API key project is not active");
          if (requestedModel != null
              && !allowedNames.isEmpty()
              && !PolicyRules.model(allowedNames, requestedModel, resolvedModel(requestedModel))) {
            throw new GatewayException(
                HttpStatus.FORBIDDEN,
                "forbidden_error",
                "model_not_allowed",
                "Model is not allowed for this API key");
          }
          if (requestedModel != null
              && key.teamModels != null
              && !allowsModel(
                  json.readTree(key.teamModels), requestedModel, resolvedModel(requestedModel)))
            throw new GatewayException(
                HttpStatus.FORBIDDEN,
                "forbidden_error",
                "model_not_allowed",
                "Model is not allowed for this project");
          JsonNode policy = json.readTree(key.policy);
          if (policy.path("ipRestricted").asBoolean(false)) {
            var attributes =
                org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            String address =
                attributes
                        instanceof
                        org.springframework.web.context.request.ServletRequestAttributes servlet
                    ? servlet.getRequest().getRemoteAddr()
                    : "";
            boolean permitted = false;
            for (JsonNode ip : policy.path("allowedIps"))
              if (IpPolicy.matches(address, ip.asText())) permitted = true;
            if (!permitted)
              throw new GatewayException(
                  HttpStatus.FORBIDDEN,
                  "forbidden_error",
                  "ip_not_allowed",
                  "Client address is not allowed for this API key");
          }
        } catch (GatewayException e) {
          throw e;
        } catch (Exception e) {
          throw new IllegalStateException("Invalid stored API key model policy", e);
        }
        database.update("UPDATE gateway_api_key SET last_used_at=now() WHERE id=?", key.id);
        return new Principal(key.userId, key.id, allowedNames, key.scope);
      }
    }
    throw unauthorized();
  }

  void authorizeRoute(Principal principal, RoutingService.Route route) {
    if (!modelAllowed(principal, route.requestedModel(), route.providerId(), route.upstreamModel()))
      throw new GatewayException(
          HttpStatus.FORBIDDEN,
          "forbidden_error",
          "provider_not_allowed",
          "Model or provider is not allowed for this API key or project");
  }

  Principal catalogPrincipal(UUID id) {
    var rows =
        database.queryForList(
            "SELECT k.user_id,k.allowed_models::text"
                + " allowed_models,k.organization_id,k.project_id,k.environment_id FROM"
                + " gateway_api_key k JOIN gateway_user u ON u.id=k.user_id LEFT JOIN gateway_team"
                + " t ON t.id=k.team_id WHERE k.id=? AND k.enabled=true AND"
                + " k.rotation_state='active' AND COALESCE(k.purpose,'')<>'polygate_ops_agent' AND"
                + " (k.expires_at IS NULL OR k.expires_at>now()) AND u.status='active' AND (t.id IS"
                + " NULL OR t.status='active')",
            id);
    if (rows.isEmpty()) throw unauthorized();
    var row = rows.getFirst();
    try {
      return new Principal(
          (UUID) row.get("user_id"),
          id,
          Arrays.asList(json.readValue((String) row.get("allowed_models"), String[].class)),
          new TenantScope(
              (String) row.get("organization_id"),
              (String) row.get("project_id"),
              (String) row.get("environment_id")));
    } catch (Exception e) {
      throw new IllegalStateException("Invalid API key catalog policy", e);
    }
  }

  boolean modelAllowed(Principal principal, String model, String provider) {
    return modelAllowed(principal, model, provider, resolvedModel(model));
  }

  private boolean modelAllowed(
      Principal principal, String model, String provider, String resolved) {
    if (principal.apiKeyId() == null || database == null) return true;
    var rows =
        database.queryForList(
            "SELECT k.allowed_providers::text providers,t.allowed_models::text"
                + " team_models,t.allowed_providers::text team_providers FROM gateway_api_key k"
                + " LEFT JOIN gateway_team t ON t.id=k.team_id WHERE k.id=?",
            principal.apiKeyId());
    if (rows.isEmpty()) return false;
    var row = rows.getFirst();
    try {
      return PolicyRules.model(principal.allowedModels(), model, resolved)
          && allows(json.readTree((String) row.get("providers")), provider)
          && (row.get("team_models") == null
              || allowsModel(json.readTree((String) row.get("team_models")), model, resolved))
          && (row.get("team_providers") == null
              || allows(json.readTree((String) row.get("team_providers")), provider));
    } catch (Exception e) {
      throw new IllegalStateException("Invalid stored allowlist", e);
    }
  }

  private static boolean allows(JsonNode values, String candidate) {
    if (values == null || !values.isArray() || values.isEmpty()) return true;
    for (JsonNode value : values) if (PolicyRules.matches(value.asText(), candidate)) return true;
    return false;
  }

  private String resolvedModel(String requested) {
    try {
      return routes.getObject().resolve(requested).upstreamModel();
    } catch (GatewayException e) {
      return requested;
    }
  }

  private static boolean allowsModel(JsonNode rules, String requested, String resolved) {
    return allows(rules, requested) || allows(rules, resolved);
  }

  static byte[] digest(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static GatewayException unauthorized() {
    return new GatewayException(
        HttpStatus.UNAUTHORIZED,
        "authentication_error",
        "authentication_failed",
        "Invalid client credential");
  }

  record Principal(UUID userId, UUID apiKeyId, List<String> allowedModels, TenantScope scope) {
    Principal(UUID userId, UUID apiKeyId, List<String> allowedModels) {
      this(userId, apiKeyId, allowedModels, TenantScope.LOCAL);
    }
  }

  private record KeyRow(
      UUID id,
      UUID userId,
      String allowedModels,
      String policy,
      String teamModels,
      String teamStatus,
      TenantScope scope,
      String purpose,
      String principalType) {}
}
