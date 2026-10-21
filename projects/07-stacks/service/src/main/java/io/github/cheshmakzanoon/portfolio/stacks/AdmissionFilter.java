package io.github.cheshmakzanoon.portfolio.stacks;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.concurrent.Semaphore;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(-200)
public class AdmissionFilter extends OncePerRequestFilter {
  private final Semaphore permits = new Semaphore(32);

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!permits.tryAcquire()) {
      reject(response, 429, "REQUEST_CAPACITY");
      return;
    }
    try {
      if (request.getContentLengthLong() > 65536) {
        reject(response, 413, "BODY_TOO_LARGE");
        return;
      }
      if (SetLike.hasBody(request.getMethod())) {
        byte[] bytes = request.getInputStream().readNBytes(65537);
        if (bytes.length > 65536) {
          reject(response, 413, "BODY_TOO_LARGE");
          return;
        }
        chain.doFilter(
            new HttpServletRequestWrapper(request) {
              @Override
              public ServletInputStream getInputStream() {
                ByteArrayInputStream in = new ByteArrayInputStream(bytes);
                return new ServletInputStream() {
                  public int read() {
                    return in.read();
                  }

                  public boolean isFinished() {
                    return in.available() == 0;
                  }

                  public boolean isReady() {
                    return true;
                  }

                  public void setReadListener(ReadListener l) {
                    throw new UnsupportedOperationException();
                  }
                };
              }

              @Override
              public BufferedReader getReader() {
                return new BufferedReader(
                    new InputStreamReader(
                        getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
              }

              @Override
              public int getContentLength() {
                return bytes.length;
              }

              @Override
              public long getContentLengthLong() {
                return bytes.length;
              }
            },
            response);
      } else chain.doFilter(request, response);
    } finally {
      permits.release();
    }
  }

  private void reject(HttpServletResponse response, int status, String code) throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.getWriter().write("{\"error\":\"" + code + "\"}");
  }

  private static final class SetLike {
    static boolean hasBody(String method) {
      return method.equals("POST") || method.equals("PUT") || method.equals("PATCH");
    }
  }
}
