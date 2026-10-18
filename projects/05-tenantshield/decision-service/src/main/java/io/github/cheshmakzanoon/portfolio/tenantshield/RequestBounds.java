package io.github.cheshmakzanoon.portfolio.tenantshield;

import com.github.benmanes.caffeine.cache.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestBounds extends OncePerRequestFilter {
  private final Semaphore permits = new Semaphore(64);
  private final AtomicInteger inFlight = new AtomicInteger(), maximum = new AtomicInteger();
  private final Cache<String, Bucket> quotas =
      Caffeine.newBuilder().maximumSize(1000).expireAfterAccess(Duration.ofMinutes(5)).build();

  private static final class Bucket {
    long second;
    int count;
    final AtomicInteger inFlight = new AtomicInteger(),
        maximum = new AtomicInteger(),
        rejected = new AtomicInteger();

    synchronized boolean take() {
      long now = System.nanoTime() / 1_000_000_000L;
      if (now != second) {
        second = now;
        count = 0;
      }
      return ++count <= 5000;
    }
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String[] path = request.getRequestURI().split("/");
    String tenant =
        path.length > 3 && path[1].equals("v1") && path[2].equals("tenants") ? path[3] : "_other";
    if (!tenant.equals("_other")) {
      try {
        tenant =
            org.springframework.web.util.UriUtils.decode(
                tenant, java.nio.charset.StandardCharsets.UTF_8);
      } catch (IllegalArgumentException e) {
        error(response, 400, "invalid tenant path");
        return;
      }
      if (!tenant.matches("[A-Za-z0-9_-]{1,128}")) {
        error(response, 400, "invalid tenant path");
        return;
      }
    }
    Bucket bucket = quotas.get(tenant, k -> new Bucket());
    if (!bucket.take() || !permits.tryAcquire()) {
      bucket.rejected.incrementAndGet();
      error(response, 429, "authorization capacity exceeded");
      return;
    }
    int tenantActive = bucket.inFlight.incrementAndGet();
    bucket.maximum.accumulateAndGet(tenantActive, Math::max);
    int active = inFlight.incrementAndGet();
    maximum.accumulateAndGet(active, Math::max);
    try {
      byte[] body = request.getInputStream().readNBytes(262145);
      if (body.length > 262144) {
        error(response, 413, "request body limit");
        return;
      }
      HttpServletRequestWrapper bounded =
          new HttpServletRequestWrapper(request) {
            @Override
            public ServletInputStream getInputStream() {
              ByteArrayInputStream b = new ByteArrayInputStream(body);
              return new ServletInputStream() {
                public int read() {
                  return b.read();
                }

                public int read(byte[] a, int o, int l) {
                  return b.read(a, o, l);
                }

                public boolean isFinished() {
                  return b.available() == 0;
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
                  new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            }
          };
      chain.doFilter(bounded, response);
    } finally {
      bucket.inFlight.decrementAndGet();
      inFlight.decrementAndGet();
      permits.release();
    }
  }

  private static void error(HttpServletResponse r, int status, String detail) throws IOException {
    r.setStatus(status);
    r.setContentType("application/problem+json");
    r.getWriter()
        .write(
            "{\"type\":\"about:blank\",\"status\":" + status + ",\"detail\":\"" + detail + "\"}");
  }

  public java.util.Map<String, Integer> tenantStats(String tenant) {
    Bucket b = quotas.getIfPresent(tenant);
    return b == null
        ? java.util.Map.of("maximumInFlight", 0, "rejections", 0)
        : java.util.Map.of("maximumInFlight", b.maximum.get(), "rejections", b.rejected.get());
  }

  public int maximumInFlight() {
    return maximum.get();
  }
}
