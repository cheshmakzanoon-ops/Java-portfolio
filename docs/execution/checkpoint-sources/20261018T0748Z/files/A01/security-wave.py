from pathlib import Path
p=Path('projects/01-clearledger')
a=p/'ledger-api/src/main/java/io/github/cheshmakzanoon/portfolio/clearledger/ClearLedgerApplication.java'
s=a.read_text().replace('decoder.setJwtValidator(validators(issuer));','preserveIdentityTypes(decoder);\n      decoder.setJwtValidator(validators(issuer));').replace('d.setJwtValidator(validators(issuer));','preserveIdentityTypes(d);\n    d.setJwtValidator(validators(issuer));')
pos=s.index('  static OAuth2TokenValidator<Jwt> validators')
s=s[:pos]+'''  static void preserveIdentityTypes(NimbusJwtDecoder decoder) {
    var defaults = MappedJwtClaimSetConverter.withDefaults(Map.of());
    decoder.setClaimSetConverter(raw -> {
      var converted = new HashMap<String,Object>(defaults.convert(raw));
      for (String name : List.of("sub", "tenant")) {
        if(raw.containsKey(name)) converted.put(name, raw.get(name));
        else converted.remove(name);
      }
      return converted;
    });
  }

'''+s[pos:]
s=s.replace('UUID.fromString(jwt.getClaimAsString("tenant"));','''Object subject=jwt.getClaims().get("sub"), tenant=jwt.getClaims().get("tenant");
            if (!(subject instanceof String sub) || sub.isBlank() || sub.length()>255
                || !(tenant instanceof String id) || id.length()!=36
                || !UUID.fromString(id).toString().equalsIgnoreCase(id)) throw new IllegalArgumentException();''').replace('.requestMatchers("/v1/reports/**")','.requestMatchers("/v1/reports/replay")\n                    .hasAuthority("SCOPE_ledger.system")\n                    .requestMatchers("/v1/reports/**")')
