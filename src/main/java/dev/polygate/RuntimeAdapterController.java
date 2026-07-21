package dev.polygate;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.networknt.schema.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;

@RestController
@ConditionalOnProperty(name = "polygate.database.enabled", havingValue = "true")
class RuntimeAdapterController {
  private final ConfigurationService config;
  private final AdminAuthService auth;
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final WebClient http;

  RuntimeAdapterController(
      ConfigurationService config,
      AdminAuthService auth,
      JdbcTemplate db,
      ObjectMapper json,
      WebClient.Builder builder) {
    this.config = config;
    this.auth = auth;
    this.db = db;
    this.json = json;
    this.http = builder.build();
  }

  static GatewayException bad(String message) {
    return new GatewayException(
        HttpStatus.BAD_REQUEST, "invalid_request_error", "runtime_adapter_invalid", message);
  }

  static JsonNode validate(JsonNode doc) {
    String name =
        switch (doc.path("kind").asText()) {
          case "RuntimeAdapterCapabilities" -> "runtime-adapter-capabilities-v1alpha1.schema.json";
          case "RuntimeAdapterComputeInventory" ->
              "runtime-adapter-compute-inventory-v1alpha1.schema.json";
          default -> throw bad("Unsupported Runtime Adapter kind");
        };
    try (var stream = RuntimeAdapterController.class.getResourceAsStream("/schemas/" + name)) {
      JsonNode schema = new ObjectMapper().readTree(stream);
      var errors =
          JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
              .getSchema(
                  schema, SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build())
              .validate(doc);
      if (!errors.isEmpty()) throw bad("Runtime Adapter document does not match v1alpha1 schema");
    } catch (GatewayException e) {
      throw e;
    } catch (Exception e) {
      throw bad("Cannot validate Runtime Adapter schema");
    }
    if ("RuntimeAdapterCapabilities".equals(doc.path("kind").asText())) {
      Set<String> ids = new HashSet<>(), paths = new HashSet<>();
      Map<String, String> expected =
          Map.of(
              "capabilities.get",
              "/capabilities",
              "health.get",
              "/health",
              "inventory.models.list",
              "/inventory/models",
              "inventory.compute.list",
              "/inventory/compute",
              "inventory.deployments.list",
              "/inventory/deployments");
      for (JsonNode op : doc.path("spec").path("operations")) {
        String id = op.path("operationId").asText(), path = op.path("path").asText();
        if (!ids.add(id)
            || !paths.add(path)
            || !path.equals("/runtime-adapter/v1alpha1" + expected.get(id)))
          throw bad("Duplicate or mismatched Runtime Adapter operation");
      }
      if (!ids.contains("capabilities.get")) throw bad("capabilities.get is required");
    } else {
      Set<String> nodes = new HashSet<>(), gpus = new HashSet<>();
      try {
        Instant.parse(doc.path("metadata").path("observedAt").asText());
      } catch (Exception e) {
        throw bad("Invalid inventory observation time");
      }
      for (JsonNode node : doc.path("nodes")) {
        if (!nodes.add(node.path("nodeId").asText())) throw bad("Duplicate compute node");
        Map<String, String> devices = new HashMap<>();
        for (JsonNode gpu : node.path("gpus"))
          devices.put(gpu.path("gpuId").asText(), gpu.path("deviceClass").asText());
        for (JsonNode gpu : node.path("gpus")) {
          if (!gpus.add(gpu.path("gpuId").asText())) throw bad("Duplicate GPU identifier");
          if (gpu.path("memory")
                  .path("availableBytes")
                  .bigIntegerValue()
                  .compareTo(gpu.path("memory").path("totalBytes").bigIntegerValue())
              > 0) throw bad("GPU available memory exceeds total");
          String kind = gpu.path("deviceClass").asText();
          JsonNode parent = gpu.path("parentGpuId");
          if ("physical".equals(kind) && !parent.isMissingNode() && !parent.isNull()
              || "partition".equals(kind) && !"physical".equals(devices.get(parent.asText())))
            throw bad("Invalid GPU partition parent");
        }
      }
    }
    return doc;
  }

  @GetMapping("/admin/runtime-adapters")
  Object list(@RequestHeader HttpHeaders h) {
    auth.requireAdmin(h);
    ObjectNode c = config.read("runtime_adapters");
    if (!c.has("adapters")) c.putArray("adapters");
    return c;
  }

  @PutMapping("/admin/runtime-adapters")
  Object update(@RequestHeader HttpHeaders h, @RequestBody JsonNode body) {
    auth.requireWrite(h);
    auth.requireAdmin(h);
    if (!body.path("adapters").isArray() || body.path("adapters").size() > 32)
      throw bad("At most 32 adapters are allowed");
    Set<String> ids = new HashSet<>();
    for (JsonNode a : body.path("adapters")) {
      for (String field : List.of("id", "baseUrl", "bearerTokenEnv"))
        if (!a.path(field).isTextual()) throw bad("Missing Runtime Adapter configuration field");
      if (!a.path("id").asText().matches("[a-z0-9][a-z0-9._-]{0,62}")
          || !ids.add(a.path("id").asText())) throw bad("Invalid/duplicate adapter id");
      URI uri = URI.create(a.path("baseUrl").asText());
      GatewaySettings.validateUpstream(uri, false);
      if (!a.path("bearerTokenEnv").asText().matches("[A-Z_][A-Z0-9_]{0,127}")
          || a.path("staleAfterSeconds").asInt(180) < 10
          || a.path("staleAfterSeconds").asInt(180) > 86400)
        throw bad("Invalid credential reference or freshness interval");
      var fields = a.fieldNames();
      while (fields.hasNext())
        if (!Set.of("id", "baseUrl", "bearerTokenEnv", "staleAfterSeconds", "enabled")
            .contains(fields.next()))
          throw bad("Unknown adapter field; store environment references only");
    }
    config.write("runtime_adapters", body);
    return list(h);
  }

