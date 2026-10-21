package io.github.cheshmakzanoon.portfolio.clearledger;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class BrowserCsrfTest {
  @Test
  void onlyAmbientBrowserCredentialsNeedCsrfTokensOnUnsafeRequests() {
    var bearer = new MockHttpServletRequest("POST", "/v1/test");
    bearer.addHeader("Authorization", "Bearer explicitly-supplied-token");
    assertFalse(ClearLedgerApplication.csrfForAmbientCredentials(bearer));

    var cookie = new MockHttpServletRequest("POST", "/v1/test");
    cookie.setCookies(new Cookie("session", "ambient-browser-state"));
    assertTrue(ClearLedgerApplication.csrfForAmbientCredentials(cookie));

    var browserBasic = new MockHttpServletRequest("DELETE", "/v1/test");
    browserBasic.addHeader("Authorization", "Basic dGVzdDp0ZXN0");
    browserBasic.addHeader("Origin", "https://malicious.example");
    assertTrue(ClearLedgerApplication.csrfForAmbientCredentials(browserBasic));

    var browserBasicFetch = new MockHttpServletRequest("POST", "/v1/test");
    browserBasicFetch.addHeader("Authorization", "bAsIc dGVzdDp0ZXN0");
    browserBasicFetch.addHeader("Sec-Fetch-Site", "cross-site");
    assertTrue(ClearLedgerApplication.csrfForAmbientCredentials(browserBasicFetch));

    var cliBasic = new MockHttpServletRequest("POST", "/v1/test");
    cliBasic.addHeader("Authorization", "Basic dGVzdDp0ZXN0");
    assertFalse(ClearLedgerApplication.csrfForAmbientCredentials(cliBasic));

    var safe = new MockHttpServletRequest("GET", "/v1/test");
    safe.setCookies(new Cookie("session", "ambient-browser-state"));
    assertFalse(ClearLedgerApplication.csrfForAmbientCredentials(safe));
  }
}