a.write_text(s)
a=p/'ledger-api/src/main/java/io/github/cheshmakzanoon/portfolio/clearledger/LedgerController.java';s=a.read_text().replace('Map<String, Object> freshness() {\n    return projector.freshness();','Map<String, Object> freshness(@AuthenticationPrincipal Jwt p) {\n    return projector.freshness(tenant(p));');a.write_text(s)
a=p/'ledger-persistence/src/main/java/io/github/cheshmakzanoon/portfolio/clearledger/Projector.java';s=a.read_text().replace('pendingOn(Connection c, UUID generation)','pendingOn(Connection c, UUID generation, UUID tenant)').replace('"SELECT count(*) AS n FROM ledger.outbox_event e WHERE NOT EXISTS(SELECT FROM ledger.projection_checkpoint p WHERE p.generation=? AND p.event_id=e.id)",\n                generation)','"SELECT count(*) AS n FROM ledger.outbox_event e WHERE e.tenant_id=? AND NOT EXISTS(SELECT FROM ledger.projection_checkpoint p WHERE p.generation=? AND p.event_id=e.id)",\n                tenant, generation)').replace('freshness()','freshness(UUID tenant)').replace('pendingOn(c, generation)','pendingOn(c, generation, tenant)');a.write_text(s)
a=p/'ledger-verification/src/test/java/io/github/cheshmakzanoon/portfolio/clearledger/LocalIssuer.java';s=a.read_text();pos=s.index('  public void close()');s=s[:pos]+'''  String identityToken(Object subject, Object tenantValue) throws Exception {
    var claims=new HashMap<String,Object>();
    claims.put("iss",issuer);claims.put("aud",List.of("clearledger"));
    claims.put("exp",Instant.now().plusSeconds(300).getEpochSecond());
    claims.put("scope","ledger.read");
    if(subject!=null)claims.put("sub",subject);
    if(tenantValue!=null)claims.put("tenant",tenantValue);
    var jwt=new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("local-oidc").build(),new Payload(claims));
    jwt.sign(new RSASSASigner(key));return jwt.serialize();
  }

'''+s[pos:];a.write_text(s)
a=p/'ledger-verification/src/test/java/io/github/cheshmakzanoon/portfolio/clearledger/LedgerPostgresIT.java';s=a.read_text().replace('projector.freshness()','projector.freshness(tenant)');mark='      var app1 = new org.springframework.boot.SpringApplication(ClearLedgerApplication.class);';s=s.replace(mark,'''      for(Object bad : List.of("", "   ", 42, List.of("actor"))) {
        assertThrows(org.springframework.security.oauth2.jwt.JwtException.class, () -> decoder.decode(issuer.identityToken(bad,tenant.toString())));
        assertThrows(org.springframework.security.oauth2.jwt.JwtException.class, () -> decoder.decode(issuer.identityToken("actor",bad)));
      }
      assertThrows(org.springframework.security.oauth2.jwt.JwtException.class, () -> decoder.decode(issuer.identityToken(null,tenant.toString())));
      assertThrows(org.springframework.security.oauth2.jwt.JwtException.class, () -> decoder.decode(issuer.identityToken("actor",null)));
'''+mark)
mark='        // No Content-Length:';s=s.replace(mark,'''        var freshness=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port1+"/v1/reports/freshness"))
            .header("Authorization","Bearer "+issuer.token(other,"ledger.admin")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200,freshness.statusCode());assertTrue(freshness.body().contains("\\\"pendingEvents\\\":0"),freshness.body());
        var replayUri=java.net.URI.create("http://127.0.0.1:"+port1+"/v1/reports/replay");
        var tenantReplay=client.send(java.net.http.HttpRequest.newBuilder(replayUri).header("Authorization","Bearer "+token).POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(403,tenantReplay.statusCode());
        var systemReplay=client.send(java.net.http.HttpRequest.newBuilder(replayUri).header("Authorization","Bearer "+issuer.token(tenant,"ledger.system")).POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200,systemReplay.statusCode(),systemReplay.body());

'''+mark)
pos=s.index('  @Test\n  void balanceOverflowRejectsWholeJournalAndReceipt()');s=s[:pos]+'''  @Test
  void sqlNullMalformedShapesAndDeferredBackstopRejectAtomically() {
    String valid="[{\\\"account\\\":\\\""+a+"\\\",\\\"side\\\":\\\"DEBIT\\\",\\\"amount\\\":\\\"10\\\"},{\\\"account\\\":\\\""+b+"\\\",\\\"side\\\":\\\"CREDIT\\\",\\\"amount\\\":\\\"10\\\"}]";
    var invalid=new ArrayList<String>(Arrays.asList(null,"null","{}","[]","[{}]","["+String.join(",",Collections.nCopies(101,"{}"))+"]",
        valid.replace("10","1.5"),valid.replace("10","-1"),valid.replace("DEBIT","INVALID"),valid.replace("\\\"10\\\"","null")));
    for(int index=0;index<invalid.size();index++) {
      String payload=invalid.get(index),key="invalid-shape-"+index;
      assertThrows(LedgerStore.LedgerException.class,()->store.tx(c->LedgerStore.query(c,"SELECT ledger.post_journal(?,?,?,?,?,?,?,?,?::jsonb,?,?)",tenant,key,"0".repeat(64),UUID.randomUUID(),period,LocalDate.of(2026,1,2),"CAD","invalid",payload,null,"POST")),key);
    }
    assertThrows(LedgerStore.LedgerException.class,()->admin.tx(c->LedgerStore.execute(c,"INSERT INTO ledger.journal(tenant_id,id,period_id,currency,effective_date,reference) VALUES(?,?,?,'CAD',DATE '2026-01-02','backstop')",tenant,UUID.randomUUID(),period)));
    assertEquals(0,count("journal"));assertEquals(0,count("posting"));assertEquals(0,count("idempotency_receipt"));assertEquals(0,count("outbox_event"));
    assertTrue(store.balances(tenant).stream().allMatch(r->new BigInteger(r.get("debits")).signum()==0&&new BigInteger(r.get("credits")).signum()==0));
  }

'''+s[pos:];a.write_text(s)
# Demo performs actual HTTP disconnect at committed boundary.
a=p/'demo/demo.py';s=a.read_text().replace('import base64,','import concurrent.futures, secrets, base64,').replace('ledger.reconcile ledger.admin','ledger.reconcile ledger.admin ledger.system');s=s.replace("signal=project/'target/crash-after.ready';signal.unlink(missing_ok=True)","signal=project/'target/crash-after.ready';signal.unlink(missing_ok=True)\nport_file=project/'target/crash-after.port';port_file.unlink(missing_ok=True)\nnonce=secrets.token_urlsafe(32)\nexecutor=concurrent.futures.ThreadPoolExecutor(max_workers=1)").replace("'after',str(signal)]","'after',str(signal),str(port_file),nonce]")
s=s.replace(" deadline=time.monotonic()+30\n while not signal.exists()", " deadline=time.monotonic()+30\n while not port_file.exists() and process.poll() is None and time.monotonic()<deadline:time.sleep(.05)\n assert port_file.exists(),'HTTP writer did not start'\n def invoke_crash():\n  with urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:'+port_file.read_text().strip()+'/post',data=b'',headers={'Authorization':'Bearer '+nonce}),timeout=40) as response:return response.read()\n pending=executor.submit(invoke_crash)\n while not signal.exists()")
s=s.replace(" process.kill();process.wait(timeout=10)\n receipt=", " assert not pending.done(),'HTTP response completed before kill'\n process.kill();process.wait(timeout=10)\n try:pending.result(timeout=10);raise AssertionError('HTTP call unexpectedly succeeded')\n except (OSError, __import__('http.client',fromlist=['RemoteDisconnected']).RemoteDisconnected):pass\n receipt=").replace(" signal.unlink(missing_ok=True)\nreverse="," signal.unlink(missing_ok=True);port_file.unlink(missing_ok=True);executor.shutdown(wait=True)\nreverse=").replace('before an adapter response is emitted','before response headers of an actual HTTP request are emitted');a.write_text(s)
