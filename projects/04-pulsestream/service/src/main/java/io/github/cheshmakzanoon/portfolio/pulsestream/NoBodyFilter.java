package io.github.cheshmakzanoon.portfolio.pulsestream;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Query service accepts no HTTP request bodies, including a chunked GET body. */
@Component
@Order(-1000)
public final class NoBodyFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null) {
      response.setStatus(413);
      response.setContentType("application/json");
      response.getWriter().write("{\"code\":\"REQUEST_BODY_NOT_ALLOWED\"}");
      return;
    }
    chain.doFilter(request, response);
  }
}
