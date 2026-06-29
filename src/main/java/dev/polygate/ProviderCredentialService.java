package dev.polygate;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "polygate.database.enabled", havingValue = "true")
class ProviderCredentialService {
  private final JdbcTemplate db;
  private final GatewaySettings settings;
  private final ObjectMapper json;
  private final ProviderStatus health;

  ProviderCredentialService(
      JdbcTemplate db, GatewaySettings settings, ObjectMapper json, ProviderStatus health) {
    this.db = db;
    this.settings = settings;
    this.json = json;
    this.health = health;
  }

  @Transactional
  RoutingService.Route decorate(RoutingService.Route route, boolean request) {
    var all =
        db.queryForList(
            "SELECT * FROM gateway_provider_credential WHERE provider_id=? ORDER BY created_at,id",
            route.providerId());
    if (all.isEmpty() && !route.config().path("credentialPoolConfigured").asBoolean()) return route;
    String mode = route.config().path("credentialPoolMode").asText("failover");
    List<Map<String, Object>> usable =
        all.stream()
            .filter(
                row ->
                    Boolean.TRUE.equals(row.get("enabled"))
                        && !secret(row.get("credential_env").toString()).isBlank())
            .toList();
    if (usable.isEmpty()) throw unavailable();
    if (!"manual".equals(mode)) {
      var healthy = usable.stream().filter(row -> !cooling(row.get("id").toString())).toList();
      if (!healthy.isEmpty()) usable = healthy;
    }
    Map<String, Object> selected;
    if ("manual".equals(mode)) {
      selected =
          usable.stream()
              .filter(row -> Boolean.TRUE.equals(row.get("active")))
              .findFirst()
              .orElseThrow(ProviderCredentialService::unavailable);
    } else if ("round_robin".equals(mode) && request) {
      db.update(
          "INSERT INTO gateway_provider_credential_cursor(provider_id) VALUES (?) ON CONFLICT DO"
              + " NOTHING",
          route.providerId());
      long sequence =
          db.queryForObject(
              "UPDATE gateway_provider_credential_cursor SET sequence=CASE WHEN"
                  + " sequence=9223372036854775807 THEN 0 ELSE sequence+1 END WHERE provider_id=?"
                  + " RETURNING sequence",
              Long.class,
              route.providerId());
      selected = usable.get((int) Math.floorMod(sequence - 1, usable.size()));
    } else
      selected =
          usable.stream()
              .filter(row -> Boolean.TRUE.equals(row.get("active")))
              .findFirst()
              .orElse(usable.getFirst());
    URI url =
        selected.get("base_url") == null || selected.get("base_url").toString().isBlank()
            ? URI.create(
                route.config().path("_providerBaseUrl").asText(route.upstreamUrl().toString()))
            : URI.create(selected.get("base_url").toString());
    GatewaySettings.validateUpstream(url, settings.allowHttpMock);
    return new RoutingService.Route(
        route.requestedModel(),
        route.providerId(),
        route.upstreamModel(),
        url,
        secret(selected.get("credential_env").toString()),
        route.protocol(),
        route.config(),
        selected.get("id").toString());
  }

  List<Map<String, Object>> list(String provider) {
    return db.query(
        "SELECT * FROM gateway_provider_credential WHERE provider_id=? ORDER BY created_at,id",
        (rs, i) -> {
          Map<String, Object> row = new HashMap<>();
          row.put("id", rs.getString("id"));
          row.put("providerId", provider);
          row.put("name", rs.getString("name"));
          row.put("apiKeyEnv", rs.getString("credential_env"));
          row.put("baseUrl", rs.getString("base_url"));
          row.put("status", rs.getBoolean("enabled") ? "active" : "disabled");
          row.put("active", rs.getBoolean("active"));
          row.put("hasApiKey", !secret(rs.getString("credential_env")).isBlank());
          row.put("health", health.health(provider, rs.getString("id")));
          row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
          row.put("updatedAt", rs.getTimestamp("updated_at").toInstant().toString());
          return row;
        },
        provider);
  }

  @Transactional
  void create(String provider, JsonNode input) {
    lock(provider);
    String id = input.path("id").asText("cred_" + UUID.randomUUID().toString().replace("-", ""));
    if (!id.matches("[A-Za-z0-9_-]{1,128}")) throw bad("Invalid credential id");
    write(provider, id, input, false);
    db.update(
        "UPDATE gateway_provider SET"
            + " config=jsonb_set(config,'{credentialPoolConfigured}','true'),updated_at=now() WHERE"
            + " id=?",
        provider);
    if (db.queryForObject(
            "SELECT count(*) FROM gateway_provider_credential WHERE provider_id=? AND active=true",
            Integer.class,
            provider)
        == 0)
      db.update(
          "UPDATE gateway_provider_credential SET active=true WHERE id=? AND enabled=true", id);
  }

  @Transactional
  void update(String provider, String id, JsonNode input) {
    lock(provider);
    var rows = list(provider).stream().filter(row -> id.equals(row.get("id"))).toList();
    if (rows.isEmpty()) throw missing();
    var merged = json.valueToTree(rows.getFirst());
    input
        .fields()
        .forEachRemaining(
            entry ->
                ((com.fasterxml.jackson.databind.node.ObjectNode) merged)
                    .set(entry.getKey(), entry.getValue()));
    write(provider, id, merged, true);
  }

