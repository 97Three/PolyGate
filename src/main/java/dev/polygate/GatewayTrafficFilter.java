package dev.polygate;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
class GatewayTrafficFilter extends OncePerRequestFilter {
  private final ConfigurationService config;
  private final AtomicInteger active = new AtomicInteger();

  GatewayTrafficFilter(ConfigurationService config) {
    this.config = config;
  }

  int activeCount() {
    return active.get();
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/v1/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var limits = config.limits();
    if (request.getContentLengthLong() > limits.bodyBytes()) {
      deny(response, 413, "request_too_large", "Request body exceeds the configured limit");
      return;
    }
    if (active.incrementAndGet() > limits.concurrent()) {
      active.decrementAndGet();
      deny(response, 429, "concurrency_limit", "Gateway concurrency limit reached");
      return;
    }
    AtomicBoolean released = new AtomicBoolean();
    Runnable release =
        () -> {
          if (released.compareAndSet(false, true)) active.decrementAndGet();
        };
    HttpServletRequest wrapped =
        new HttpServletRequestWrapper(request) {
          private ServletInputStream input;

          @Override
          public ServletInputStream getInputStream() throws IOException {
            if (input != null) return input;
            ServletInputStream original = super.getInputStream();
            input =
                new ServletInputStream() {
                  long read;

                  private void count(long n) throws IOException {
                    if (n > 0 && (read += n) > limits.bodyBytes()) throw new BodyLimitException();
                  }

                  @Override
                  public int read() throws IOException {
                    int n = original.read();
                    count(n < 0 ? 0 : 1);
                    return n;
                  }

                  @Override
                  public int read(byte[] bytes, int offset, int length) throws IOException {
                    int n = original.read(bytes, offset, length);
                    count(n);
                    return n;
                  }

                  @Override
                  public boolean isFinished() {
                    return original.isFinished();
                  }

                  @Override
                  public boolean isReady() {
                    return original.isReady();
                  }

                  @Override
                  public void setReadListener(ReadListener listener) {
                    original.setReadListener(listener);
                  }

                  @Override
                  public void close() throws IOException {
                    original.close();
                  }
                };
            return input;
          }

          @Override
          public BufferedReader getReader() throws IOException {
            return new BufferedReader(
                new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
          }
        };
    try {
      chain.doFilter(wrapped, response);
    } finally {
      if (request.isAsyncStarted()) {
        try {
          request
              .getAsyncContext()
              .addListener(
                  new AsyncListener() {
                    public void onComplete(AsyncEvent event) {
                      release.run();
                    }

                    public void onTimeout(AsyncEvent event) {
                      release.run();
                    }

                    public void onError(AsyncEvent event) {
                      release.run();
                    }

                    public void onStartAsync(AsyncEvent event) {
                      event.getAsyncContext().addListener(this);
                    }
                  });
        } catch (IllegalStateException ignored) {
          release.run();
        }
      } else release.run();
    }
  }

  private static void deny(HttpServletResponse response, int status, String code, String message)
      throws IOException {
    DetailedMetrics.rejected(code);
    response.setStatus(status);
    response.setContentType("application/json");
    response.setHeader("x-polygate-error-contract", "v1");
    response
        .getWriter()
        .write(
            "{\"error\":{\"type\":\"invalid_request_error\",\"code\":\""
                + code
                + "\",\"status\":"
                + status
                + ",\"message\":\""
                + message
                + "\"}}");
  }

  static class BodyLimitException extends IOException {}
}
