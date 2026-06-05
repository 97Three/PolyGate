package dev.polygate;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class ConfigurationService {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private volatile Limits cachedLimits = new Limits(64, 2097152, 60, 60);
  private volatile long limitsUntil;

  ConfigurationService(ObjectProvider<JdbcTemplate> db, ObjectMapper json) {
    this.db = db.getIfAvailable();
    this.json = json;
  }

  ObjectNode system() {
    ObjectNode base = json.createObjectNode();
    base.putObject("server")
        .put("bindAddress", "127.0.0.1:18080")
        .put("maxRequestBodyBytes", 2097152)
        .put("maxConcurrentRequests", 64);
    base.putObject("auth")
        .put("enabled", true)
        .put("tokenEnvVar", "POLYGATE_CLIENT_TOKEN")
        .put("allowNoAuth", false);
    ObjectNode gateway = base.putObject("gateway");
    gateway.put("defaultProvider", "deepseek");
    ArrayNode order = gateway.putArray("providerOrder");
    if (db != null)
      db.query(
          "SELECT id FROM gateway_provider ORDER BY id",
          (org.springframework.jdbc.core.RowCallbackHandler) rs -> order.add(rs.getString(1)));
    base.putObject("smartRouting")
        .put("mode", "off")
        .put("defaultProfile", "balanced")
        .put("policyVersion", "java-1")
        .put("activationPercent", 0)
        .put("groupCount", 0)
        .put("candidateCount", 0);
    base.putObject("rateLimits")
        .put("maxConcurrentRequests", 64)
        .put("maxRequestBodyBytes", 2097152)
        .put("requestTimeoutSecs", 60)
        .put("streamIdleTimeoutSecs", 60);
    base.putObject("runtime")
        .put("apiEndpoint", "http://127.0.0.1:18080")
        .put("anthropicEndpoint", "http://127.0.0.1:18080/v1/messages")
        .put("openaiEndpoint", "http://127.0.0.1:18080/v1/chat/completions")
        .put("modelsEndpoint", "http://127.0.0.1:18080/v1/models")
        .put("adminEndpoint", "http://127.0.0.1:33003");
    if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
        instanceof org.springframework.web.context.request.ServletRequestAttributes servlet) {
      var request = servlet.getRequest();
      String host = request.getHeader("Host");
      if (host != null && host.matches("[A-Za-z0-9.\\[\\]:-]{1,255}")) {
        String origin = request.getScheme() + "://" + host;
        ((ObjectNode) base.path("runtime"))
            .put("apiEndpoint", origin)
            .put("anthropicEndpoint", origin + "/v1/messages")
            .put("openaiEndpoint", origin + "/v1/chat/completions")
            .put("modelsEndpoint", origin + "/v1/models")
            .put("adminEndpoint", origin);
      }
    }
    merge(base, read("system"));
    JsonNode groups = read("router_config").path("groups");
    int candidateCount = 0;
    for (JsonNode group : groups) candidateCount += group.path("candidates").size();
    ((ObjectNode) base.path("smartRouting"))
        .put("groupCount", groups.size())
        .put("candidateCount", candidateCount);
    int active =
        db == null
            ? 0
            : db.queryForObject(
                "SELECT count(*) FROM gateway_provider WHERE enabled=true", Integer.class);
    base.putObject("setup")
        .put("ready", active > 0)
        .put("activeProviderCount", active)
        .put("defaultProviderReady", active > 0)
        .putArray("checks");
    ((ObjectNode) base.path("setup")).putArray("issues");
    return base;
  }

  ObjectNode updateSystem(JsonNode patch) {
    if (db == null) throw bad("Database is required for configuration changes");
    if (!patch.isObject()) throw bad("Settings must be an object");
    patch
        .fieldNames()
        .forEachRemaining(
            field -> {
              if (!Set.of("gateway", "rateLimits", "server", "smartRouting").contains(field))
                throw bad("Settings field cannot be changed: " + field);
            });
    JsonNode routing = patch.path("smartRouting");
    if (!routing.isMissingNode()) {
      if (!routing.isObject()) throw bad("smartRouting must be an object");
      if (routing.has("mode")
          && !Set.of("off", "shadow", "active").contains(routing.path("mode").asText()))
        throw bad("Invalid routing mode");
      if (routing.has("defaultProfile")
          && !SmartRoutingService.profiles().contains(routing.path("defaultProfile").asText()))
        throw bad("Invalid routing profile");
      if (routing.has("activationPercent")
          && (!routing.path("activationPercent").isIntegralNumber()
              || routing.path("activationPercent").asInt() < 0
              || routing.path("activationPercent").asInt() > 100))
        throw bad("Invalid activation percent");
      if (!"off".equals(routing.path("mode").asText("off"))
          && read("router_config").path("groups").isEmpty())
        throw bad("Configure a routing group before enabling smart routing");
    }
    if (patch.path("server").has("bindAddress")
        && !"127.0.0.1:18080".equals(patch.path("server").path("bindAddress").asText()))
      throw bad("The bind address is configured with environment variables and requires restart");
    for (String section : List.of("server", "rateLimits")) {
      JsonNode body = patch.path(section);
      if (!body.isMissingNode() && !body.isObject()) throw bad("Invalid " + section);
      for (String field :
          List.of(
              "maxConcurrentRequests",
              "maxRequestBodyBytes",
              "requestTimeoutSecs",
              "streamIdleTimeoutSecs"))
        if (body.has(field)) {
          long value = body.path(field).asLong(-1);
          long min = "maxRequestBodyBytes".equals(field) ? 1024 : 1,
              max =
                  "maxRequestBodyBytes".equals(field)
                      ? 33554432
                      : "maxConcurrentRequests".equals(field) ? 1024 : 3600;
          if (!body.path(field).isIntegralNumber() || value < min || value > max)
            throw bad("Invalid " + field);
        }
    }
    JsonNode gateway = patch.path("gateway");
    if (gateway.has("defaultProvider")
        && db.queryForObject(
                "SELECT count(*) FROM gateway_provider WHERE id=?",
                Integer.class,
                gateway.path("defaultProvider").asText())
            == 0) throw bad("Unknown default provider");
    if (gateway.has("providerOrder")) {
      if (!gateway.path("providerOrder").isArray()) throw bad("providerOrder must be an array");
      Set<String> seen = new HashSet<>();
      for (JsonNode id : gateway.path("providerOrder"))
        if (!id.isTextual()
            || !seen.add(id.asText())
            || db.queryForObject(
                    "SELECT count(*) FROM gateway_provider WHERE id=?", Integer.class, id.asText())
                == 0) throw bad("Invalid provider order");
    }
    new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()))
        .executeWithoutResult(
            tx -> {
              db.update(
                  "INSERT INTO gateway_setting(name,document) VALUES ('system','{}') ON CONFLICT DO"
                      + " NOTHING");
              ObjectNode stored =
                  db.queryForObject(
                      "SELECT document::text FROM gateway_setting WHERE name='system' FOR UPDATE",
                      (rs, i) -> parse(rs.getString(1)));
              merge(stored, patch);
              if (patch.path("server").has("maxConcurrentRequests"))
                stored
                    .withObject("/rateLimits")
                    .set(
                        "maxConcurrentRequests",
                        patch.path("server").path("maxConcurrentRequests"));
              if (patch.path("server").has("maxRequestBodyBytes"))
                stored
                    .withObject("/rateLimits")
                    .set("maxRequestBodyBytes", patch.path("server").path("maxRequestBodyBytes"));
              for (String field : List.of("maxConcurrentRequests", "maxRequestBodyBytes"))
                if (patch.path("rateLimits").has(field))
                  stored.withObject("/server").set(field, patch.path("rateLimits").path(field));
              db.update(
                  "UPDATE gateway_setting SET document=?::jsonb,revision=revision+1 WHERE"
                      + " name='system'",
                  stored.toString());
            });
    limitsUntil = 0;
    return system();
  }

  ObjectNode read(String name) {
    if (db == null) return json.createObjectNode();
    var rows =
        db.query(
            "SELECT document::text FROM gateway_setting WHERE name=?",
            (rs, i) -> parse(rs.getString(1)),
            name);
    return rows.isEmpty() ? json.createObjectNode() : rows.getFirst();
  }

  void write(String name, JsonNode value) {
    if (db == null) throw bad("Database is required");
    db.update(
        "INSERT INTO gateway_setting(name,document) VALUES (?,?::jsonb) ON CONFLICT(name) DO UPDATE"
            + " SET document=excluded.document,revision=gateway_setting.revision+1",
        name,
        value.toString());
  }

  ObjectNode parse(String value) {
    try {
      return (ObjectNode) json.readTree(value);
    } catch (Exception e) {
      throw new IllegalStateException("Invalid stored configuration", e);
    }
  }

  Limits limits() {
    if (System.nanoTime() > limitsUntil) {
      JsonNode saved = read("system"),
          rate = saved.path("rateLimits"),
          server = saved.path("server");
      cachedLimits =
          new Limits(
              rate.path("maxConcurrentRequests")
                  .asInt(server.path("maxConcurrentRequests").asInt(64)),
              rate.path("maxRequestBodyBytes")
                  .asInt(server.path("maxRequestBodyBytes").asInt(2097152)),
              rate.path("requestTimeoutSecs").asInt(60),
              rate.path("streamIdleTimeoutSecs").asInt(60));
      limitsUntil = System.nanoTime() + 1_000_000_000L;
    }
    return cachedLimits;
  }

  record Limits(int concurrent, int bodyBytes, int timeoutSeconds, int idleSeconds) {}

  static void merge(ObjectNode target, JsonNode patch) {
    if (!patch.isObject()) return;
    patch
        .fields()
        .forEachRemaining(
            entry -> {
              if (entry.getValue().isObject() && target.path(entry.getKey()).isObject())
                merge((ObjectNode) target.path(entry.getKey()), entry.getValue());
              else target.set(entry.getKey(), entry.getValue().deepCopy());
            });
  }

  private static GatewayException bad(String detail) {
    return new GatewayException(
        HttpStatus.BAD_REQUEST, "invalid_request_error", "invalid_request", detail);
  }
}
