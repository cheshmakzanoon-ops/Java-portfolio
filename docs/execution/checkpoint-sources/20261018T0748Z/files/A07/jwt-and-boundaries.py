from pathlib import Path
root=Path('/workspace/Java-portfolio/projects/07-indexforge');base=Path('src/main/java/io/github/cheshmakzanoon/portfolio/indexforge');tests=Path('src/test/java/io/github/cheshmakzanoon/portfolio/indexforge')
p=root/'service'/base/'SecurityConfiguration.java';s=p.read_text().replace('    return decoder;','''    return token -> {
      if(token==null||token.length()>16384)throw new BadJwtException("token bound");
      Jwt jwt=decoder.decode(token); // Cryptography, issuer and timestamps precede raw-type inspection.
      try {
        Map<String,Object> raw=com.nimbusds.jose.JWSObject.parse(token).getPayload().toJSONObject();
        Object sub=raw.get("sub"),tenant=raw.get("tenant"),aud=raw.get("aud"),expiration=raw.get("exp"),scope=raw.get("scope");
        boolean audienceType=aud instanceof String || aud instanceof List<?> list&&!list.isEmpty()&&list.stream().allMatch(value->value instanceof String text&&!text.isBlank());
        if(!(sub instanceof String subject)||subject.isBlank()||subject.length()>256||!(tenant instanceof String t)||!t.matches("[a-zA-Z0-9_-]{1,64}")||!audienceType||!(expiration instanceof Number)||!(scope instanceof String))throw new BadJwtException("signed claim type");
        return jwt;
      }catch(java.text.ParseException|RuntimeException invalid){throw new BadJwtException("signed claims invalid",invalid);}
    };''',1);p.write_text(s)
p=root/'service'/tests/'HttpSecurityIT.java';s=p.read_text();at=s.index('      JwtDecoder decoder = context.getBean(JwtDecoder.class);');s=s[:at]+'''      String fixed=",\\"aud\\":[\\"indexforge\\"],\\"exp\\":"+(Instant.now().getEpochSecond()+300)+",\\"tenant\\":\\"demo\\",\\"scope\\":\\"search ingest\\"}";
      for(String badSubject:List.of("42","[]","null","\\"\\"","\\"   \\"")) {
        String signed=issuer.sign("{\\"iss\\":\\""+issuer.issuer()+"\\",\\"sub\\":"+badSubject+fixed);
        assertEquals(401,request(client,base,"/v1/search",signed,"{\\"text\\":\\"find\\",\\"pageSize\\":10}").statusCode(),"original signed subject type");
      }
'''+s[at:];p.write_text(s)
p=root/'query'/tests/'SearchSecurityTest.java';s=p.read_text();needle='    assertEquals(new SearchCursor.Position(gen, 20), codec.decode(c, a, "digest"));';s=s.replace(needle,needle+'''
    assertEquals(new SearchCursor.Position(gen,0),codec.decode(codec.encode(a,"digest",gen,0),a,"digest"));
    assertEquals(new SearchCursor.Position(gen,500),codec.decode(codec.encode(a,"digest",gen,500),a,"digest"));
    var exactExpiry=new SearchCursor(key,Clock.offset(clock,Duration.ofSeconds(300)));assertThrows(IllegalArgumentException.class,()->exactExpiry.decode(c,a,"digest"));
    for(int length=1;length<1600;length++){var principal=new AuthorizationFilter.Access("p".repeat(length),"t",Set.of("r"),1,false);String boundary=codec.encode(principal,"digest",gen,0);if(boundary.length()==2048){assertEquals(0,codec.decode(boundary,principal,"digest").offset());break;}}
''');p.write_text(s)
p=root/'cli'/base/'IndexForgeCli.java';s=p.read_text().replace('case "reindex"->{','case "resume"->{engine.resume(access,args[4],GenerationBuilder.Fault.none(),()->false);System.out.println("Resumed job="+args[4]);}case "reindex"->{');p.write_text(s)
p=root/'index'/base/'GenerationPublisher.java';s=p.read_text().replace('Files.readString(\n                root.resolve("generations").resolve(id).resolve("generation.sha256"))','checksumText(root.resolve("generations").resolve(id))');p.write_text(s)