  JsonNode adapter(String id) {
    for (JsonNode a : config.read("runtime_adapters").path("adapters"))
      if (a.path("id").asText().equals(id)) return a;
    throw new GatewayException(
        HttpStatus.NOT_FOUND, "not_found_error", "adapter_not_found", "Runtime Adapter not found");
  }

  @GetMapping("/admin/runtime-adapters/{id}")
  Object snapshot(@RequestHeader HttpHeaders h, @PathVariable String id) {
    auth.requireAdmin(h);
    JsonNode a = adapter(id);
    ObjectNode out = json.createObjectNode().put("id", id).put("status", "unavailable");
    db.query(
        "SELECT * FROM gateway_runtime_observation WHERE adapter_id=?",
        (org.springframework.jdbc.core.RowCallbackHandler)
            rs -> {
              try {
                if (rs.getString("inventory") != null)
                  out.set("inventory", json.readTree(rs.getString("inventory")));
                if (rs.getString("capabilities") != null)
                  out.set("capabilities", json.readTree(rs.getString("capabilities")));
                if (rs.getTimestamp("observed_at") != null) {
                  Instant t = rs.getTimestamp("observed_at").toInstant();
                  out.put("observedAt", t.toString());
                  out.put(
                      "status",
                      t.plusSeconds(a.path("staleAfterSeconds").asLong(180)).isBefore(Instant.now())
                          ? "stale"
                          : "fresh");
                }
                if (rs.getString("safe_error") != null)
                  out.put("status", "unavailable").put("lastError", rs.getString("safe_error"));
              } catch (Exception e) {
                throw bad("Stored inventory invalid");
              }
            },
        id);
    return out;
  }

  @PostMapping("/admin/runtime-adapters/{id}/refresh")
  Object refresh(@RequestHeader HttpHeaders h, @PathVariable String id) {
    auth.requireWrite(h);
    auth.requireAdmin(h);
    refresh(id);
    return snapshot(h, id);
  }

  void refresh(String id) {
    JsonNode a = adapter(id);
    if (!a.path("enabled").asBoolean(true)) throw bad("Runtime Adapter is disabled");
    try {
      URI base = URI.create(a.path("baseUrl").asText());
      GatewaySettings.validateUpstream(base, false);
      String key = System.getenv(a.path("bearerTokenEnv").asText());
      if (key == null || key.isBlank()) throw bad("Runtime Adapter credential is unavailable");
      JsonNode caps = fetch(base, "/runtime-adapter/v1alpha1/capabilities", key);
      validate(caps);
      if (!id.equals(caps.path("metadata").path("adapterId").asText()))
        throw bad("Runtime Adapter identity mismatch");
      boolean bearer = false, compute = false;
      for (JsonNode s : caps.path("spec").path("authentication").path("schemes"))
        if ("bearer".equals(s.asText())) bearer = true;
      for (JsonNode op : caps.path("spec").path("operations"))
        if ("inventory.compute.list".equals(op.path("operationId").asText())) compute = true;
      if (!bearer
          || !compute
          || "http".equals(base.getScheme())
              && !"tls_or_loopback"
                  .equals(caps.path("spec").path("authentication").path("transport").asText()))
        throw bad("Runtime Adapter capabilities do not permit authenticated inventory");
      JsonNode inventory = fetch(base, "/runtime-adapter/v1alpha1/inventory/compute", key);
      validate(inventory);
      if (!id.equals(inventory.path("metadata").path("adapterId").asText()))
        throw bad("Inventory identity mismatch");
      Instant observed = Instant.parse(inventory.path("metadata").path("observedAt").asText());
      if (observed.isAfter(Instant.now().plusSeconds(30)))
        throw bad("Inventory timestamp is in the future");
      db.update(
          "INSERT INTO gateway_runtime_observation(adapter_id,capabilities,inventory,observed_at)"
              + " VALUES (?,?::jsonb,?::jsonb,?) ON CONFLICT(adapter_id) DO UPDATE SET"
              + " capabilities=excluded.capabilities,inventory=excluded.inventory,observed_at=excluded.observed_at,checked_at=now(),safe_error=NULL"
              + " WHERE gateway_runtime_observation.observed_at IS NULL OR"
              + " gateway_runtime_observation.observed_at<=excluded.observed_at",
          id,
          caps.toString(),
          inventory.toString(),
          java.sql.Timestamp.from(observed));
    } catch (Exception e) {
      db.update(
          "INSERT INTO gateway_runtime_observation(adapter_id,safe_error) VALUES"
              + " (?,'adapter_query_failed') ON CONFLICT(adapter_id) DO UPDATE SET"
              + " checked_at=now(),safe_error='adapter_query_failed'",
          id);
      throw new GatewayException(
          HttpStatus.BAD_GATEWAY,
          "upstream_error",
          "adapter_query_failed",
          "Runtime Adapter query or validation failed");
    }
  }

  private JsonNode fetch(URI base, String path, String key) {
    return http.get()
        .uri(base.resolve(path))
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
        .retrieve()
        .bodyToMono(JsonNode.class)
        .timeout(Duration.ofSeconds(10))
        .block();
  }
}
