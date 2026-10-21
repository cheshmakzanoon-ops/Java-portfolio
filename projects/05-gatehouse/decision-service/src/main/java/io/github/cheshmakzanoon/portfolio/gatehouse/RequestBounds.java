package io.github.cheshmakzanoon.portfolio.gatehouse;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestBounds extends OncePerRequestFilter {
  private final TenantAdmission admission = new TenantAdmission();

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
    TenantAdmission.Lease lease = admission.acquire(tenant);
    if (lease == null) {
      error(response, 429, "authorization capacity exceeded");
      return;
    }
    try (lease) {
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
    return admission.stats(tenant);
  }

  public int maximumInFlight() {
    return admission.maximumInFlight();
  }
}
