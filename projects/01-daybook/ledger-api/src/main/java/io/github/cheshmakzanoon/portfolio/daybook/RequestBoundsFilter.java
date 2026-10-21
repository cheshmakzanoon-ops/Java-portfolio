package io.github.cheshmakzanoon.portfolio.daybook;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounds actual bytes, including chunked requests, before JSON/CSV deserialization. */
@Component
public final class RequestBoundsFilter extends OncePerRequestFilter {
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!request.getRequestURI().startsWith("/v1/") || !request.getMethod().equals("POST")) {
      chain.doFilter(request, response);
      return;
    }
    byte[] bytes = request.getInputStream().readNBytes(1048577);
    if (bytes.length > 1048576) {
      response.setStatus(413);
      response.setContentType("application/json");
      response.getWriter().write("{\"code\":\"REQUEST_BYTE_BOUND\"}");
      return;
    }
    if (request.getContentType() != null
        && request.getContentType().startsWith("application/json")) {
      int depth = 0;
      boolean quoted = false, escaped = false;
      for (byte raw : bytes) {
        char ch = (char) (raw & 255);
        if (quoted) {
          if (escaped) escaped = false;
          else if (ch == '\\') escaped = true;
          else if (ch == '"') quoted = false;
        } else if (ch == '"') quoted = true;
        else if (ch == '{' || ch == '[') {
          if (++depth > 64) {
            response.setStatus(422);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"NESTING_BOUND\"}");
            return;
          }
        } else if (ch == '}' || ch == ']') depth--;
      }
    }
    chain.doFilter(
        new HttpServletRequestWrapper(request) {
          public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(bytes);
            return new ServletInputStream() {
              public int read() {
                return input.read();
              }

              public boolean isFinished() {
                return input.available() == 0;
              }

              public boolean isReady() {
                return true;
              }

              public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("synchronous adapter");
              }
            };
          }

          public BufferedReader getReader() {
            return new BufferedReader(
                new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
          }
        },
        response);
  }
}
