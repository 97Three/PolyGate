package dev.polygate;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class RequestLogService {
  private final JdbcTemplate database;
  private final QuotaService quotas;
  private final PricingService pricing;
  private final TransactionTemplate transaction;
  private final com.fasterxml.jackson.databind.ObjectMapper json;
  private final BudgetService budget;
  private final IdempotencyService idempotency;
  private final InputAccounting inputs;
  private final LocalScheduler scheduler;
  private final UpstreamExchange exchange;

  RequestLogService(
      ObjectProvider<JdbcTemplate> database,
      ObjectProvider<QuotaService> quotas,
      PricingService pricing,
      com.fasterxml.jackson.databind.ObjectMapper json,
      ObjectProvider<BudgetService> budget,
      ObjectProvider<IdempotencyService> idempotency,
      InputAccounting inputs,
      LocalScheduler scheduler,
      UpstreamExchange exchange) {
    this.database = database.getIfAvailable();
    this.quotas = quotas.getIfAvailable();
    this.pricing = pricing;
    this.json = json;
    this.budget = budget.getIfAvailable();
    this.idempotency = idempotency.getIfAvailable();
    this.inputs = inputs;
    this.scheduler = scheduler;
    this.exchange = exchange;
    this.transaction =
        this.database == null
            ? null
            : new TransactionTemplate(
                new DataSourceTransactionManager(this.database.getDataSource()));
  }

  UUID start(
      String protocol,
      RoutingService.Route route,
      ClientAuthService.Principal principal,
      boolean stream,
      JsonNode request,
      org.springframework.http.HttpHeaders headers) {
    String publicModel = route.requestedModel(),
        providerId = route.providerId(),
        upstreamModel = route.upstreamModel();
    String traffic =
        java.util.Objects.requireNonNullElse(
                headers.getFirst("x-polygate-traffic-class"), "business")
            .trim()
            .toLowerCase(java.util.Locale.ROOT);
    if (!java.util.Set.of("business", "batch", "synthetic", "diagnostic").contains(traffic))
      throw new GatewayException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "invalid_request_error",
          "invalid_traffic_class",
          "Invalid traffic class");
    UUID id = UUID.randomUUID();
    var prepared = exchange.prepare(route, principal, headers, request);
    JsonNode reservationCard = exchange.reservationCard(prepared);
    long attemptInput = inputs.input(protocol, request, route, principal);
    long attemptOutput =
        request.path("max_tokens").asLong(request.path("max_completion_tokens").asLong(1024));
    boolean repair =
        prepared.stream().anyMatch(p -> repairEnabled(protocol, stream, request, p.route()));
    long maximumInput =
        repair ? Math.addExact(Math.multiplyExact(attemptInput, 2), 1024) : attemptInput;
    long maximumOutput = repair ? Math.multiplyExact(attemptOutput, 2) : attemptOutput;
    if (database != null) {
      transaction.executeWithoutResult(
          status -> {
            boolean idempotent =
                idempotency != null
                    && idempotency.reserve(id, principal, protocol, request, headers);
            JsonNode card = prepared.getFirst().card();
            if (!java.util.Set.of("standard", "default", "auto")
                .contains(request.path("service_tier").asText("standard"))) card = null;
            if (budget != null)
              budget.reserve(id, principal, request, reservationCard, maximumInput, maximumOutput);
            if (quotas != null)
              quotas.reserve(
                  id, principal, route, request, reservationCard, maximumInput, maximumOutput);
            database.update(
                "INSERT INTO gateway_request(id, client_protocol, requested_model, provider_id,"
                    + " upstream_model, user_id, api_key_id, stream, tool_requested,"
                    + " price_card,team_id,quota_subject,reserved_input_tokens,reserved_output_tokens,state)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?,?::jsonb,(SELECT team_id FROM"
                    + " gateway_api_key WHERE id=?),(SELECT COALESCE(quota_subject,id) FROM"
                    + " gateway_api_key WHERE id=?),?,?, 'started')",
                id,
                protocol,
                publicModel,
                providerId,
                upstreamModel,
                principal.userId(),
                principal.apiKeyId(),
                stream,
                request.path("tools").isArray() && !request.path("tools").isEmpty(),
                card == null ? null : card.toString(),
                principal.apiKeyId(),
                principal.apiKeyId(),
                maximumInput,
                maximumOutput);
            database.update(
                "UPDATE gateway_request SET attempt_input_tokens=?,attempt_output_tokens=? WHERE"
                    + " id=?",
                attemptInput,
                attemptOutput,
                id);
            database.update(
                "UPDATE gateway_request SET reservation_price_card=?::jsonb WHERE id=?",
                reservationCard == null ? null : reservationCard.toString(),
                id);
            database.update(
                "UPDATE gateway_request SET organization_id=?,project_id=?,environment_id=? WHERE"
                    + " id=?",
                principal.scope().organizationId(),
                principal.scope().projectId(),
                principal.scope().environmentId(),
                id);
            var attributes =
                org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            String address =
                attributes
                        instanceof
                        org.springframework.web.context.request.ServletRequestAttributes servlet
                    ? servlet.getRequest().getRemoteAddr()
                    : null;
            database.update(
                "UPDATE gateway_request SET"
                    + " provider_protocol=?,traffic_class=?,client_ip=?,has_idempotency_key=?,credential_id=?"
                    + " WHERE id=?",
                route.protocol(),
                traffic,
                address,
                idempotent,
                route.credentialId(),
                id);
          });
    }
    exchange.pin(id, prepared, route.requestTimeoutMs());
    return id;
  }

  void completed(UUID id, long elapsedMs, JsonNode usage) {
    exchange.release(id);
    scheduler.release(id);
    if (database == null) return;
    transaction.executeWithoutResult(
        status -> {
          if (budget != null) budget.lock(id);
          if (quotas != null) quotas.lock(id);
          database.update(
              "UPDATE gateway_request SET state='completed', http_status=200, upstream_status=200,"
                  + " ended_at=now(), latency_ms=? WHERE id=? AND state='started'",
              elapsedMs,
              id);
          recordUsage(id, usage);
          finalAttempt(id);
          if (budget != null) budget.settle(id);
          if (quotas != null) quotas.release(id);
          if (idempotency != null) idempotency.finish(id);
        });
  }

  private void recordUsage(UUID id, JsonNode usage) {
    var row =
        database.queryForMap(
            "SELECT reservation_price_card::text reservation_card,price_card::text"
                + " snapshot,reserved_input_tokens,reserved_output_tokens,provider_id,upstream_model,started_at"
                + " FROM gateway_request WHERE id=?",
            id);
    String snapshot = (String) row.get("snapshot");
    JsonNode card;
    try {
      card = snapshot == null ? null : json.readTree(snapshot);
    } catch (Exception error) {
      throw new IllegalStateException("Invalid pricing snapshot", error);
    }
    boolean estimated =
        usage == null
            || !validTokens(usage.path("input_tokens"))
            || !validTokens(usage.path("output_tokens"))
            || usage.has("cache_read_input_tokens")
                && !validTokens(usage.path("cache_read_input_tokens"))
            || usage.has("cache_creation_input_tokens")
                && !validTokens(usage.path("cache_creation_input_tokens"));
    long input =
        estimated
            ? ((Number) row.get("reserved_input_tokens")).longValue()
            : nonnegative(usage.path("input_tokens"));
    long output =
        estimated
            ? ((Number) row.get("reserved_output_tokens")).longValue()
            : nonnegative(usage.path("output_tokens"));
    long read = estimated ? 0 : nonnegative(usage.path("cache_read_input_tokens")),
        write = estimated ? 0 : nonnegative(usage.path("cache_creation_input_tokens"));
    JsonNode estimateCard = card;
    if (estimated && row.get("reservation_card") != null)
      try {
        estimateCard = json.readTree(row.get("reservation_card").toString());
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    var cost = pricing.cost(estimated ? estimateCard : card, input, output, read, write);
    JsonNode evidence =
        estimated
            ? null
            : pricing.evidence(
                card,
                (String) row.get("provider_id"),
                (String) row.get("upstream_model"),
                ((java.sql.Timestamp) row.get("started_at")).toInstant());
    var proven = evidence == null ? null : cost;
    database.update(
        "INSERT INTO"
            + " gateway_usage(request_id,input_tokens,output_tokens,cache_read_tokens,cache_write_tokens,cost_estimate,estimated,actual_cost,billable_cost,pricing_evidence)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?::jsonb) ON CONFLICT(request_id) DO NOTHING",
        id,
        input,
        output,
        read,
        write,
        cost,
        estimated,
        proven,
        proven,
        evidence == null ? null : evidence.toString());
  }

  void failed(UUID id, long elapsedMs, int status, String safeCode) {
    finishFailure(id, elapsedMs, status, safeCode, "failed");
  }

  private void finishFailure(UUID id, long elapsedMs, int status, String safeCode, String state) {
    finishFailure(id, elapsedMs, status, safeCode, state, null);
  }

  private void finishFailure(
      UUID id, long elapsedMs, int status, String safeCode, String state, JsonNode observedUsage) {
    exchange.release(id);
    scheduler.release(id);
    if (database == null) return;
    transaction.executeWithoutResult(
        tx -> {
          if (budget != null) budget.lock(id);
          if (quotas != null) quotas.lock(id);
          database.update(
              "UPDATE gateway_request SET state=?, http_status=?, ended_at=now(), latency_ms=?,"
                  + " safe_error_code=? WHERE id=?",
              state,
              status,
              elapsedMs,
              safeCode,
              id);
          recordUsage(id, observedUsage);
          finalAttempt(id);
          if (budget != null) budget.settle(id);
          if (quotas != null) quotas.release(id);
          if (idempotency != null) idempotency.finish(id);
        });
  }

  void cancelled(UUID id, long elapsedMs) {
    finishFailure(id, elapsedMs, 499, "client_cancelled", "cancelled");
  }

  void upstreamFailed(UUID id, long elapsedMs, RuntimeException error) {
    upstreamFailed(id, elapsedMs, error, null);
  }

  void upstreamFailed(UUID id, long elapsedMs, RuntimeException error, JsonNode observedUsage) {
    GatewayException safe = UpstreamFailures.safe(error);
    if (observedUsage == null && error instanceof UpstreamExchange.BeforeDispatch) {
      rejectBeforeDispatch(id, safe.status.value(), safe.code);
      return;
    }
    if (observedUsage == null
        && (error instanceof UpstreamExchange.BeforeDispatch
            || java.util.Set.of(400, 401, 402, 403, 404, 413, 422, 429, 503)
                .contains(safe.upstreamStatus))) {
      observedUsage = json.createObjectNode().put("input_tokens", 0).put("output_tokens", 0);
    }
    if (database != null)
      database.update(
          "UPDATE gateway_request SET upstream_status=? WHERE id=?",
          safe.upstreamStatus > 0 ? safe.upstreamStatus : null,
          id);
    finishFailure(
        id,
        elapsedMs,
        safe.status.value(),
        safe.upstreamStatus > 0 ? "upstream_http_" + safe.upstreamStatus : safe.code,
        "failed",
        observedUsage);
  }

  void retrying(UUID id, int status) {
    if (database == null) return;
    transaction.executeWithoutResult(
        tx -> {
          database.update(
              """
INSERT INTO gateway_provider_attempt(id,request_id,ordinal,provider_id,upstream_model,provider_protocol,credential_id,state,http_status,safe_error_code,started_at,latency_ms,cost_estimate)
SELECT ?,r.id,r.retry_count+1,r.provider_id,r.upstream_model,r.provider_protocol,r.credential_id,'failed',?,'retryable_upstream_rejection',COALESCE((SELECT max(a.ended_at) FROM gateway_provider_attempt a WHERE a.request_id=r.id),r.started_at),0,0 FROM gateway_request r WHERE r.id=?
""",
              UUID.randomUUID(),
              status,
              id);
          database.update(
              "UPDATE gateway_request SET retry_count=retry_count+1,upstream_status=? WHERE id=?",
              status,
              id);
        });
  }

  private void finalAttempt(UUID id) {
    database.update(
        """
INSERT INTO gateway_provider_attempt(id,request_id,ordinal,provider_id,upstream_model,provider_protocol,credential_id,state,http_status,safe_error_code,started_at,latency_ms,input_tokens,output_tokens,cache_read_tokens,cache_write_tokens,cost_estimate,actual_cost,billable_cost,pricing_evidence,estimated)
SELECT ?,r.id,r.retry_count+1,r.provider_id,r.upstream_model,r.provider_protocol,r.credential_id,r.state,COALESCE(r.upstream_status,r.http_status),r.safe_error_code,COALESCE((SELECT max(a.ended_at) FROM gateway_provider_attempt a WHERE a.request_id=r.id),r.started_at),(extract(epoch FROM now()-COALESCE((SELECT max(a.ended_at) FROM gateway_provider_attempt a WHERE a.request_id=r.id),r.started_at))*1000)::bigint,
GREATEST(u.input_tokens-COALESCE(prior.input,0),0),GREATEST(u.output_tokens-COALESCE(prior.output,0),0),GREATEST(u.cache_read_tokens-COALESCE(prior.cache_read,0),0),GREATEST(u.cache_write_tokens-COALESCE(prior.cache_write,0),0),
CASE WHEN u.cost_estimate IS NULL THEN NULL ELSE GREATEST(u.cost_estimate-COALESCE(prior.cost,0),0) END,CASE WHEN u.actual_cost IS NULL THEN NULL ELSE GREATEST(u.actual_cost-COALESCE(prior.actual,0),0) END,CASE WHEN u.billable_cost IS NULL THEN NULL ELSE GREATEST(u.billable_cost-COALESCE(prior.billable,0),0) END,u.pricing_evidence,u.estimated
FROM gateway_request r LEFT JOIN gateway_usage u ON u.request_id=r.id LEFT JOIN LATERAL(SELECT sum(a.input_tokens) input,sum(a.output_tokens) output,sum(a.cache_read_tokens) cache_read,sum(a.cache_write_tokens) cache_write,sum(a.cost_estimate) cost,sum(a.actual_cost) actual,sum(a.billable_cost) billable FROM gateway_provider_attempt a WHERE a.request_id=r.id) prior ON true WHERE r.id=? ON CONFLICT(request_id,ordinal) DO NOTHING
""",
        UUID.randomUUID(),
        id);
  }

  private static long nonnegative(JsonNode value) {
    return value.canConvertToLong() ? Math.max(0, value.asLong()) : 0;
  }

  private static boolean validTokens(JsonNode value) {
    return value.isIntegralNumber()
        && value.canConvertToLong()
        && value.asLong() >= 0
        && value.asLong() <= 1_000_000_000L;
  }

  static boolean repairEnabled(
      String protocol, boolean stream, JsonNode request, RoutingService.Route route) {
    return "anthropic".equals(protocol)
        && !stream
        && request.path("tools").isArray()
        && !request.path("tools").isEmpty()
        && "openai_compat".equals(route.protocol())
        && route.config().path("toolUse").path("repairInvalidArguments").asBoolean();
  }

  void responseAttempt(
      UUID id, JsonNode usage, boolean success, boolean repairAttempt, boolean recovered) {
    if (database == null) return;
    transaction.executeWithoutResult(
        tx -> {
          var row =
              database.queryForMap(
                  "SELECT *,price_card::text card FROM gateway_request WHERE id=? FOR UPDATE", id);
          JsonNode card;
          try {
            card = row.get("card") == null ? null : json.readTree(row.get("card").toString());
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
          boolean estimated =
              usage == null
                  || !validTokens(usage.path("input_tokens"))
                  || !validTokens(usage.path("output_tokens"));
          long
              input =
                  estimated
                      ? ((Number) row.get("attempt_input_tokens")).longValue()
                      : usage.path("input_tokens").asLong(),
              output =
                  estimated
                      ? ((Number) row.get("attempt_output_tokens")).longValue()
                      : usage.path("output_tokens").asLong(),
              read = estimated ? 0 : usage.path("cache_read_input_tokens").asLong(0),
              write = estimated ? 0 : usage.path("cache_creation_input_tokens").asLong(0);
          var cost = pricing.cost(card, input, output, read, write);
          JsonNode evidence =
              estimated
                  ? null
                  : pricing.evidence(
                      card,
                      (String) row.get("provider_id"),
                      (String) row.get("upstream_model"),
                      ((java.sql.Timestamp) row.get("started_at")).toInstant());
          database.update(
              "INSERT INTO"
                  + " gateway_provider_attempt(id,request_id,ordinal,provider_id,upstream_model,provider_protocol,credential_id,state,http_status,safe_error_code,started_at,latency_ms,input_tokens,output_tokens,cache_read_tokens,cache_write_tokens,cost_estimate,actual_cost,billable_cost,pricing_evidence,estimated)"
                  + " SELECT"
                  + " ?,r.id,r.retry_count+1,r.provider_id,r.upstream_model,r.provider_protocol,r.credential_id,?,200,?,COALESCE((SELECT"
                  + " max(a.ended_at) FROM gateway_provider_attempt a WHERE"
                  + " a.request_id=r.id),r.started_at),(extract(epoch FROM now()-COALESCE((SELECT"
                  + " max(a.ended_at) FROM gateway_provider_attempt a WHERE"
                  + " a.request_id=r.id),r.started_at))*1000)::bigint,?,?,?,?,?,?,?,?::jsonb,? FROM"
                  + " gateway_request r WHERE r.id=? ON CONFLICT(request_id,ordinal) DO NOTHING",
              UUID.randomUUID(),
              success ? "completed" : "failed",
              success ? null : "tool_validation_failed",
              input,
              output,
              read,
              write,
              cost,
              evidence == null ? null : cost,
              evidence == null ? null : cost,
              evidence == null ? null : evidence.toString(),
              estimated,
              id);
          database.update(
              "UPDATE gateway_request SET tool_repair_attempted=tool_repair_attempted OR"
                  + " ?,tool_repair_recovered=tool_repair_recovered OR ?,retry_count=retry_count+?"
                  + " WHERE id=?",
              repairAttempt,
              recovered,
              repairAttempt ? 1 : 0,
              id);
        });
  }

  void deliveryFailed(UUID id) {
    if (database != null)
      database.update(
          "UPDATE gateway_request SET"
              + " state='failed',http_status=502,ended_at=now(),latency_ms=COALESCE(latency_ms,(extract(epoch"
              + " FROM"
              + " now()-started_at)*1000)::bigint),safe_error_code='protocol_conversion_failure'"
              + " WHERE id=?",
          id);
  }

  private void rejectBeforeDispatch(UUID id, int status, String code) {
    exchange.release(id);
    scheduler.release(id);
    if (database == null) return;
    transaction.executeWithoutResult(
        tx -> {
          if (budget != null) budget.lock(id);
          if (quotas != null) quotas.lock(id);
          database.update(
              "UPDATE gateway_request SET"
                  + " state='failed',http_status=?,safe_error_code=?,ended_at=now(),latency_ms=(extract(epoch"
                  + " FROM now()-started_at)*1000)::bigint WHERE id=?",
              status,
              code,
              id);
          database.update(
              "INSERT INTO"
                  + " gateway_usage(request_id,cost_estimate,actual_cost,billable_cost,estimated,pricing_evidence)"
                  + " VALUES"
                  + " (?,0,0,0,false,'{\"method\":\"not_dispatched\",\"source\":\"gateway_admission\"}')"
                  + " ON CONFLICT(request_id) DO NOTHING",
              id);
          if (budget != null) budget.settle(id);
          if (quotas != null) quotas.release(id);
          if (idempotency != null) idempotency.finish(id);
        });
  }

  void dispatch(UUID id, RoutingService.Route route, JsonNode card) {
    if (database != null)
      database.update(
          "UPDATE gateway_request SET fallback_from_provider=CASE WHEN fallback_from_provider IS"
              + " NULL AND provider_id<>? THEN provider_id ELSE fallback_from_provider"
              + " END,provider_id=?,upstream_model=?,provider_protocol=?,credential_id=?,price_card=?::jsonb,dispatched_at=COALESCE(dispatched_at,now())"
              + " WHERE id=?",
          route.providerId(),
          route.providerId(),
          route.upstreamModel(),
          route.protocol(),
          route.credentialId(),
          card == null ? null : card.toString(),
          id);
  }
}
