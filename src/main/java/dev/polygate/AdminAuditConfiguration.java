package dev.polygate;

import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
@ConditionalOnProperty(name = "polygate.database.enabled", havingValue = "true")
class AdminAuditConfiguration implements WebMvcConfigurer {
  private final JdbcTemplate db;
  private final AdminAuthService auth;

  AdminAuditConfiguration(JdbcTemplate db, AdminAuthService auth) {
    this.db = db;
    this.auth = auth;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(
            new HandlerInterceptor() {
              @Override
              public boolean preHandle(
                  HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (request.getAttribute("auditActor") == null) {
                  HttpHeaders headers = new HttpHeaders();
                  var names = request.getHeaderNames();
                  while (names.hasMoreElements()) {
                    String name = names.nextElement();
                    headers.put(name, Collections.list(request.getHeaders(name)));
                  }
                  try {
                    request.setAttribute("auditActor", auth.requireSession(headers).get("id"));
                  } catch (GatewayException ignored) {
                  }
                }
                return true;
              }

              @Override
              public void afterCompletion(
                  HttpServletRequest request,
                  HttpServletResponse response,
                  Object handler,
                  Exception error) {
                if (Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())
                    || Boolean.TRUE.equals(request.getAttribute("audited"))) return;
                request.setAttribute("audited", true);
                String path = request.getRequestURI();
                if (path.length() > 512) path = path.substring(0, 512);
                Object actor = request.getAttribute("auditActor");
                db.update(
                    "INSERT INTO"
                        + " gateway_audit(id,actor_id,action,resource_type,resource_id,safe_details)"
                        + " VALUES (?,?,?,'http',?,jsonb_build_object('status',?::integer))",
                    UUID.randomUUID(),
                    actor == null ? null : UUID.fromString(actor.toString()),
                    request.getMethod(),
                    path,
                    response.getStatus());
              }
            })
        .addPathPatterns("/admin/**");
  }
}
