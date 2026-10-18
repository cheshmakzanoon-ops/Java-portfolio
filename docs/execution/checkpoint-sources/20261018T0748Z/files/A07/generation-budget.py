from pathlib import Path
b=Path('projects/07-indexforge');pkg=Path('io/github/cheshmakzanoon/portfolio/indexforge')
p=b/'ingest/src/main/java'/pkg/'ContentAddressedStore.java';s=p.read_text();at=s.index('  public byte[] read(');s=s[:at]+'''  public long size(String digest) throws IOException {
    if(!digest.matches("[a-f0-9]{64}"))throw new IOException("invalid digest");
    long bytes=Files.size(root.resolve(digest));
    if(bytes>1_048_576)throw new IOException("content too large");
    return bytes;
  }

'''+s[at:];s=s.replace('    if (!digest.matches("[a-f0-9]{64}")) throw new IOException("invalid digest");\n    Path p = root.resolve(digest);\n    if (Files.size(p) > 1_048_576) throw new IOException("content too large");','    size(digest);\n    Path p = root.resolve(digest);');p.write_text(s)
p=b/'index/src/main/java'/pkg/'GenerationBuilder.java';s=p.read_text();s=s.replace('  private final Path root;','  public static final long MAX_SOURCE_BYTES=128L*1024*1024;\n  private final long sourceByteLimit;\n  private final Path root;');s=s.replace('  public GenerationBuilder(Path root, ContentAddressedStore cas) throws IOException {\n    this.root = root;','  public GenerationBuilder(Path root, ContentAddressedStore cas) throws IOException {\n    this(root,cas,MAX_SOURCE_BYTES);\n  }\n\n  public GenerationBuilder(Path root, ContentAddressedStore cas,long sourceByteLimit) throws IOException {\n    if(sourceByteLimit<1||sourceByteLimit>MAX_SOURCE_BYTES)throw new IllegalArgumentException("source byte limit");\n    this.sourceByteLimit=sourceByteLimit;\n    this.root = root;');needle='    for (var e : manifest.entries())\n      docs.put(';s=s.replace(needle,'''    long sourceBytes=0;
    for(SourceDocument existing:docs.values())sourceBytes+=existing.content().getBytes(StandardCharsets.UTF_8).length;
    for(var entry:manifest.entries()) {
      var replaced=docs.get(prefix+entry.path());
      if(replaced!=null)sourceBytes-=replaced.content().getBytes(StandardCharsets.UTF_8).length;
      sourceBytes+=cas.size(entry.sha256());
    }
    if(sourceBytes>sourceByteLimit)throw new IOException("complete generation source byte quota");
    for (var e : manifest.entries())
      docs.put(''');p.write_text(s)
p=b/'index/src/main/java'/pkg/'GenerationPublisher.java';s=p.read_text().replace('      var stored = reader.storedFields();','      long sourceBytes=0;\n      var stored = reader.storedFields();',1).replace('        sources.add(DocumentMapper.source(d));','        sourceBytes+=d.get("content").getBytes(StandardCharsets.UTF_8).length;\n        if(sourceBytes>GenerationBuilder.MAX_SOURCE_BYTES)throw new IOException("complete generation source byte quota");\n        sources.add(DocumentMapper.source(d));',1);s=s.replace('      throw e;\n    }\n  }\n\n  public synchronized void publish', '      if(e instanceof IOException io)throw io;\n      throw new IOException("invalid generation metadata",e);\n    }\n  }\n\n  public synchronized void publish');p.write_text(s)
p=b/'query/src/main/java'/pkg/'CandidateRetriever.java';s=p.read_text().replace('.storedFields().document(h.doc).get("key")','.storedFields().document(h.doc,Set.of("key")).get("key")');p.write_text(s)
p=b/'query/src/main/java'/pkg/'Ranker.java';s=p.read_text().replace('.storedFields().document(candidate.document())','.storedFields().document(candidate.document(),Set.of("declaration","path"))');p.write_text(s)
p=b/'query/src/main/java'/pkg/'SearchEngine.java';s=p.read_text().replace('.storedFields().document(c.document());\n          for (String symbol', '.storedFields().document(c.document(),Set.of("symbol"));\n          for (String symbol');p.write_text(s)
