from pathlib import Path
p=Path('projects/01-clearledger');base='io/github/cheshmakzanoon/portfolio/clearledger'
f=p/'ledger-api/src/main/java'/base/'RequestBoundsFilter.java';s=f.read_text();needle='    chain.doFilter(\n        new HttpServletRequestWrapper';idx=s.index(needle);s=s[:idx]+'''    if(request.getContentType()!=null && request.getContentType().startsWith("application/json")) {
      int depth=0;boolean quoted=false,escaped=false;
      for(byte raw:bytes){char ch=(char)(raw&255);if(quoted){if(escaped)escaped=false;else if(ch=='\\\\')escaped=true;else if(ch=='"')quoted=false;}else if(ch=='"')quoted=true;else if(ch=='{'||ch=='['){if(++depth>64){response.setStatus(422);response.setContentType("application/json");response.getWriter().write("{\\"code\\":\\"NESTING_BOUND\\"}");return;}}else if(ch=='}'||ch==']')depth--;}
    }
''' +s[idx:];f.write_text(s)
f=p/'ledger-verification/src/test/java'/base/'LedgerPostgresIT.java';s=f.read_text();needle='        var injection =';idx=s.index(needle);s=s[:idx]+'''
        // No Content-Length: BodyPublishers.ofInputStream emits a real chunked body.
        for(String contentType:List.of("text/csv","application/json")){
          byte[] oversized=new byte[1048577];Arrays.fill(oversized,(byte)'a');
          var huge=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port1+(contentType.equals("text/csv")?"/v1/statements":"/v1/journals"))).header("Authorization","Bearer "+token).header("Content-Type",contentType).POST(java.net.http.HttpRequest.BodyPublishers.ofInputStream(()->new java.io.ByteArrayInputStream(oversized))).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
          assertEquals(413,huge.statusCode(),huge.body());assertTrue(huge.body().contains("REQUEST_BYTE_BOUND"));
        }
        String nested="[".repeat(65)+"0"+"]".repeat(65);
        var deep=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port1+"/v1/journals")).header("Authorization","Bearer "+token).header("Content-Type","application/json").header("Idempotency-Key","nested").POST(java.net.http.HttpRequest.BodyPublishers.ofString(nested)).build(),java.net.http.HttpResponse.BodyHandlers.ofString());assertEquals(422,deep.statusCode());assertTrue(deep.body().contains("NESTING_BOUND"));
        String hugeAmount=json.replace("1.00","1".repeat(100000));
        var amountRejected=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port1+"/v1/journals")).header("Authorization","Bearer "+token).header("Content-Type","application/json").header("Idempotency-Key","huge-amount").POST(java.net.http.HttpRequest.BodyPublishers.ofString(hugeAmount)).build(),java.net.http.HttpResponse.BodyHandlers.ofString());assertEquals(422,amountRejected.statusCode());
''' +s[idx:];# add dev startup refusal before final method end via new standalone test
idx=s.rfind('}');s=s[:idx]+'''
 @Test void devProfileRefusesNonLoopbackBinding(){var app=new org.springframework.boot.SpringApplication(ClearLedgerApplication.class);assertThrows(org.springframework.beans.factory.BeanCreationException.class,()->app.run("--server.port=0","--server.address=0.0.0.0","--spring.profiles.active=dev","--ledger.url="+pg.getJdbcUrl(),"--spring.main.banner-mode=off"));}
 @Test void statementSnapshotExcludesLaterCommittedJournal()throws Exception {
   String snapshot;
   try(var delayed=DriverManager.getConnection(pg.getJdbcUrl(),"clearledger_runtime","local-only-clearledger")){
     delayed.setAutoCommit(false);LedgerStore.postOn(delayed,tenant,"late-statement",journal(5,"late"),null,UUID.randomUUID());snapshot=store.visibilitySnapshot();delayed.commit();
   }
   var boundary=Instant.now().plusSeconds(1);
   assertTrue(store.statement(tenant,a,LocalDate.of(1900,1,1),new UUID(0,0),0,boundary,snapshot,100).isEmpty());
   assertEquals(1,store.statement(tenant,a,LocalDate.of(1900,1,1),new UUID(0,0),0,boundary,store.visibilitySnapshot(),100).size());
 }
''' +s[idx:];f.write_text(s)
