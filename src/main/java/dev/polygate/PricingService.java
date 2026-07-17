package dev.polygate;

import com.fasterxml.jackson.databind.*;
import java.math.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
class PricingService {
  private final JdbcTemplate db;
  private final ObjectMapper json;

  PricingService(ObjectProvider<JdbcTemplate> db, ObjectMapper json) {
    this.db = db.getIfAvailable();
    this.json = json;
  }

  JsonNode card(String provider, String model) {
    if (db == null) return null;
    var rows =
        db.query(
            "SELECT config::text FROM gateway_provider WHERE id=?",
            (rs, i) -> rs.getString(1),
            provider);
    if (rows.isEmpty()) return null;
    try {
      JsonNode config = json.readTree(rows.getFirst());
      JsonNode card = config.path("modelPricing").path(model);
      boolean exact = card.isObject();
      if (!exact) card = config.path("pricing");
      if (!card.isObject() || !card.has("inputPerMillion") || !card.has("outputPerMillion"))
        return null;
      var snapshot = (com.fasterxml.jackson.databind.node.ObjectNode) card.deepCopy();
      snapshot.put("_exactModel", exact);
      snapshot.put("_providerRegion", config.path("governance").path("region").asText("global"));
      return snapshot;
    } catch (Exception e) {
      throw new IllegalStateException("Invalid stored pricing configuration", e);
    }
  }

  BigDecimal cost(JsonNode card, long input, long output, long read, long write) {
    if (card == null || !card.isObject()) return null;
    return rate(card, "inputPerMillion")
        .multiply(BigDecimal.valueOf(input))
        .add(rate(card, "outputPerMillion").multiply(BigDecimal.valueOf(output)))
        .add(rate(card, "cacheReadPerMillion").multiply(BigDecimal.valueOf(read)))
        .add(rate(card, "cacheWritePerMillion").multiply(BigDecimal.valueOf(write)))
        .divide(BigDecimal.valueOf(1_000_000), 8, RoundingMode.HALF_UP);
  }

  private static BigDecimal rate(JsonNode card, String field) {
    JsonNode value = card.path(field);
    return value.isNumber() ? value.decimalValue() : BigDecimal.ZERO;
  }

  JsonNode evidence(JsonNode card, String provider, String model, java.time.Instant started) {
    if (card == null
        || card.path("estimateOnly").asBoolean()
        || !card.path("_exactModel").asBoolean()
        || !"USD".equals(card.path("currency").asText())
        || card.path("version").asText().isBlank()
        || card.path("evidence").asText().isBlank()
        || !Set.of("provider_published", "provider_contract", "internal_chargeback")
            .contains(card.path("source").asText())
        || !"standard".equals(card.path("serviceTier").asText("standard"))) return null;
    try {
      if (java.time.Instant.parse(card.path("effectiveAt").asText()).isAfter(started)) return null;
    } catch (Exception e) {
      return null;
    }
    if (card.hasNonNull("region")
        && !card.path("region").asText().isBlank()
        && !card.path("region").asText().equals(card.path("_providerRegion").asText())) return null;
    var result =
        json.createObjectNode()
            .put("provider", provider)
            .put("model", model)
            .put("method", "exact_rate_card")
            .put("currency", "USD")
            .put("version", card.path("version").asText())
            .put("effectiveAt", card.path("effectiveAt").asText())
            .put("source", card.path("source").asText())
            .put("serviceTier", "standard")
            .put("evidence", card.path("evidence").asText());
    var rates = result.putObject("rates");
    for (String field :
        List.of(
            "inputPerMillion", "outputPerMillion", "cacheReadPerMillion", "cacheWritePerMillion"))
      rates.set(field, card.path(field));
    if (card.hasNonNull("region")) result.set("region", card.path("region"));
    return result;
  }
}
