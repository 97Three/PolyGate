package dev.polygate;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.*;

@Service
@ConditionalOnProperty(name = "polygate.database.enabled", havingValue = "true")
class SmartRoutingService {
  private final ConfigurationService config;
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final PricingService pricing;
  private final ProviderStatus health;

  SmartRoutingService(
      ConfigurationService config,
      JdbcTemplate db,
      ObjectMapper json,
      PricingService pricing,
      ProviderStatus health) {
    this.pricing = pricing;
    this.health = health;
    this.config = config;
    this.db = db;
    this.json = json;
  }

  ObjectNode configuration() {
    ObjectNode node = config.read("router_config");
    if (!node.has("groups")) node.putArray("groups");
    return node;
  }

  ObjectNode update(JsonNode input) {
    if (!input.isObject() || !input.path("groups").isArray() || input.path("groups").size() > 100)
      throw bad("groups must be an array of at most 100 groups");
    Set<String> groupIds = new HashSet<>(), aliases = new HashSet<>();
    for (JsonNode group : input.path("groups")) {
      String id = group.path("id").asText();
      if (!id.matches("[A-Za-z0-9_.-]{1,128}") || !groupIds.add(id))
        throw bad("Invalid or duplicate group id");
      if (!group.path("aliases").isArray() || group.path("aliases").isEmpty())
        throw bad("A group requires aliases");
      for (JsonNode alias : group.path("aliases"))
        if (!alias.isTextual()
            || !alias.asText().matches("[A-Za-z0-9._:/-]{1,240}")
            || !aliases.add(alias.asText())) throw bad("Invalid or duplicate routing alias");
      if (!profiles().contains(group.path("defaultProfile").asText("balanced")))
        throw bad("Unknown routing profile");
      if (!group.path("candidates").isArray()
          || group.path("candidates").isEmpty()
          || group.path("candidates").size() > 256) throw bad("A group requires 1..256 candidates");
      Set<String> models = new HashSet<>();
      for (JsonNode candidate : group.path("candidates")) {
        String model = candidate.path("model").asText();
        if (!models.add(model)
            || db.queryForObject(
                    "SELECT count(*) FROM gateway_model WHERE public_id=?", Integer.class, model)
                == 0) throw bad("Candidate must identify a configured public model");
        for (String field : List.of("quality", "cost", "latencyMs"))
          if (candidate.has(field)
              && (!candidate.path(field).isNumber()
                  || !Double.isFinite(candidate.path(field).asDouble())
                  || candidate.path(field).asDouble() < 0)) throw bad("Invalid candidate " + field);
        if (candidate.path("quality").asDouble(.5) > 1)
          throw bad("quality must be between 0 and 1");
      }
    }
    // Routing aliases may shadow a static alias for shadow/off mode, but never a concrete model.
    for (String alias : aliases)
      if (db.queryForObject(
              "SELECT count(*) FROM gateway_model WHERE public_id=?", Integer.class, alias)
          > 0) throw bad("Routing alias conflicts with a concrete model");
    config.write("router_config", input);
    return configuration();
  }

