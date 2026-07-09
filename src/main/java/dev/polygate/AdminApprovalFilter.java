package dev.polygate;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

@Component
@ConditionalOnProperty(name = "polygate.database.enabled", havingValue = "true")
class AdminApprovalFilter extends OncePerRequestFilter {
  private final GovernanceService governance;
  private final AdminAuthService auth;
  private final ObjectMapper json;
  private final TransactionTemplate transaction;

  AdminApprovalFilter(
      GovernanceService governance, AdminAuthService auth, ObjectMapper json, JdbcTemplate db) {
    this.governance = governance;
    this.auth = auth;
    this.json = json;
    transaction = new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()));
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !governance.requiresDualApproval()
        || !request.getRequestURI().startsWith("/admin/")
        || Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    HttpHeaders headers = new HttpHeaders();
    for (String name : Collections.list(request.getHeaderNames()))
      headers.put(name, Collections.list(request.getHeaders(name)));
    String path = request.getRequestURI();
    if (path.startsWith("/admin/auth/") || path.startsWith("/admin/governance/")) {
      chain.doFilter(request, response);
      return;
    }
    try {
      var actor = auth.requireSession(headers);
      if (!"admin".equals(actor.get("role"))) {
        chain.doFilter(request, response);
        return;
      }
      byte[] body = request.getInputStream().readNBytes(131073);
      if (body.length > 131072)
        throw new GatewayException(
            HttpStatus.PAYLOAD_TOO_LARGE,
            "invalid_request_error",
            "approval_payload_too_large",
            "Approval payload exceeds 128 KiB");
      JsonNode payload;
      try {
        payload = body.length == 0 ? json.createObjectNode() : json.readTree(body);
      } catch (Exception e) {
        throw new GatewayException(
            HttpStatus.BAD_REQUEST,
            "invalid_request_error",
            "invalid_json",
            "Invalid JSON request body");
      }
      Operation operation = operation(request, payload, actor);
      HttpServletRequest replay =
          new HttpServletRequestWrapper(request) {
            @Override
            public ServletInputStream getInputStream() {
              ByteArrayInputStream source = new ByteArrayInputStream(body);
              return new ServletInputStream() {
                public int read() {
                  return source.read();
                }

                public int read(byte[] target, int offset, int length) {
                  return source.read(target, offset, length);
                }

                public boolean isFinished() {
                  return source.available() == 0;
                }

                public boolean isReady() {
                  return true;
                }

                public void setReadListener(ReadListener listener) {
                  throw new UnsupportedOperationException("Synchronous admin requests only");
                }
              };
            }

            @Override
            public BufferedReader getReader() {
              return new BufferedReader(
                  new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            }
          };
      if (operation == null) {
        chain.doFilter(replay, response);
        return;
      }
      ContentCachingResponseWrapper buffered = new ContentCachingResponseWrapper(response);
      try {
        transaction.executeWithoutResult(
            tx -> {
              governance.requireApproved(
                  headers, operation.action(), operation.target(), operation.payload());
              try {
                chain.doFilter(replay, buffered);
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              } catch (ServletException e) {
                throw new IllegalStateException(e);
              }
              if (buffered.getStatus() >= 400) tx.setRollbackOnly();
            });
        buffered.copyBodyToResponse();
      } catch (UncheckedIOException e) {
        throw e.getCause();
      }
    } catch (GatewayException error) {
      if (response.isCommitted()) throw error;
      response.setStatus(error.status.value());
      response.setContentType("application/json");
      response.setHeader("x-polygate-error-contract", "v1");
      json.writeValue(
          response.getOutputStream(),
          Map.of(
              "error",
              Map.of(
                  "type",
                  error.type,
                  "code",
                  error.code,
                  "status",
                  error.status.value(),
                  "message",
                  error.getMessage())));
    }
  }

  private Operation operation(
      HttpServletRequest request, JsonNode body, Map<String, Object> actor) {
    String[] parts = request.getRequestURI().split("/");
    if (parts.length < 3) return null;
    String resource = parts[2], id = parts.length > 3 ? parts[3] : "", method = request.getMethod();
    JsonNode payload = body;
    if ("providers".equals(resource)) {
      if (parts.length > 4 && Set.of("balance").contains(parts[4])
          || parts.length > 4 && "models".equals(parts[4]) && "POST".equals(method)) return null;
      if (id.isEmpty()) id = body.path("id").asText();
      if ("DELETE".equals(method) && parts.length == 4) {
        ObjectNode patch = json.createObjectNode();
        patch.put("force", Boolean.parseBoolean(request.getParameter("force")));
        payload = patch;
      }
      String action =
          parts.length > 4 && Set.of("credentials", "credential-pool").contains(parts[4])
              ? "secret.rotation"
              : parts.length > 4 && "models".equals(parts[4])
                  ? "model.production_promotion"
                  : "provider.allowlist_change";
      String target = "provider:" + id;
      if (parts.length > 5 && "credentials".equals(parts[4])) target += "/credential:" + parts[5];
      return new Operation(action, target, payload);
    }
    if ("aliases".equals(resource))
      return new Operation(
          "provider.allowlist_change",
          "alias:" + (id.isEmpty() ? body.path("alias").asText() : id),
          body);
    if ("users".equals(resource)) {
      if (parts.length > 4 && "api-keys".equals(parts[4]))
        return new Operation("identity.permission", "user:" + id, body);
      return new Operation(
          "identity.permission",
          "user:" + (id.isEmpty() ? body.path("username").asText() : id),
          body);
    }
    if ("api-keys".equals(resource)) {
      if (parts.length > 4 && "rotate".equals(parts[4])) {
        if (parts.length == 6 && "POST".equals(method)) {
          ObjectNode patch = json.createObjectNode();
          patch.put("replacementId", parts[5]);
          return new Operation("secret.rotation", "api-key:" + id, patch);
        }
        return null;
      }
      if ("DELETE".equals(method) || parts.length > 4 && "disable".equals(parts[4])) return null;
      return new Operation(
          "identity.permission",
          id.isEmpty()
              ? "user:" + body.path("userId").asText(actor.get("id").toString())
              : "api-key:" + id,
          body);
    }
    if ("teams".equals(resource))
      return new Operation(
          "identity.permission",
          "team:" + (id.isEmpty() ? body.path("slug").asText(body.path("name").asText()) : id),
          body);
    if ("quotas".equals(resource))
      return new Operation(
          "budget.hard_limit", "quota:" + (id.isEmpty() ? body.path("userId").asText() : id), body);
    if ("enterprise".equals(resource) && parts.length > 3 && "budget".equals(parts[3]))
      return new Operation("budget.hard_limit", TenantScope.from(body).key(), body);
    if ("settings".equals(resource) && parts.length == 3)
      return new Operation("data_egress.change", "system", body);
    if ("retention".equals(resource)) return new Operation("data_egress.change", "retention", body);
    if ("router".equals(resource)) return new Operation("data_egress.change", "routing", body);
    if ("ops".equals(resource) && parts.length > 3 && "configuration".equals(parts[3]))
      return new Operation("data_egress.change", "operations", body);
    return null;
  }

  private record Operation(String action, String target, JsonNode payload) {}
}
