package io.github.cheshmakzanoon.portfolio.indexforge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.*;
import org.apache.lucene.store.*;

public final class GenerationBuilder {
  public enum Boundary {
    BUILD_START,
    DOCUMENT,
    BEFORE_COMMIT,
    AFTER_COMMIT,
    AFTER_WARM,
    AFTER_RENAME,
    BEFORE_POINTER,
    AFTER_POINTER,
    RETIRE
  }

  @FunctionalInterface
  public interface Fault {
    void at(Boundary boundary) throws IOException;

    static Fault none() {
      return boundary -> {};
    }
  }

  public record Built(String id, Path path, String manifestHash, int count) {}

  public static final long MAX_SOURCE_BYTES = 128L * 1024 * 1024;
  private final long sourceByteLimit;
  private final Path root;
  private final ContentAddressedStore cas;
  private final CheckpointStore checkpoints;
  private final boolean structural;

  public GenerationBuilder(Path root, ContentAddressedStore cas) throws IOException {
    this(root, cas, MAX_SOURCE_BYTES);
  }

  public GenerationBuilder(Path root, ContentAddressedStore cas, long sourceByteLimit)
      throws IOException {
    this(root,cas,sourceByteLimit,false);
  }

  public GenerationBuilder(Path root,ContentAddressedStore cas,long sourceByteLimit,boolean structural)
      throws IOException {
    if (sourceByteLimit < 1 || sourceByteLimit > MAX_SOURCE_BYTES)
      throw new IllegalArgumentException("source byte limit");
    this.sourceByteLimit = sourceByteLimit;
    this.structural=structural;
    this.root = root;
    this.cas = cas;
    this.checkpoints = new CheckpointStore(root.resolve("checkpoints"));
    Files.createDirectories(root.resolve("staging"));
  }

  public Built build(
      SnapshotManifest manifest,
      List<SourceDocument> previous,
      Fault fault,
      BooleanSupplier cancelled)
      throws IOException {
    return build(manifest, previous, fault, cancelled, "");
  }