  static void requestInput(JsonNode input) {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes s)
      s.getRequest().setAttribute("polygate.routingInput", input);
  }

  static String digest(String value) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static double affinity(String session, String model) {
    byte[] hash =
        java.util.HexFormat.of()
            .parseHex(digest(session + "\u0000" + model.replaceFirst(":", "\u0000")));
    return new java.math.BigInteger(1, Arrays.copyOf(hash, 8)).doubleValue()
        / new java.math.BigInteger("18446744073709551615").doubleValue();
  }

  static double inverse(double value, double min, double max) {
    return Math.abs(max - min) < 1e-12 ? 1 : 1 - Math.clamp((value - min) / (max - min), 0, 1);
  }

  Selection select(String requested) {
    JsonNode system = config.system().path("smartRouting");
    for (JsonNode group : configuration().path("groups")) {
      boolean matches = false;
      for (JsonNode a : group.path("aliases")) if (requested.equals(a.asText())) matches = true;
      if (!matches) continue;
      String profile =
          group.path("defaultProfile").asText(system.path("defaultProfile").asText("balanced"));
      String affinity = "";
      if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servlet) {
        String selectedProfile = servlet.getRequest().getHeader("x-polygate-routing-profile");
        if (selectedProfile != null) {
          if (!profiles().contains(selectedProfile)) throw bad("Unknown routing profile");
          profile = selectedProfile;
        }
        affinity =
            Objects.toString(
                servlet.getRequest().getHeader("x-polygate-session-id"),
                Objects.toString(servlet.getRequest().getHeader("Idempotency-Key"), ""));
        if (affinity.length() > 512) throw bad("Session affinity identifier is too long");
      }
      String sessionKey = affinity;
      long inputTokens = 1024, outputTokens = 1024;
      if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes s) {
        Object body = s.getRequest().getAttribute("polygate.routingInput");
        if (body instanceof JsonNode n) {
          inputTokens = n.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
          outputTokens = n.path("max_tokens").asLong(n.path("max_completion_tokens").asLong(1024));
        }
        String principal =
            Objects.toString(
                s.getRequest().getHeader("x-api-key"),
                Objects.toString(s.getRequest().getHeader("Authorization"), "anonymous"));
        sessionKey = digest(principal + "\u0000" + affinity);
      }
      final String hashedSession = affinity.isBlank() ? "" : sessionKey;
      List<Candidate> candidates = new ArrayList<>();
      for (JsonNode c : group.path("candidates")) {
        String model = c.path("model").asText();
        if (db.queryForObject(
                "SELECT count(*) FROM gateway_model m JOIN gateway_provider p ON p.id=m.provider_id"
                    + " WHERE m.public_id=? AND m.enabled AND p.enabled",
                Integer.class,
                model)
            == 0) continue;
        Map<String, Object> stats =
            db.queryForMap(
                "SELECT count(*) attempts,COALESCE(sum(reliability*weight)/NULLIF(sum(weight),0),1)"
                    + " reliability,COALESCE(sum(latency_ms*weight)/NULLIF(sum(weight) FILTER(WHERE"
                    + " latency_ms IS NOT NULL),0),?) latency FROM (SELECT CASE WHEN"
                    + " r.state='completed' THEN 1.0 ELSE 0 END"
                    + " reliability,r.latency_ms,power(.9,row_number() OVER(ORDER BY r.started_at"
                    + " DESC)-1) weight FROM gateway_request r JOIN gateway_model m ON"
                    + " m.provider_id=r.provider_id AND m.upstream_id=r.upstream_model WHERE"
                    + " m.public_id=? AND r.started_at>now()-interval '1 hour' AND"
                    + " r.state<>'started' ORDER BY r.started_at DESC LIMIT 200) recent",
                c.path("latencyMs").asDouble(1000),
                model);
        String provider = model.substring(0, model.indexOf(':'));
        var runtimeHealth = health.health(provider, null);
        if ("cooldown".equals(runtimeHealth.get("status"))) continue;
        JsonNode card = pricing.card(provider, model.substring(model.indexOf(':') + 1));
        double estimate =
            card == null
                ? c.path("cost").asDouble(1)
                : pricing.cost(card, inputTokens, outputTokens, 0, 0).doubleValue();
        double samples = ((Number) stats.get("attempts")).doubleValue(),
            confidence = samples / (samples + 10);
        candidates.add(
            new Candidate(
                model,
                c.path("quality").asDouble(.5),
                estimate,
                c.path("latencyMs").asDouble(1000) * (1 - confidence)
                    + ((Number) stats.get("latency")).doubleValue() * confidence,
                (((Number) runtimeHealth.get("successesTotal")).doubleValue() + 2)
                    / (((Number) runtimeHealth.get("requestsTotal")).doubleValue() + 4)));
      }
      if (candidates.isEmpty())
        throw new GatewayException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "server_error",
            "no_routing_candidate",
            "No enabled routing candidate is available");
      double minCost = candidates.stream().mapToDouble(Candidate::cost).min().orElse(0),
          minLatency = candidates.stream().mapToDouble(Candidate::latency).min().orElse(0);
      double maxCost = candidates.stream().mapToDouble(Candidate::cost).max().orElse(1),
          maxLatency = candidates.stream().mapToDouble(Candidate::latency).max().orElse(1);
      double[] weights =
          switch (profile) {
            case "quality" -> new double[] {.60, .10, .15, .15};
            case "economy" -> new double[] {.15, .70, .05, .10};
            case "latency" -> new double[] {.20, .10, .60, .10};
            default -> new double[] {.35, .30, .20, .15};
          };
      Candidate best =
          candidates.stream()
              .max(
                  Comparator.comparingDouble(
                      c ->
                          weights[0] * c.quality()
                              + weights[1] * inverse(c.cost(), minCost, maxCost)
                              + weights[2] * inverse(c.latency(), minLatency, maxLatency)
                              + weights[3] * c.reliability()
                              + (hashedSession.isEmpty()
                                  ? 0
                                  : .02 * affinity(hashedSession, c.model()))))
              .orElseThrow();
      String fallback =
          db
              .query(
                  "SELECT target_model FROM model_alias WHERE alias=?",
                  (rs, i) -> rs.getString(1),
                  requested)
              .stream()
              .filter(m -> candidates.stream().anyMatch(c -> c.model().equals(m)))
              .findFirst()
              .orElse(candidates.getFirst().model());
      String mode = system.path("mode").asText("off"),
          version = system.path("policyVersion").asText("java-1");
      int bucket =
          Math.floorMod(
              new java.math.BigInteger(
                      digest(
                              version
                                  + ":"
                                  + (hashedSession.isEmpty()
                                      ? UUID.randomUUID().toString()
                                      : hashedSession))
                          .substring(0, 16),
                      16)
                  .mod(java.math.BigInteger.valueOf(100))
                  .intValue(),
              100);
      boolean active =
          "active".equals(mode) && bucket < system.path("activationPercent").asInt(100);
      ObjectNode decision =
          json.createObjectNode()
              .put("group", group.path("id").asText())
              .put(
                  "mode",
                  active
                      ? "active"
                      : "shadow".equals(mode) || "active".equals(mode) ? "shadow" : "off")
              .put("profile", profile)
              .put("selected", active ? best.model() : fallback)
              .put("recommended", best.model())
              .put("policyVersion", version);
      return new Selection(decision.path("selected").asText(), decision);
    }
    return null;
  }

  void record(JsonNode d, String requested) {
    if (d.isObject())
      db.update(
          "INSERT INTO"
              + " gateway_routing_decision(id,requested_model,group_id,mode,profile,selected,recommended,policy_version)"
              + " VALUES (?,?,?,?,?,?,?,?)",
          UUID.randomUUID(),
          requested,
          d.path("group").asText(),
          d.path("mode").asText(),
          d.path("profile").asText(),
          d.path("selected").asText(),
          d.path("recommended").asText(),
          d.path("policyVersion").asText());
  }

  static Set<String> profiles() {
    return Set.of("quality", "balanced", "economy", "latency");
  }

  private record Candidate(
      String model, double quality, double cost, double latency, double reliability) {}

  record Selection(String model, ObjectNode decision) {}

  private static GatewayException bad(String detail) {
    return new GatewayException(
        HttpStatus.BAD_REQUEST, "invalid_request_error", "invalid_routing_config", detail);
  }
}

