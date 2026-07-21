package dev.polygate;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import jakarta.annotation.PreDestroy;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Built-in read-only detector. Optional model analysis travels through normal gateway admission.
 */
@Service
@ConditionalOnProperty(name = "polygate.database.enabled", havingValue = "true")
class JavaOperationsAgent {
  static final UUID INSTANCE = UUID.fromString("00000000-0000-0000-0000-000000010001"),
      USER = UUID.fromString("00000000-0000-0000-0000-000000010002"),
      SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000010003");
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final OperationsController ops;
  private final RoutingService routes;
  private final WebClient http;
  private final GatewayHealth readiness;
  private final ProviderStatus providerHealth;
  private volatile int port;
  private UUID keyId;
  private String token, model;
  private Long lastSuccess;
  private String modelStatus = "disabled";

  JavaOperationsAgent(
      JdbcTemplate db,
      ObjectMapper json,
      OperationsController ops,
      RoutingService routes,
      WebClient.Builder builder,
      GatewayHealth readiness,
      ProviderStatus providerHealth) {
    this.readiness = readiness;
    this.providerHealth = providerHealth;
    this.db = db;
    this.json = json;
    this.ops = ops;
    this.routes = routes;
    http = builder.build();
  }

  @EventListener
  void port(WebServerInitializedEvent event) {
    port = event.getWebServer().getPort();
  }

  @Scheduled(initialDelay = 30000, fixedDelay = 30000)
  void scheduled() {
    try {
      check();
    } catch (RuntimeException ignored) {
      modelStatus = "error";
    }
  }