  public Built build(
      SnapshotManifest manifest,
      List<SourceDocument> previous,
      Fault fault,
      BooleanSupplier cancelled,
      String jobId)
      throws IOException {
    if (!jobId.isEmpty() && !jobId.matches("[a-f0-9]{64}"))
      throw new IllegalArgumentException("job ID");
    String id = UUID.randomUUID().toString();
    Path dir = root.resolve("staging").resolve(id);
    Files.createDirectory(dir);
    fault.at(Boundary.BUILD_START);
    Map<String, SourceDocument> docs = new TreeMap<>();
    for (SourceDocument old : previous) docs.put(old.key(), old);
    String prefix = manifest.tenantId() + "/" + manifest.repositoryId() + "/";
    Set<String> uploaded = new HashSet<>();
    for (var e : manifest.entries()) uploaded.add(prefix + e.path());
    if (manifest.complete())
      for (String key : docs.keySet())
        if (key.startsWith(prefix)
            && !uploaded.contains(key)
            && !manifest.deletions().contains(key.substring(prefix.length())))
          throw new IOException("complete snapshot requires explicit deletions");
    for (String deleted : manifest.deletions()) docs.remove(prefix + deleted);
    long sourceBytes = 0;
    for (SourceDocument existing : docs.values())
      sourceBytes += existing.content().getBytes(StandardCharsets.UTF_8).length;
    for (var entry : manifest.entries()) {
      var replaced = docs.get(prefix + entry.path());
      if (replaced != null)
        sourceBytes -= replaced.content().getBytes(StandardCharsets.UTF_8).length;
      sourceBytes += cas.size(entry.sha256());
    }
    if (sourceBytes > sourceByteLimit)
      throw new IOException("complete generation source byte quota");
    for (var e : manifest.entries())
      docs.put(
          prefix + e.path(),
          new SourceDocument(
              manifest.tenantId(),
              manifest.repositoryId(),
              manifest.revision(),
              e,
              cas.text(e.sha256())));
    if (docs.size() > 100_000) throw new IOException("tenant document quota");
    String hash = manifest.digest();
    checkpoints.read(hash); // Validated hints never override the authoritative manifest/commit.
    byte[] snapshot = fullSnapshot(docs.values());
    DurableFiles.atomicWrite(dir.resolve("snapshot.bin"), snapshot);
    try (SourceParserClient parser=structural?new SourceParserClient(root.resolve("source-workers")):null;
        Directory fs = FSDirectory.open(dir);
        Analyzer identifiers = new CodeIdentifierAnalyzer();
        Analyzer prose = new SourceProseAnalyzer();
        Analyzer analyzer =
            new org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper(
                identifiers, Map.of("prose", prose, "code_features", prose));
        IndexWriter writer =
            new IndexWriter(
                fs,
                new IndexWriterConfig(analyzer).setOpenMode(IndexWriterConfig.OpenMode.CREATE))) {
      int n = 0;
      SourceRepositoryFacts.Builder graph=null;String graphScope="";
      for (SourceDocument s : docs.values()) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
          throw new IOException("cancelled");
        SourceFacts.Unit facts=null;
        if(parser!=null) {
          String scope=s.tenant()+"/"+s.repository();
          if(!scope.equals(graphScope)) {
            if(graph!=null)SourceRepositoryFacts.write(dir,graph.finish());
            graph=new SourceRepositoryFacts.Builder(s.tenant(),s.repository());graphScope=scope;
          }
          facts=s.entry().language().equals("java")?parser.parse(s.content()):
              new SourceFacts.Unit(SourceFacts.Status.OBSERVED,"",List.of(),List.of(),List.of());
          facts=SourceAnchors.checked(facts);
          graph.add(s,facts);
        }
        writer.addDocument(DocumentMapper.map(s,facts));
        fault.at(Boundary.DOCUMENT);
        if (++n % 1000 == 0) checkpoints.save(hash, n);
      }
      if(graph!=null)SourceRepositoryFacts.write(dir,graph.finish());
      Map<String,String> metadata=new HashMap<>(Map.of(
          "manifest",hash,"snapshot",Digests.sha256(snapshot),"schema",Integer.toString(SnapshotManifest.SCHEMA),
          "count",Integer.toString(docs.size()),"generation",id,"job",jobId));
      if(parser!=null) {
        metadata.put("source_schema",SourceFacts.SCHEMA);
        metadata.put("source_graph",SourceSymbolGraph.SCHEMA);
        metadata.put("source_worker",parser.identity());
        metadata.put("source_jdk",System.getProperty("java.runtime.version"));
      }
      writer.setLiveCommitData(
          metadata.entrySet());
      fault.at(Boundary.BEFORE_COMMIT);
      writer.commit();
      fault.at(Boundary.AFTER_COMMIT);
    }
    DurableFiles.atomicWrite(dir.resolve("manifest.bin"), manifest.bytes());
    try (Directory fs = FSDirectory.open(dir);
        DirectoryReader r = DirectoryReader.open(fs)) {
      if (r.numDocs() != docs.size()) throw new IOException("count mismatch");
      new org.apache.lucene.search.IndexSearcher(r)
          .count(new org.apache.lucene.search.MatchAllDocsQuery());
    }
    fault.at(Boundary.AFTER_WARM);
    DurableFiles.atomicWrite(
        dir.resolve("generation.sha256"), checksum(dir).getBytes(StandardCharsets.UTF_8));
    return new Built(id, dir, hash, docs.size());
  }

  public static byte[] fullSnapshot(Collection<SourceDocument> docs) throws IOException {
    long estimate = 16;
    for (SourceDocument document : docs) {
      estimate +=
          160
              + 3L
                  * (document.key().length()
                      + document.revision().length()
                      + document.entry().language().length()
                      + document.entry().kind().length()
                      + document.entry().coordinates().length()
                      + document.entry().version().length());
      for (String symbol : document.entry().symbols()) estimate += 2 + 3L * symbol.length();
    }
    if (estimate > 64L * 1024 * 1024)
      throw new IOException("complete snapshot metadata byte quota");
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      out.writeInt(SnapshotManifest.SCHEMA);
      out.writeInt(docs.size());
      for (SourceDocument d :
          docs.stream().sorted(Comparator.comparing(SourceDocument::key)).toList()) {
        out.writeUTF(d.key());
        out.writeUTF(d.revision());
        out.writeUTF(d.entry().sha256());
        out.writeUTF(d.entry().language());
        out.writeUTF(d.entry().kind());
        out.writeUTF(d.entry().coordinates());
        out.writeUTF(d.entry().version());
        out.writeInt(d.entry().symbols().size());
        for (String symbol : d.entry().symbols()) out.writeUTF(symbol);
      }
    }
    return bytes.toByteArray();
  }

  public static String checksum(Path dir) throws IOException {
    StringBuilder b = new StringBuilder();
    try (var files = Files.list(dir)) {
      for (Path p :
          files
              .filter(Files::isRegularFile)
              .filter(
                  p ->
                      !p.getFileName().toString().equals("generation.sha256")
                          && !p.getFileName().toString().equals("write.lock"))
              .sorted()
              .toList())
        b.append(p.getFileName()).append(':').append(Digests.sha256(p)).append('\n');
    }
    return Digests.sha256(b.toString());
  }
}
