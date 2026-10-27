package ru.ssau.bibliography;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ApiRequestFilter extends OncePerRequestFilter {
  private final ObjectMapper mapper;
  private final Clock clock;
  private final boolean trustProxyHeaders;
  private final Cache<String, ArrayDeque<Long>> requests =
      Caffeine.newBuilder().maximumSize(10000).expireAfterAccess(Duration.ofMinutes(1)).build();

  public ApiRequestFilter(
      ObjectMapper mapper,
      Clock clock,
      @org.springframework.beans.factory.annotation.Value(
              "${bibliography.trust-proxy-headers:false}")
          boolean trustProxyHeaders) {
    this.mapper = mapper;
    this.clock = clock;
    this.trustProxyHeaders = trustProxyHeaders;
  }

  void clearLimits() {
    requests.invalidateAll();
  }

  private boolean allow(String key, int limit) {
    var times = requests.get(key, k -> new ArrayDeque<>());
    synchronized (times) {
      long now = clock.millis();
      while (!times.isEmpty() && times.peekFirst() <= now - 60000) times.removeFirst();
      if (times.size() >= limit) return false;
      times.addLast(now);
      return true;
    }
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    response.setHeader("X-Content-Type-Options", "nosniff");
    if (!request.getRequestURI().startsWith("/api/")) {
      chain.doFilter(request, response);
      return;
    }
    // By default use the socket peer. In isolated Compose, only Nginx supplies X-Real-IP.
    String peer = request.getRemoteAddr();
    String forwarded = request.getHeader("X-Real-IP");
    if (trustProxyHeaders && forwarded != null && forwarded.matches("[0-9a-fA-F:.]+"))
      peer = forwarded;
    if (!allow("all:" + peer, 120)
        || (request.getRequestURI().matches("/api/import/?") && !allow("imports:" + peer, 20))) {
      response.setHeader("Retry-After", "60");
      reject(response, 429, "Слишком много запросов. Подождите минуту и попробуйте снова.");
      return;
    }
    if (request.getContentLengthLong() > 32768) {
      reject(response, 413, "Размер запроса не должен превышать 32 КиБ.");
      return;
    }
    byte[] body = request.getInputStream().readNBytes(32769);
    if (body.length > 32768) {
      reject(response, 413, "Размер запроса не должен превышать 32 КиБ.");
      return;
    }
    chain.doFilter(
        new HttpServletRequestWrapper(request) {
          @Override
          public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
              @Override
              public int read() {
                return input.read();
              }

              @Override
              public int read(byte[] b, int off, int len) {
                return input.read(b, off, len);
              }

              @Override
              public boolean isFinished() {
                return input.available() == 0;
              }

              @Override
              public boolean isReady() {
                return true;
              }

              @Override
              public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException();
              }
            };
          }

          @Override
          public BufferedReader getReader() {
            return new BufferedReader(
                new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
          }
        },
        response);
  }

  private void reject(HttpServletResponse response, int status, String message) throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    mapper.writeValue(response.getOutputStream(), Map.of("detail", message));
  }
}