@RestController
@ConditionalOnProperty(name = "polygate.database.enabled", havingValue = "true")
class SmartRoutingController {
  private final SmartRoutingService router;
  private final ConfigurationService config;
  private final AdminAuthService auth;
  private final JdbcTemplate db;

  SmartRoutingController(
      SmartRoutingService router,
      ConfigurationService config,
      AdminAuthService auth,
      JdbcTemplate db) {
    this.router = router;
    this.config = config;
    this.auth = auth;
    this.db = db;
  }

  @GetMapping("/admin/router/config")
  ObjectNode get(@RequestHeader HttpHeaders h) {
    auth.requireAdmin(h);
    return router.configuration();
  }

  @PutMapping("/admin/router/config")
  ObjectNode put(@RequestHeader HttpHeaders h, @RequestBody JsonNode input) {
    auth.requireWrite(h);
    auth.requireAdmin(h);
    return router.update(input);
  }

  @GetMapping("/admin/router/status")
  Map<String, Object> status(@RequestHeader HttpHeaders h) {
    auth.requireAdmin(h);
    Map<String, Object> out = new LinkedHashMap<>();
    JsonNode settings = config.system().path("smartRouting");
    settings.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue()));
    List<Map<String, Object>> groups = new ArrayList<>();
    for (JsonNode g : router.configuration().path("groups"))
      groups.add(
          Map.of(
              "id",
              g.path("id"),
              "aliases",
              g.path("aliases"),
              "defaultProfile",
              g.path("defaultProfile").asText("balanced"),
              "candidateCount",
              g.path("candidates").size()));
    out.put("groups", groups);
    out.put(
        "decisionsTotal",
        db.queryForObject("SELECT count(*) FROM gateway_routing_decision", Long.class));
    for (String mode : List.of("active", "shadow", "off"))
      out.put(
          "off".equals(mode) ? "staticDecisionsTotal" : mode + "DecisionsTotal",
          db.queryForObject(
              "SELECT count(*) FROM gateway_routing_decision WHERE mode=?", Long.class, mode));
    out.put(
        "shadowDisagreementsTotal",
        db.queryForObject(
            "SELECT count(*) FROM gateway_routing_decision WHERE mode='shadow' AND"
                + " selected<>recommended",
            Long.class));
    for (String field : List.of("selected", "recommended")) {
      Map<String, Long> counts = new TreeMap<>();
      db.query(
          "SELECT " + field + ",count(*) n FROM gateway_routing_decision GROUP BY " + field,
          (org.springframework.jdbc.core.RowCallbackHandler)
              rs -> counts.put(rs.getString(1), rs.getLong(2)));
      out.put(field + "ByCandidate", counts);
    }
    out.put(
        "outcomes",
        db.queryForList(
            "SELECT provider_id||':'||upstream_model candidate,count(*) attempts,count(*)"
                + " FILTER(WHERE state='completed') successes,avg(CASE WHEN state='completed' THEN"
                + " 1.0 ELSE 0 END) AS \"successRate\",sum(latency_ms*weight)/NULLIF(sum(weight)"
                + " FILTER(WHERE latency_ms IS NOT NULL),0) AS \"latencyEwmaMs\" FROM (SELECT"
                + " *,power(.9,row_number() OVER(PARTITION BY provider_id,upstream_model ORDER BY"
                + " started_at DESC)-1) weight FROM gateway_request WHERE state<>'started' AND"
                + " started_at>now()-interval '1 hour') recent GROUP BY"
                + " provider_id,upstream_model"));
    return out;
  }
}
