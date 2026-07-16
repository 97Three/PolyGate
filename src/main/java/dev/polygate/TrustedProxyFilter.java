package dev.polygate;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(-200)
class TrustedProxyFilter extends OncePerRequestFilter {
  private final List<String> trusted;

  TrustedProxyFilter(@Value("${POLYGATE_TRUSTED_PROXIES:}") String cidrs) {
    trusted = Arrays.stream(cidrs.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    if (trusted.stream().anyMatch(s -> !IpPolicy.valid(s)))
      throw new IllegalArgumentException("Invalid trusted proxy CIDR");
  }

  static String clientIp(String peer, String forwarded, List<String> trusted) {
    if (trusted.stream().noneMatch(c -> IpPolicy.matches(peer, c))
        || forwarded == null
        || forwarded.length() > 4096) return peer;
    String[] chain = forwarded.split(",", -1);
    if (chain.length > 32) return peer;
    List<String> hops = new ArrayList<>();
    for (String entry : chain) {
      String ip = entry.trim();
      if (ip.startsWith("[")) {
        int end = ip.indexOf(']');
        if (end < 0) return peer;
        ip = ip.substring(1, end);
      } else if (ip.indexOf(':') == ip.lastIndexOf(':') && ip.contains(":"))
        ip = ip.substring(0, ip.indexOf(':'));
      if (!IpPolicy.valid(ip) || ip.contains("/")) return peer;
      hops.add(ip);
    }
    hops.add(peer);
    for (int i = hops.size() - 1; i >= 0; i--) {
      String hop = hops.get(i);
      if (trusted.stream().noneMatch(c -> IpPolicy.matches(hop, c))) return hop;
    }
    return peer;
  }

  private static String last(String value) {
    return value == null ? null : value.substring(value.lastIndexOf(',') + 1).trim();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String peer = request.getRemoteAddr();
    if (trusted.stream().noneMatch(c -> IpPolicy.matches(peer, c))) {
      chain.doFilter(request, response);
      return;
    }
    String client = clientIp(peer, request.getHeader("X-Forwarded-For"), trusted);
    String host = last(request.getHeader("X-Forwarded-Host")),
        proto = last(request.getHeader("X-Forwarded-Proto"));
    if (host != null) {
      try {
        URI uri = URI.create("https://" + host);
        if (uri.getHost() == null
            || uri.getRawUserInfo() != null
            || !uri.getRawPath().isEmpty()
            || uri.getRawQuery() != null
            || uri.getRawFragment() != null) host = null;
      } catch (Exception e) {
        host = null;
      }
    }
    final String externalHost = host,
        scheme =
            Set.of("http", "https").contains(Objects.toString(proto, ""))
                ? proto
                : request.getScheme();
    chain.doFilter(
        new HttpServletRequestWrapper(request) {
          @Override
          public String getRemoteAddr() {
            return client;
          }

          @Override
          public String getRemoteHost() {
            return client;
          }

          @Override
          public String getScheme() {
            return scheme;
          }

          @Override
          public boolean isSecure() {
            return "https".equals(scheme);
          }

          @Override
          public String getHeader(String name) {
            return externalHost != null && "host".equalsIgnoreCase(name)
                ? externalHost
                : super.getHeader(name);
          }

          @Override
          public Enumeration<String> getHeaders(String name) {
            return externalHost != null && "host".equalsIgnoreCase(name)
                ? Collections.enumeration(List.of(externalHost))
                : super.getHeaders(name);
          }
        },
        response);
  }
}
