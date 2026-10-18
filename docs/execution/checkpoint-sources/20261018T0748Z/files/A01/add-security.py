from pathlib import Path
p=Path('projects/01-clearledger/ledger-verification/src/test/java/io/github/cheshmakzanoon/portfolio/clearledger/LedgerPostgresIT.java')
s=p.read_text();pos=s.rfind('}')
s=s[:pos]+'''
  @Test void protectedOidcAndTwoApiInstances() throws Exception {
    try (var issuer = new LocalIssuer()) {
      var decoder = ClearLedgerApplication.protectedDecoder(issuer.issuer);
      assertEquals(tenant.toString(), decoder.decode(issuer.token(tenant,"ledger.read")).getClaimAsString("tenant"));
      for (String invalid : List.of(
        issuer.token(tenant,"ledger.read",issuer.issuer,"wrong",java.time.Instant.now().plusSeconds(300),false),
        issuer.token(tenant,"ledger.read","http://wrong-issuer","clearledger",java.time.Instant.now().plusSeconds(300),false),
        issuer.token(tenant,"ledger.read",issuer.issuer,"clearledger",java.time.Instant.now().minusSeconds(300),false),
        issuer.token(tenant,"ledger.read",issuer.issuer,"clearledger",java.time.Instant.now().plusSeconds(300),true)))
        assertThrows(org.springframework.security.oauth2.jwt.JwtException.class, () -> decoder.decode(invalid));
      var app1 = new org.springframework.boot.SpringApplication(ClearLedgerApplication.class);
      var app2 = new org.springframework.boot.SpringApplication(ClearLedgerApplication.class);
      String[] args={"--server.port=0","--ledger.issuer="+issuer.issuer,"--ledger.url="+pg.getJdbcUrl(),"--spring.main.banner-mode=off"};
      try (var context1=app1.run(args);var context2=app2.run(args)) {
        int port1=((org.springframework.boot.web.server.context.WebServerApplicationContext)context1).getWebServer().getPort();
        int port2=((org.springframework.boot.web.server.context.WebServerApplicationContext)context2).getWebServer().getPort();
        var client=java.net.http.HttpClient.newHttpClient();
        String token=issuer.token(tenant,"ledger.write ledger.read ledger.reconcile ledger.admin");
        String json="{\\"period\\":\\""+period+"\\",\\"date\\":\\"2026-01-02\\",\\"currency\\":\\"CAD\\",\\"reference\\":\\"api\\",\\"lines\\":[{\\"account\\":\\""+a+"\\",\\"side\\":\\"DEBIT\\",\\"amount\\":\\"1.00\\"},{\\"account\\":\\""+b+"\\",\\"side\\":\\"CREDIT\\",\\"amount\\":\\"1.00\\"}]}";
        var response1=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port1+"/v1/journals")).header("Authorization","Bearer "+token).header("Content-Type","application/json").header("Idempotency-Key","api-shared").POST(java.net.http.HttpRequest.BodyPublishers.ofString(json)).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        var response2=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port2+"/v1/journals")).header("Authorization","Bearer "+token).header("Content-Type","application/json").header("Idempotency-Key","api-shared").POST(java.net.http.HttpRequest.BodyPublishers.ofString(json)).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response1.statusCode(),response1.body());assertEquals(response1.body(),response2.body());assertEquals(1,count("journal"));
        var cross=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port1+"/v1/accounts/"+a+"/statement")).header("Authorization","Bearer "+issuer.token(other,"ledger.read")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200,cross.statusCode());assertTrue(cross.body().contains("\\"rows\\":[]"));
        var denied=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port1+"/v1/requests/api-shared")).header("Authorization","Bearer "+issuer.token(tenant,"unrelated.scope")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());assertEquals(403,denied.statusCode());
        var injection=store.account(tenant,"CAD",Journal.Side.DEBIT,"'; DROP TABLE ledger.journal;--");assertNotNull(injection);assertEquals(1,count("journal"));
      }
    }
  }
''' +s[pos:];p.write_text(s)
