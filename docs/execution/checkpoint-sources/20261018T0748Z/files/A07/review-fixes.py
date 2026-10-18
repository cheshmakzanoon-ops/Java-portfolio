from pathlib import Path
base=Path('projects/07-indexforge')
def path(module,name): return base/module/'src/main/java/io/github/cheshmakzanoon/portfolio/indexforge'/f'{name}.java'
p=path('ingest','SafeSourceReader');s=p.read_text().replace('    boolean selectedDirectory = Files.isDirectory(base);\n','').replace('(selectedDirectory ? base.relativize(p) : base.getFileName())','root.relativize(p)');p.write_text(s)
p=path('ingest','IngestionJob');s=p.read_text();at=s.index('  private SnapshotManifest load(');s=s[:at]+'''  /** Completion is an immutable, checksummed receipt, not a presence marker. */
  public synchronized boolean completed(String id) throws IOException {
    Registered job = get(id);
    Path receipt = root.resolve(id + ".done");
    if (!Files.exists(receipt)) return false;
    if (Files.size(receipt) > 512) throw new IOException("completion receipt quota");
    String[] lines = Files.readString(receipt).split("\\\\n", -1);
    if (lines.length != 5 || !lines[0].equals("1") || !lines[1].equals(id)
        || !lines[2].equals(job.manifestHash()) || !lines[3].matches("[a-f0-9-]{36}")
        || !Digests.sha256(String.join("\\n", Arrays.copyOf(lines, 4))).equals(lines[4]))
      throw new IOException("corrupt completion receipt");
    return true;
  }

  public synchronized void complete(String id, String manifestHash, String generation) throws IOException {
    Registered job = get(id);
    if (!job.manifestHash().equals(manifestHash) || !generation.matches("[a-f0-9-]{36}"))
      throw new IOException("committed job identity mismatch");
    if (completed(id)) return;
    String body = "1\\n" + id + "\\n" + manifestHash + "\\n" + generation;
    DurableFiles.atomicWrite(root.resolve(id + ".done"),
        (body + "\\n" + Digests.sha256(body)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

'''+s[at:];s=s.replace('import java.nio.file.*;','import java.nio.file.*;\nimport java.util.Arrays;');p.write_text(s)
p=path('index','GenerationBuilder');s=p.read_text();needle='''    String id = UUID.randomUUID().toString();''';s=s.replace(needle,'''    return build(manifest, previous, fault, cancelled, "");
  }

  public Built build(SnapshotManifest manifest, List<SourceDocument> previous, Fault fault,
      BooleanSupplier cancelled, String jobId) throws IOException {
    if (!jobId.isEmpty() && !jobId.matches("[a-f0-9]{64}"))
      throw new IllegalArgumentException("job ID");
    String id = UUID.randomUUID().toString();''');s=s.replace('''                  "generation",
                  id)''','''                  "generation",
                  id,
                  "job",
                  jobId)''');p.write_text(s)
p=path('index','GenerationPublisher');s=p.read_text();s=s.replace('''      if (!manifest.digest().equals(metadata.get("manifest"))''','''      if ((metadata.containsKey("job") && !metadata.get("job").isEmpty()
              && !metadata.get("job").matches("[a-f0-9]{64}"))
          || !manifest.digest().equals(metadata.get("manifest"))''');s=s.replace('''      primary = failure;
      throw failure;''','''      primary = failure;
      // atomic rename can succeed before its directory fsync reports failure. Reconcile the
      // actual pointer before allowing a subsequent writer to supersede an unknown outcome.
      if (!installed) {
        try {
          if (built.id().equals(readPointer(root.resolve("CURRENT")))) {
            handles.put(built.id(), next);
            current = built.id();
            installed = true;
          }
        } catch (IOException uncertain) {
          failure.addSuppressed(uncertain);
          // A readable old pointer is safe; an unreadable pointer must block all later writes.
          if (Files.exists(root.resolve("CURRENT"))) publicationUncertain = true;
        }
      }
      throw failure;''');s=s.replace('  private boolean closed;','  private boolean closed;\n  private boolean publicationUncertain;');s=s.replace('''    ensureOpen();
    Path destination''','''    ensureOpen();
    if (publicationUncertain) throw new IOException("publication outcome requires restart recovery");
    Path destination''');at=s.index('  public synchronized String currentId()');s=s[:at]+'''  public record CommittedJob(String id, String manifestHash, String generation) {}

  public synchronized CommittedJob committedJob() throws IOException {
    if (publicationUncertain) throw new IOException("publication outcome requires restart recovery");
    if (current == null) return null;
    Map<String, String> metadata = handles.get(current).reader.getIndexCommit().getUserData();
    String job = metadata.get("job");
    return job == null || job.isEmpty() ? null
        : new CommittedJob(job, metadata.get("manifest"), current);
  }

'''+s[at:];p.write_text(s)
p=path('query','SearchEngine');s=p.read_text();s=s.replace('''          publishers.put(name, new GenerationPublisher(tenant));''','''          GenerationPublisher publisher = new GenerationPublisher(tenant);
          publishers.put(name, publisher);
          reconcile(publisher);''');a=s.index('  private static String boundedExplanation(');b=s.index('  public long cacheHits()',a);s=s[:a]+'''  /** Export only matching authorized-document features, never raw collection statistics. */
  private static String safeExplanation(Explanation explanation, float score, String features) {
    Set<String> matches = new TreeSet<>();
    collectMatches(explanation, matches);
    String body = "Authorized match; score=" + score + "; matched features="
        + String.join(", ", matches) + features;
    return body.length() <= 4096 ? body : body.substring(0, 4070) + "\\n[explanation truncated]";
  }

  private static void collectMatches(Explanation explanation, Set<String> matches) {
    if (!explanation.isMatch() || matches.size() >= 64) return;
    String description = explanation.getDescription();
    if (description.startsWith("weight(")) {
      int end = description.indexOf(" in ");
      if (end > 7) matches.add(description.substring(7, Math.min(end, 263)));
    }
    for (Explanation detail : explanation.getDetails()) collectMatches(detail, matches);
  }

  private void reconcile(GenerationPublisher publisher) throws IOException {
    var committed = publisher.committedJob();
    if (committed != null)
      jobs.complete(committed.id(), committed.manifestHash(), committed.generation());
  }

'''+s[b:];s=s.replace('''    return id.matches("[a-f0-9]{64}") && Files.exists(root.resolve("jobs").resolve(id + ".done"));''','''    try {
      return id.matches("[a-f0-9]{64}") && jobs.completed(id);
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }''');s=s.replace('''      Path done = root.resolve("jobs").resolve(job.id() + ".done");
      if (Files.exists(done)) return job;
      GenerationPublisher publisher = generations(a.tenant());''','''      GenerationPublisher publisher = generations(a.tenant());
      reconcile(publisher);
      if (jobs.completed(job.id())) return job;''');s=s.replace('builder.build(m, publisher.sources(), fault, cancelled)','builder.build(m, publisher.sources(), fault, cancelled, job.id())');s=s.replace('''      DurableFiles.atomicWrite(done, built.id().getBytes(java.nio.charset.StandardCharsets.UTF_8));''','''      reconcile(publisher);''');s=s.replace('''                  boundedExplanation(
                      lease.searcher().explain(authorized, c.document()).toString()
                          + c.features())''','''                  safeExplanation(
                      lease.searcher().explain(authorized, c.document()), c.score(), c.features())''');p.write_text(s)
p=path('service','ErrorResponses');s=p.read_text().replace('@ExceptionHandler(IOException.class)','@ExceptionHandler({IOException.class, UncheckedIOException.class})');p.write_text(s)