  @Transactional
  void select(String provider, String id) {
    lock(provider);
    var rows =
        db.queryForList(
            "SELECT enabled,credential_env FROM gateway_provider_credential WHERE provider_id=? AND"
                + " id=?",
            provider,
            id);
    if (rows.isEmpty()) throw missing();
    if (!Boolean.TRUE.equals(rows.getFirst().get("enabled"))
        || secret(rows.getFirst().get("credential_env").toString()).isBlank())
      throw bad("Credential is disabled or its environment variable is missing");
    db.update("UPDATE gateway_provider_credential SET active=false WHERE provider_id=?", provider);
    db.update("UPDATE gateway_provider_credential SET active=true,updated_at=now() WHERE id=?", id);
  }

  @Transactional
  void delete(String provider, String id) {
    lock(provider);
    if (db.update(
            "DELETE FROM gateway_provider_credential WHERE provider_id=? AND id=?", provider, id)
        == 0) throw missing();
  }

  @Transactional
  void mode(String provider, String mode) {
    if (!Set.of("manual", "failover", "round_robin").contains(mode))
      throw bad("Invalid credential pool mode");
    lock(provider);
    db.update(
        "UPDATE gateway_provider SET"
            + " config=jsonb_set(config,'{credentialPoolMode}',to_jsonb(?::text)),updated_at=now()"
            + " WHERE id=?",
        mode,
        provider);
  }

  private void write(String provider, String id, JsonNode input, boolean update) {
    String name = input.path("name").asText("").trim(),
        env = input.path("apiKeyEnv").asText("").trim(),
        status = input.path("status").asText("active"),
        url = input.path("baseUrl").asText("").trim();
    if (name.isBlank()
        || name.length() > 120
        || !env.matches("[A-Z][A-Z0-9_]{0,127}")
        || !Set.of("active", "disabled").contains(status)) throw bad("Invalid provider credential");
    if (!url.isBlank())
      try {
        GatewaySettings.validateUpstream(URI.create(url), settings.allowHttpMock);
      } catch (Exception e) {
        throw bad("Invalid credential baseUrl");
      }
    try {
      if (update)
        db.update(
            "UPDATE gateway_provider_credential SET"
                + " name=?,credential_env=?,base_url=?,enabled=?,active=CASE WHEN ? THEN active"
                + " ELSE false END,updated_at=now() WHERE provider_id=? AND id=?",
            name,
            env,
            url.isBlank() ? null : url,
            "active".equals(status),
            "active".equals(status),
            provider,
            id);
      else
        db.update(
            "INSERT INTO"
                + " gateway_provider_credential(id,provider_id,name,credential_env,base_url,enabled)"
                + " VALUES (?,?,?,?,?,?)",
            id,
            provider,
            name,
            env,
            url.isBlank() ? null : url,
            "active".equals(status));
    } catch (org.springframework.dao.DuplicateKeyException e) {
      throw new dev.polygate.GatewayException(
          HttpStatus.CONFLICT,
          "invalid_request_error",
          "credential_exists",
          "Credential id already exists");
    }
  }

  private void lock(String provider) {
    if (db.query(
            "SELECT id FROM gateway_provider WHERE id=? FOR UPDATE",
            (rs, i) -> rs.getString(1),
            provider)
        .isEmpty())
      throw new GatewayException(
          HttpStatus.NOT_FOUND, "not_found_error", "provider_not_found", "Provider not found");
  }

  private String secret(String env) {
    String value =
        "POLYGATE_UPSTREAM_KEY".equals(env) ? settings.upstreamKey : System.getenv(env);
    return value == null ? "" : value;
  }

  private boolean cooling(String credential) {
    var rows =
        db.query(
            "SELECT provider_id FROM gateway_provider_credential WHERE id=?",
            (rs, i) -> rs.getString(1),
            credential);
    return !rows.isEmpty() && health.cooling(rows.getFirst(), credential);
  }

  @Transactional
  RoutingService.Route alternative(RoutingService.Route route, Set<String> rejected) {
    if ("manual".equals(route.config().path("credentialPoolMode").asText())
        || route.credentialId() == null) return null;
    var rows =
        db.queryForList(
            "SELECT * FROM gateway_provider_credential WHERE provider_id=? AND enabled ORDER BY"
                + " created_at,id",
            route.providerId());
    for (var row : rows) {
      String id = row.get("id").toString(), key = secret(row.get("credential_env").toString());
      if (rejected.contains(id) || key.isBlank() || health.cooling(route.providerId(), id))
        continue;
      URI url =
          row.get("base_url") == null
              ? URI.create(
                  route.config().path("_providerBaseUrl").asText(route.upstreamUrl().toString()))
              : URI.create(row.get("base_url").toString());
      GatewaySettings.validateUpstream(url, settings.allowHttpMock);
      return new RoutingService.Route(
          route.requestedModel(),
          route.providerId(),
          route.upstreamModel(),
          url,
          key,
          route.protocol(),
          route.config(),
          id);
    }
    return null;
  }

  private static GatewayException unavailable() {
    return new GatewayException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "server_error",
        "credential_pool_not_ready",
        "Provider credential pool has no usable selected credential");
  }

  private static GatewayException missing() {
    return new GatewayException(
        HttpStatus.NOT_FOUND,
        "not_found_error",
        "credential_not_found",
        "Provider credential not found");
  }

  private static GatewayException bad(String message) {
    return new GatewayException(
        HttpStatus.BAD_REQUEST, "invalid_request_error", "invalid_request", message);
  }
}
