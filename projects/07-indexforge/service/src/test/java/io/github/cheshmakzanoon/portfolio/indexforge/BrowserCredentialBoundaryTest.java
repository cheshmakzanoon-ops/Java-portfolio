package io.github.cheshmakzanoon.portfolio.indexforge;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class BrowserCredentialBoundaryTest {
  @Test
  void rawCookieHeadersCannotBeLostThroughContainerCookieParsing() {
    for (String header : List.of("JSESSIONID=ambient", "preference=dark", "malformed-cookie")) {
      var request = new MockHttpServletRequest("POST", "/v1/ingestions");
      request.addHeader("Cookie", header);
      request.addHeader("Authorization", "Bearer explicit-token");
      assertNull(request.getCookies(), "test covers raw headers without parsed cookies");
      assertTrue(SecurityConfiguration.csrfForAmbientCredentials(request));
    }
  }

  @Test
  void unsafeParsedCookieRequestsRequireCsrfEvenWhenBearerAuthenticationIsPresent() {
    for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
      var request = new MockHttpServletRequest(method, "/v1/ingestions");
      request.setCookies(new Cookie("ambient", "cookie"));
      request.addHeader("Authorization", "Bearer explicit-token");
      assertTrue(SecurityConfiguration.csrfForAmbientCredentials(request));
    }
  }

  @Test
  void safeMethodsAndExplicitNonbrowserBearerRequestsRetainTheirExistingBehavior() {
    for (String method : List.of("GET", "HEAD", "OPTIONS", "TRACE")) {
      var request = new MockHttpServletRequest(method, "/v1/metrics");
      request.setCookies(new Cookie("ambient", "cookie"));
      request.addHeader("Cookie", "ambient=cookie");
      assertFalse(SecurityConfiguration.csrfForAmbientCredentials(request));
    }
    for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
      var request = new MockHttpServletRequest(method, "/v1/ingestions");
      request.addHeader("Authorization", "Bearer explicit-token");
      assertFalse(SecurityConfiguration.csrfForAmbientCredentials(request));
    }
  }

  @Test
  void browserBasicCredentialRequestsRequireCsrfButStandaloneCliRequestsDoNot() {
    var cli = new MockHttpServletRequest("POST", "/v1/ingestions");
    cli.addHeader("Authorization", "Basic dXNlcjpwYXNz");
    assertFalse(SecurityConfiguration.csrfForAmbientCredentials(cli));
    for (String marker : List.of("Origin", "Sec-Fetch-Site")) {
      var browser = new MockHttpServletRequest("POST", "/v1/ingestions");
      browser.addHeader("Authorization", "bAsIc dXNlcjpwYXNz");
      browser.addHeader(marker, "browser-context");
      assertTrue(SecurityConfiguration.csrfForAmbientCredentials(browser));
    }
    var empty = new MockHttpServletRequest("POST", "/v1/ingestions");
    empty.addHeader("Cookie", "   ");
    assertFalse(SecurityConfiguration.csrfForAmbientCredentials(empty));
  }
}