  synchronized Map<String, Object> check() {
    ObjectNode configuration = ops.configuration();
    boolean enabled = configuration.path("enabled").asBoolean();
    boolean analysis = enabled && configuration.path("analysisEnabled").asBoolean();
    if (!analysis) {
      revoke();
      modelStatus = "disabled";
    }
    try (Connection connection = db.getDataSource().getConnection();
        Statement lock = connection.createStatement()) {
      try (ResultSet result = lock.executeQuery("SELECT pg_try_advisory_lock(861271006)")) {
        if (!result.next() || !result.getBoolean(1))
          return Map.of("checked", false, "reason", "another_instance_is_checking");
      }
      try {
        int activeConditions = 0;
        if (enabled) {
          boolean ready = "UP".equals(readiness.health().getStatus().getCode());
          if (observe(
              "readiness.gateway",
              "readiness_storage",
              "SEV-2",
              "网关依赖未就绪",
              "认证、存储或上游配置不满足接单条件",
              json.createObjectNode().put("gatewayReady", ready),
              !ready,
              configuration,
              analysis)) activeConditions++;
          var recent =
              db.queryForMap(
                  "SELECT count(*) total,count(*) FILTER(WHERE state='failed' AND http_status>=500)"
                      + " failures FROM gateway_request WHERE started_at>now()-interval '5 minutes'"
                      + " AND traffic_class='business' AND organization_id='org_local' AND"
                      + " project_id='prj_default' AND environment_id='env_default'");
          long volume = ((Number) recent.get("total")).longValue(),
              failures = ((Number) recent.get("failures")).longValue();
          if (observe(
              "requests.failure-ratio",
              "request_anomaly",
              "SEV-3",
              "请求失败率超出运行基线",
              "近五分钟受控失败率超过百分之五",
              json.createObjectNode().put("requests", volume).put("failures", failures),
              volume >= 20 && failures >= volume * .05,
              configuration,
              analysis)) activeConditions++;
          long changes =
              db.queryForObject(
                  "SELECT count(*) FROM gateway_audit WHERE occurred_at>now()-interval '15 minutes'"
                      + " AND action NOT LIKE '%login%'",
                  Long.class);
          if (observe(
              "change.verification",
              "post_change_verification",
              "SEV-2",
              "变更后就绪性回退",
              "最近变更后网关尚未就绪",
              json.createObjectNode().put("recentChanges", changes).put("gatewayReady", ready),
              changes > 0 && !ready,
              configuration,
              analysis)) activeConditions++;
          if (db.queryForObject(
                  "SELECT count(*) FROM gateway_budget_account WHERE organization_id='org_local'"
                      + " AND project_id='prj_default' AND environment_id='env_default' AND"
                      + " limit_microunits IS NOT NULL",
                  Long.class)
              == 0)
            observe(
                "budget.default",
                "budget_utilization",
                "SEV-3",
                "项目预算使用率较高",
                "当前未设置硬预算",
                json.createObjectNode(),
                false,
                configuration,
                false);

          for (var provider :
              db.queryForList(
                  "SELECT p.id,count(r.id) total,count(r.id) FILTER(WHERE r.state='failed' AND"
                      + " r.http_status>=500) failures FROM gateway_provider p LEFT JOIN"
                      + " gateway_request r ON r.provider_id=p.id AND r.started_at>now()-interval"
                      + " '5 minutes' AND r.traffic_class='business' AND"
                      + " r.organization_id='org_local' AND r.project_id='prj_default' AND"
                      + " r.environment_id='env_default' WHERE p.enabled GROUP BY p.id")) {
            long total = ((Number) provider.get("total")).longValue(),
                failed = ((Number) provider.get("failures")).longValue();
            boolean active =
                total >= 3 && failed >= 3 && failed * 1.0 / total >= .3
                    || providerHealth.cooling(provider.get("id").toString(), null);
            ObjectNode evidence =
                json.createObjectNode()
                    .put("providerId", provider.get("id").toString())
                    .put("requests", total)
                    .put("serverErrors", failed)
                    .put("windowSeconds", 300);
            if (observe(
                "provider." + provider.get("id"),
                "provider_error_ratio",
                "SEV-2",
                "供应商错误率升高",
                "近 5 分钟出现持续供应商错误",
                evidence,
                active,
                configuration,
                analysis)) activeConditions++;
          }
          long stale =
              db.queryForObject(
                  "SELECT count(*) FROM gateway_request WHERE state='started' AND"
                      + " started_at<now()-interval '2 hours' AND organization_id='org_local' AND"
                      + " project_id='prj_default' AND environment_id='env_default'",
                  Long.class);
          if (observe(
              "ledger.stale",
              "stale_requests",
              "SEV-2",
              "请求账本存在长期未终结记录",
              "账本记录超过自动核对期限",
              json.createObjectNode().put("unreconciledRequests", stale),
              stale > 0,
              configuration,
              analysis)) activeConditions++;
          for (var account :
              db.queryForList(
                  "SELECT"
                      + " organization_id,project_id,environment_id,limit_microunits,settled_microunits,reserved_microunits"
                      + " FROM gateway_budget_account WHERE organization_id='org_local' AND"
                      + " project_id='prj_default' AND environment_id='env_default' AND"
                      + " limit_microunits IS NOT NULL")) {
            long limit = ((Number) account.get("limit_microunits")).longValue(),
                used =
                    Math.addExact(
                        ((Number) account.get("settled_microunits")).longValue(),
                        ((Number) account.get("reserved_microunits")).longValue());
            ObjectNode evidence =
                json.createObjectNode()
                    .put("limitMicrounits", limit)
                    .put("usedAndReservedMicrounits", used);
            if (observe(
                "budget.default",
                "budget_utilization",
                used >= limit ? "SEV-2" : "SEV-3",
                "项目预算使用率较高",
                "项目已使用与已预留预算接近硬上限",
                evidence,
                limit == 0 || used >= limit * .8,
                configuration,
                analysis)) activeConditions++;
          }
        }
        ObjectNode heartbeat =
            json.createObjectNode()
                .put("agentVersion", "java-1.0")
                .put("mode", enabled ? "read_only" : "disabled")
                .put("ruleSetVersion", "java-aggregate-1")
                .put("queueDepth", 0)
                .put("intervalSeconds", 30)
                .put("analysisEnabled", analysis)
                .put("modelStatus", modelStatus);
        heartbeat.set("selectedModel", configuration.path("selectedModel"));
        if (lastSuccess == null) heartbeat.putNull("modelLastSuccessAtMs");
        else heartbeat.put("modelLastSuccessAtMs", lastSuccess);
        ops.recordHeartbeat(INSTANCE, heartbeat);
        return Map.of(
            "checked",
            true,
            "enabled",
            enabled,
            "activeConditions",
            activeConditions,
            "analysisEnabled",
            analysis,
            "modelStatus",
            modelStatus);
      } finally {
        lock.execute("SELECT pg_advisory_unlock(861271006)");
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Operations storage is unavailable");
    }
  }

  private boolean observe(
      String key,
      String detector,
      String severity,
      String title,
      String summary,
      ObjectNode evidence,
      boolean active,
      JsonNode configuration,
      boolean analysis) {
    ObjectNode observation =
        json.createObjectNode()
            .put("eventKey", "java." + key)
            .put("detectorType", detector)
            .put("severity", severity)
            .put("title", title)
            .put("summary", summary)
            .put("observedAtMs", System.currentTimeMillis())
            .put("active", active)
            .put("recoveryCriteria", "下一次聚合检测确认该条件恢复正常");
    observation
        .putObject("affectedScope")
        .put("organizationId", "org_local")
        .put("projectId", "prj_default")
        .put("environmentId", "env_default");
    observation.set("evidence", evidence);
    var principal = new ClientAuthService.Principal(INSTANCE, null, List.of(), TenantScope.LOCAL);
    Map<String, Object> result = ops.observe(principal, observation);
    if (active
        && analysis
        && result.get("incident") instanceof JsonNode incident
        && !"suppressed".equals(incident.path("status").asText())) {
      UUID id = UUID.fromString(incident.path("id").asText());
      if (db.queryForObject(
                  "SELECT count(*) FROM gateway_ops_evidence WHERE incident_id=? AND"
                      + " document->'evidence'->>'kind'='model_analysis_attempt' AND"
                      + " (document->>'observedAtMs')::bigint>?",
                  Integer.class,
                  id,
                  System.currentTimeMillis() - 900000)
              == 0
          && db.queryForObject(
                  "SELECT count(*) FROM gateway_ops_evidence WHERE"
                      + " document->'evidence'->>'kind'='model_analysis_attempt' AND"
                      + " (document->>'observedAtMs')::bigint>?",
                  Integer.class,
                  System.currentTimeMillis() - 3600000)
              < 4) analyze(id, evidence, configuration);
    }
    return active;
  }

  private void analyze(UUID incident, ObjectNode evidence, JsonNode configuration) {
    String selected = configuration.path("selectedModel").asText("");
    if (!configuration.path("modelReady").asBoolean() || port <= 0) {
      modelStatus = "missing_credential";
      return;
    }
    ObjectNode attempt =
        json.createObjectNode()
            .put("id", UUID.randomUUID().toString())
            .put("incidentId", incident.toString())
            .put("observedAtMs", System.currentTimeMillis());
    attempt.putObject("evidence").put("kind", "model_analysis_attempt").put("model", selected);
    db.update(
        "INSERT INTO gateway_ops_evidence(id,incident_id,document) VALUES (?,?,?::jsonb)",
        UUID.fromString(attempt.path("id").asText()),
        incident,
        attempt.toString());
    try {
      var route = routes.resolve(selected);
      ensureKey(selected, route.providerId());
      ObjectNode request = json.createObjectNode().put("model", selected).put("max_tokens", 512);
      request
          .putArray("messages")
          .addObject()
          .put("role", "user")
          .put(
              "content",
              "Analyze these aggregate gateway diagnostics. Give short root-cause hypotheses and"
                  + " read-only recommendations. Do not ask for secrets, user prompts, tools or"
                  + " changes. Evidence: "
                  + evidence);
      var response =
          http.post()
              .uri("http://127.0.0.1:" + port + "/v1/messages")
              .header("x-api-key", token)
              .header("x-polygate-traffic-class", "diagnostic")
              .contentType(MediaType.APPLICATION_JSON)
              .bodyValue(request)
              .retrieve()
              .toEntity(JsonNode.class)
              .timeout(Duration.ofMillis(Math.min(route.requestTimeoutMs() + 5000, 665000)))
              .block();
      StringBuilder answer = new StringBuilder();
      if (response != null && response.getBody() != null)
        for (JsonNode block : response.getBody().path("content"))
          if ("text".equals(block.path("type").asText()))
            answer.append(block.path("text").asText());
      if (answer.isEmpty()) throw new IllegalStateException("No analysis text");
      if (answer.length() > 8000) answer.setLength(8000);
      lastSuccess = System.currentTimeMillis();
      modelStatus = "configured";
      ObjectNode record =
          json.createObjectNode()
              .put("id", UUID.randomUUID().toString())
              .put("incidentId", incident.toString())
              .put("observedAtMs", lastSuccess);
      record
          .putObject("evidence")
          .put("kind", "model_analysis")
          .put("model", selected)
          .put("analysis", answer.toString())
          .put("requestId", response.getHeaders().getFirst("x-polygate-request-id"));
      db.update(
          "INSERT INTO gateway_ops_evidence(id,incident_id,document) VALUES (?,?,?::jsonb)",
          UUID.fromString(record.path("id").asText()),
          incident,
          record.toString());
    } catch (RuntimeException e) {
      modelStatus = "error";
    }
  }

  private void ensureKey(String selected, String provider) {
    if (keyId != null && selected.equals(model)) {
      var rows =
          db.queryForList("SELECT enabled,expires_at FROM gateway_api_key WHERE id=?", keyId);
      if (rows.isEmpty() || !Boolean.TRUE.equals(rows.getFirst().get("enabled")))
        throw new IllegalStateException("Managed analysis credential is disabled");
      if (((Timestamp) rows.getFirst().get("expires_at")).getTime()
          > System.currentTimeMillis() + 60000) return;
    }
    revoke();
    String random = HexFormat.of().formatHex(SecureRandomHolder.RANDOM.generateSeed(32));
    db.update(
        "INSERT INTO gateway_user(id,username,password_hash,role,email,status) VALUES"
            + " (?,'polygate_ops_system',?,'user','','active') ON CONFLICT(id) DO NOTHING",
        USER,
        new BCryptPasswordEncoder(12).encode(random));
    if (db.queryForObject(
            "SELECT count(*) FROM gateway_user WHERE id=? AND status='active'", Integer.class, USER)
        == 0) throw new IllegalStateException("Managed operations identity is disabled");
    keyId = UUID.randomUUID();
    model = selected;
    token = "mp_java_ops_" + HexFormat.of().formatHex(SecureRandomHolder.RANDOM.generateSeed(32));
    ObjectNode policy =
        json.createObjectNode()
            .put("rateLimited", true)
            .put("dailyLimitUsd", 1)
            .put("ipRestricted", true);
    policy.putArray("allowedIps").add("127.0.0.1");
    db.update(
        "INSERT INTO"
            + " gateway_api_key(id,name,secret_hash,user_id,allowed_models,allowed_providers,key_prefix,principal_type,purpose,policy,quota_subject,expires_at)"
            + " VALUES (?,'PolyGate Java internal"
            + " diagnostics',?,?,?::jsonb,?::jsonb,?,'service_account','polygate_ops_analysis',?::jsonb,?,now()+interval"
            + " '24 hours')",
        keyId,
        ClientAuthService.digest(token),
        USER,
        json.createArrayNode().add(selected).toString(),
        json.createArrayNode().add(provider).toString(),
        token.substring(0, 12),
        policy.toString(),
        SUBJECT);
    db.update(
        "INSERT INTO gateway_audit(id,actor_id,action,resource_type,resource_id,safe_details)"
            + " VALUES"
            + " (?,?,'ops_service_account_provisioned','api_key',?,'{\"managed\":true,\"purpose\":\"polygate_ops_analysis\",\"dailyLimitUsd\":1,\"expiryHours\":24}')",
        UUID.randomUUID(),
        INSTANCE,
        keyId.toString());
  }

  @PreDestroy
  synchronized void revoke() {
    if (keyId != null) {
      try {
        db.update("UPDATE gateway_api_key SET enabled=false WHERE id=?", keyId);
      } catch (RuntimeException ignored) {
      }
      keyId = null;
      token = null;
      model = null;
    }
  }

  private static final class SecureRandomHolder {
    static final SecureRandom RANDOM = new SecureRandom();
  }
}
