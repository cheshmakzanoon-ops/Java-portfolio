package io.github.cheshmakzanoon.portfolio.gatehouse;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class BrowserCsrfTest {
  @Test
  void rejectsAmbientCredentialsOnWritesWithoutBlockingExplicitBearerTokens() {
    var anonymous = new MockHttpServletRequest("POST", "/v1/decisions");
    assertThat(DecisionApplication.csrfForAmbientCredentials(anonymous)).isFalse();

    var bearer = new MockHttpServletRequest("POST", "/v1/decisions");
    bearer.addHeader("Authorization", "Bearer signed-token");
    assertThat(DecisionApplication.csrfForAmbientCredentials(bearer)).isFalse();

    var withCookie = new MockHttpServletRequest("POST", "/v1/decisions");
    withCookie.setCookies(new Cookie("JSESSIONID", "ambient"));
    assertThat(DecisionApplication.csrfForAmbientCredentials(withCookie)).isTrue();

    var browserBasic = new MockHttpServletRequest("POST", "/v1/decisions");
    browserBasic.addHeader("Authorization", "Basic YTpi");
    browserBasic.addHeader("Origin", "https://attacker.invalid");
    assertThat(DecisionApplication.csrfForAmbientCredentials(browserBasic)).isTrue();

    var cliBasic = new MockHttpServletRequest("POST", "/v1/decisions");
    cliBasic.addHeader("Authorization", "Basic YTpi");
    assertThat(DecisionApplication.csrfForAmbientCredentials(cliBasic)).isFalse();

    var safeRequest = new MockHttpServletRequest("GET", "/v1/decisions");
    safeRequest.setCookies(new Cookie("JSESSIONID", "ambient"));
    assertThat(DecisionApplication.csrfForAmbientCredentials(safeRequest)).isFalse();
  }

  @Test
  void browserManagedBasicCredentialsAlsoRequireCsrfWhenFetchMetadataIsPresent() {
    var browser = new MockHttpServletRequest("DELETE", "/v1/decisions");
    browser.addHeader("Authorization", "bAsIc YTpi");
    browser.addHeader("Sec-Fetch-Site", "cross-site");
    assertThat(DecisionApplication.csrfForAmbientCredentials(browser)).isTrue();
  }
}
