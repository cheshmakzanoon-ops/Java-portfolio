# Apply only after current certifying build exits.
from pathlib import Path
root=Path('/workspace/Java-portfolio/projects/07-indexforge');base=Path('src/main/java/io/github/cheshmakzanoon/portfolio/indexforge');tests=Path('src/test/java/io/github/cheshmakzanoon/portfolio/indexforge')
p=root/'service'/base/'SearchController.java';s=p.read_text().replace('    SafeSourceReader reader =\n        new SafeSourceReader(sources, SafeSourceReader.Limits.defaults(), engine.contentStore());','''    Path tenantRoot=sources.resolve(a.tenant()),repositoryRoot=tenantRoot.resolve(request.repository());
    if(Files.isSymbolicLink(tenantRoot)||Files.isSymbolicLink(repositoryRoot))throw new IOException("source binding symlinks prohibited");
    SafeSourceReader reader =
        new SafeSourceReader(repositoryRoot, SafeSourceReader.Limits.defaults(), engine.contentStore());''');p.write_text(s)
p=root/'service'/tests/'HttpSecurityIT.java';s=p.read_text().replace('sources/project','sources/demo/public/project');needle='      assertEquals(202, ingestion.statusCode(), ingestion.body());';s=s.replace(needle,needle+'''
      Files.createDirectories(dir.resolve("sources/demo/denied"));Files.writeString(dir.resolve("sources/demo/denied/Private.java"),"unreadableOtherRepositorySecret");
      String escape="{\\"repository\\":\\"public\\",\\"revision\\":\\"v2\\",\\"idempotencyKey\\":\\"escape\\",\\"path\\":\\"../denied\\"}";
      var escaped=request(client,base,"/v1/ingestions",token,escape);assertEquals(422,escaped.statusCode());assertFalse(escaped.body().contains("Private.java"));
      String denied="{\\"repository\\":\\"denied\\",\\"revision\\":\\"v2\\",\\"idempotencyKey\\":\\"denied\\",\\"path\\":\\".\\"}";assertEquals(403,request(client,base,"/v1/ingestions",token,denied).statusCode());
''');p.write_text(s)
p=root/'index'/tests/'GenerationLifecycleTest.java';s=p.read_text().replace('while (stream.incrementToken())\n        actual.add(', 'while (stream.incrementToken()) {if(actual.size()>64)fail("token stream failed to terminate");actual.add(').replace('offset.endOffset());\n      assertEquals(\n          List.of("foobar:1:0:6"','offset.endOffset());}\n      assertEquals(\n          List.of("foobar:1:0:6"');p.write_text(s)
# Add runtime prerequisite diagnostics without invoking or modifying any shared script.
p=root/'scripts/common.sh';s=p.read_text().replace('command -v java >/dev/null || { echo \'JDK 25 is required\' >&2; exit 1; }','command -v java >/dev/null || { echo \'JDK 25 is required\' >&2; exit 1; }\ncommand -v python3 >/dev/null || { echo \'Python3 is required for source fingerprint validation\' >&2; exit 1; }');p.write_text(s)
