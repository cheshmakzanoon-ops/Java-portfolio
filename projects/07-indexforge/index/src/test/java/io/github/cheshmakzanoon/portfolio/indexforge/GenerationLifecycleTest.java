package io.github.cheshmakzanoon.portfolio.indexforge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.apache.lucene.search.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class GenerationLifecycleTest {
  @TempDir Path root;

  SnapshotManifest manifest(ContentAddressedStore cas, String revision, int count)
      throws IOException {
    List<SnapshotManifest.Entry> entries = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      String text = "public void method" + i + "() {} revision " + revision;
      entries.add(
          new SnapshotManifest.Entry(
              "src/A" + i + ".java",
              cas.put(text.getBytes(StandardCharsets.UTF_8)),
              "java",
              "source",
              List.of("method" + i),
              "g:a",
              "1"));
    }
    return new SnapshotManifest("t", "r", revision, false, entries, Set.of());
  }

  @Test
  void aggregateGenerationByteQuotaRejectsPartialGrowthButAllowsValidReplacement()
      throws Exception {
    var cas = new ContentAddressedStore(root.resolve("cas"));
    Path index = root.resolve("index");
    var builder = new GenerationBuilder(index, cas, 200);
    assertThrows(IllegalArgumentException.class, () -> new GenerationBuilder(index, cas, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new GenerationBuilder(index, cas, GenerationBuilder.MAX_SOURCE_BYTES + 1));
    try (var publisher = new GenerationPublisher(index)) {
      var first =
          builder.build(
              manifest(cas, "old", 2), List.of(), GenerationBuilder.Fault.none(), () -> false);
      publisher.publish(first, GenerationBuilder.Fault.none());
      var oversized =
          new SnapshotManifest.Entry(
              "src/B.java",
              cas.put("x".repeat(190).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
              "java",
              "source",
              List.of(),
              "",
              "");
      var addition = new SnapshotManifest("t", "r", "growth", false, List.of(oversized), Set.of());
      IOException rejected =
          assertThrows(
              IOException.class,
              () ->
                  builder.build(
                      addition, publisher.sources(), GenerationBuilder.Fault.none(), () -> false));
      assertTrue(rejected.getMessage().contains("source byte quota"));
      assertEquals(first.id(), publisher.currentId());
      try (var current = publisher.acquire()) {
        assertEquals(2, current.reader().numDocs());
      }
      var unicode =
          new SnapshotManifest.Entry(
              "src/Unicode.java",
              cas.put("α".repeat(100).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
              "java",
              "source",
              List.of(),
              "",
              "");
      var unicodeGrowth =
          new SnapshotManifest("t", "r", "unicode", false, List.of(unicode), Set.of());
      assertThrows(
          IOException.class,
          () ->
              builder.build(
                  unicodeGrowth, publisher.sources(), GenerationBuilder.Fault.none(), () -> false));
      String smaller = "class A { String value=\"" + "α".repeat(20) + "\"; }";
      String hash = cas.put(smaller.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      var replacement =
          new SnapshotManifest(
              "t",
              "r",
              "replacement",
              true,
              List.of(
                  new SnapshotManifest.Entry(
                      "src/A0.java", hash, "java", "source", List.of("A"), "", ""),
                  new SnapshotManifest.Entry(
                      "src/A1.java", hash, "java", "source", List.of("A"), "", "")),
              Set.of());
      var built =
          builder.build(
              replacement, publisher.sources(), GenerationBuilder.Fault.none(), () -> false);
      publisher.publish(built, GenerationBuilder.Fault.none());
      assertEquals(2, publisher.sources().size());
      assertTrue(publisher.sources().stream().allMatch(source -> source.content().equals(smaller)));
      assertNotEquals(first.id(), publisher.currentId());
    }
  }

  @Test
  void postPointerFaultsRetireReadersAndPruneOnlyAfterLastLease() throws Exception {
    for (var faultBoundary :
        List.of(GenerationBuilder.Boundary.AFTER_POINTER, GenerationBuilder.Boundary.RETIRE)) {
      Path run = root.resolve(faultBoundary.name());
      var cas = new ContentAddressedStore(run.resolve("cas"));
      Path index = run.resolve("index");
      var builder = new GenerationBuilder(index, cas);
      try (var publisher = new GenerationPublisher(index)) {
        var first =
            builder.build(
                manifest(cas, "old", 2), List.of(), GenerationBuilder.Fault.none(), () -> false);
        publisher.publish(first, GenerationBuilder.Fault.none());
        var held = publisher.acquire();
        var reader = held.reader();
        var second =
            builder.build(
                manifest(cas, "new", 3),
                publisher.sources(),
                GenerationBuilder.Fault.none(),
                () -> false);
        var failure =
            assertThrows(
                IOException.class,
                () ->
                    publisher.publish(
                        second,
                        atBoundary -> {
                          if (atBoundary == faultBoundary)
                            throw new IOException("post-pointer crash");
                        }));
        assertEquals("post-pointer crash", failure.getMessage());
        assertEquals(second.id(), publisher.currentId());
        assertThrows(
            GenerationPublisher.RetiredGeneration.class, () -> publisher.acquire(first.id()));
        assertEquals(2, held.reader().numDocs());
        var third =
            builder.build(
                manifest(cas, "third", 4),
                publisher.sources(),
                GenerationBuilder.Fault.none(),
                () -> false);
        publisher.publish(third, GenerationBuilder.Fault.none());
        assertTrue(Files.isDirectory(index.resolve("generations").resolve(first.id())));
        held.close();
        assertEquals(0, reader.getRefCount());
        assertFalse(Files.exists(index.resolve("generations").resolve(first.id())));
        assertTrue(Files.isDirectory(index.resolve("generations").resolve(second.id())));
        assertTrue(Files.isDirectory(index.resolve("generations").resolve(third.id())));
        try (var current = publisher.acquire()) {
          assertEquals(4, current.reader().numDocs());
        }
      }
      try (var reopened = new GenerationPublisher(index);
          var current = reopened.acquire()) {
        assertEquals(4, current.reader().numDocs());
      }
    }
  }

  @Test
  void retirementFailureWithNoOutstandingLeaseStillClosesOldReader() throws Exception {
    var cas = new ContentAddressedStore(root.resolve("cas"));
    Path index = root.resolve("index");
    var builder = new GenerationBuilder(index, cas);
    try (var publisher = new GenerationPublisher(index)) {
      var first =
          builder.build(
              manifest(cas, "first", 2), List.of(), GenerationBuilder.Fault.none(), () -> false);
      publisher.publish(first, GenerationBuilder.Fault.none());
      org.apache.lucene.index.DirectoryReader reader;
      try (var lease = publisher.acquire()) {
        reader = lease.reader();
      }
      assertEquals(1, reader.getRefCount());
      var second =
          builder.build(
              manifest(cas, "second", 3),
              publisher.sources(),
              GenerationBuilder.Fault.none(),
              () -> false);
      assertThrows(
          IOException.class,
          () ->
              publisher.publish(
                  second,
                  atBoundary -> {
                    if (atBoundary == GenerationBuilder.Boundary.RETIRE)
                      throw new IOException("retirement crash");
                  }));
      assertEquals(0, reader.getRefCount());
      try (var current = publisher.acquire()) {
        assertEquals(second.id(), current.id());
        assertEquals(3, current.reader().numDocs());
      }
    }
  }

  @Test
  void identifierStreamHasExactSynonymPositionsAndSourceOffsets() throws Exception {
    try (var analyzer = new CodeIdentifierAnalyzer();
        var stream = analyzer.tokenStream("content", "fooBar baz")) {
      var term =
          stream.addAttribute(org.apache.lucene.analysis.tokenattributes.CharTermAttribute.class);
      var offset =
          stream.addAttribute(org.apache.lucene.analysis.tokenattributes.OffsetAttribute.class);
      var position =
          stream.addAttribute(
              org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute.class);
      stream.reset();
      List<String> actual = new ArrayList<>();
      while (stream.incrementToken()) {
        if (actual.size() > 64) fail("token stream failed to terminate");
        actual.add(
            term.toString()
                + ":"
                + position.getPositionIncrement()
                + ":"
                + offset.startOffset()
                + ":"
                + offset.endOffset());
      }
      assertEquals(List.of("foobar:1:0:6", "foo:0:0:3", "bar:0:3:6", "baz:1:7:10"), actual);
      stream.end();
      assertEquals(10, offset.startOffset());
      assertEquals(10, offset.endOffset());
      assertEquals(0, position.getPositionIncrement());
    }
  }

  @Test
  void committedGenerationsHaveAtomicSnapshotAndLeaseLifetime() throws Exception {
    var cas = new ContentAddressedStore(root.resolve("cas"));
    Path index = root.resolve("index");
    var builder = new GenerationBuilder(index, cas);
    try (var publisher = new GenerationPublisher(index)) {
      var one =
          builder.build(
              manifest(cas, "one", 4), List.of(), GenerationBuilder.Fault.none(), () -> false);
      publisher.publish(one, GenerationBuilder.Fault.none());
      GenerationLease old = publisher.acquire();
      assertEquals(4, old.searcher().count(new MatchAllDocsQuery()));
      var two =
          builder.build(
              manifest(cas, "two", 6),
              publisher.sources(),
              GenerationBuilder.Fault.none(),
              () -> false);
      publisher.publish(two, GenerationBuilder.Fault.none());
      assertEquals(one.id(), old.id());
      assertEquals(4, old.reader().numDocs());
      try (var lease = publisher.acquire()) {
        assertEquals(two.id(), lease.id());
        assertEquals(6, lease.reader().numDocs());
      }
      assertThrows(GenerationPublisher.RetiredGeneration.class, () -> publisher.acquire(one.id()));
      old.close();
      old.close();
      assertThrows(IllegalStateException.class, old::searcher);
    }
    try (var recovered = new GenerationPublisher(index);
        var lease = recovered.acquire()) {
      assertEquals(6, lease.reader().numDocs());
      assertEquals("two", recovered.sources().getFirst().revision());
    }
  }

  @Test
  void explicitDeletionsAndPartialUploadsRespectSnapshotContract() throws Exception {
    var cas = new ContentAddressedStore(root.resolve("cas"));
    Path index = root.resolve("index");
    var builder = new GenerationBuilder(index, cas);
    try (var publisher = new GenerationPublisher(index)) {
      var m = manifest(cas, "one", 3);
      publisher.publish(
          builder.build(m, List.of(), GenerationBuilder.Fault.none(), () -> false),
          GenerationBuilder.Fault.none());
      var partial =
          new SnapshotManifest(
              "t", "r", "partial", false, List.of(m.entries().getFirst()), Set.of());
      publisher.publish(
          builder.build(partial, publisher.sources(), GenerationBuilder.Fault.none(), () -> false),
          GenerationBuilder.Fault.none());
      assertEquals(3, publisher.sources().size());
      var invalid =
          new SnapshotManifest("t", "r", "full", true, List.of(m.entries().getFirst()), Set.of());
      assertThrows(
          IOException.class,
          () ->
              builder.build(
                  invalid, publisher.sources(), GenerationBuilder.Fault.none(), () -> false));
      var full =
          new SnapshotManifest(
              "t",
              "r",
              "full",
              true,
              List.of(m.entries().getFirst()),
              Set.of("src/A1.java", "src/A2.java"));
      publisher.publish(
          builder.build(full, publisher.sources(), GenerationBuilder.Fault.none(), () -> false),
          GenerationBuilder.Fault.none());
      assertEquals(1, publisher.sources().size());
    }
  }

  @Test
  void hundredSeededFailureSchedulesNeverExposeMixedGenerations() throws Exception {
    Random random = new Random(7007);
    var boundaries = GenerationBuilder.Boundary.values();
    StringBuilder evidence =
        new StringBuilder(
            "schedule,seed,boundary,oldCount,newCount,documentOrdinal,observedCount\n");
    for (int schedule = 0; schedule < 100; schedule++) {
      Path run = root.resolve("schedule-" + schedule);
      var cas = new ContentAddressedStore(run.resolve("cas"));
      Path index = run.resolve("index");
      var builder = new GenerationBuilder(index, cas);
      var chosen = boundaries[random.nextInt(boundaries.length)];
      int oldCount = 2 + random.nextInt(5),
          newCount = oldCount + 1 + random.nextInt(8),
          documentOrdinal = 1 + random.nextInt(newCount);
      java.util.concurrent.atomic.AtomicInteger documents =
          new java.util.concurrent.atomic.AtomicInteger();
      try (var publisher = new GenerationPublisher(index)) {
        publisher.publish(
            builder.build(
                manifest(cas, "old", oldCount),
                List.of(),
                GenerationBuilder.Fault.none(),
                () -> false),
            GenerationBuilder.Fault.none());
        GenerationBuilder.Fault failure =
            at -> {
              if (at == chosen
                  && (at != GenerationBuilder.Boundary.DOCUMENT
                      || documents.incrementAndGet() == documentOrdinal))
                throw new IOException("seeded failure " + at);
            };
        try {
          publisher.publish(
              builder.build(
                  manifest(cas, "new", newCount), publisher.sources(), failure, () -> false),
              failure);
        } catch (IOException expected) {
          assertTrue(expected.getMessage().contains("seeded failure"));
        }
      }
      try (var recovered = new GenerationPublisher(index);
          var lease = recovered.acquire()) {
        int n = lease.reader().numDocs();
        assertTrue(n == oldCount || n == newCount, "schedule " + schedule);
        evidence
            .append(schedule)
            .append(",7007,")
            .append(chosen)
            .append(',')
            .append(oldCount)
            .append(',')
            .append(newCount)
            .append(',')
            .append(documentOrdinal)
            .append(',')
            .append(n)
            .append('\n');
        Set<String> revisions = new HashSet<>();
        recovered.sources().forEach(d -> revisions.add(d.revision()));
        assertEquals(Set.of(n == oldCount ? "old" : "new"), revisions, "schedule " + schedule);
      }
    }
    Files.createDirectories(Path.of("target/failure-schedules"));
    Files.writeString(Path.of("target/failure-schedules/schedules.csv"), evidence);
  }

  @Test
  void corruptCurrentPointerFallsBackAndStagingIsQuarantined() throws Exception {
    var cas = new ContentAddressedStore(root.resolve("cas"));
    Path index = root.resolve("index");
    var b = new GenerationBuilder(index, cas);
    String previous;
    try (var p = new GenerationPublisher(index)) {
      p.publish(
          b.build(manifest(cas, "old", 2), List.of(), GenerationBuilder.Fault.none(), () -> false),
          GenerationBuilder.Fault.none());
      previous = p.currentId();
      p.publish(
          b.build(
              manifest(cas, "new", 3), p.sources(), GenerationBuilder.Fault.none(), () -> false),
          GenerationBuilder.Fault.none());
    }
    Files.writeString(index.resolve("CURRENT"), "corrupt");
    Files.createDirectory(index.resolve("staging/incomplete"));
    Files.writeString(index.resolve("staging/incomplete/corrupt"), "metadata");
    try (var p = new GenerationPublisher(index)) {
      assertEquals(previous, p.currentId());
      assertEquals(2, p.sources().size());
    }
    try (var files = Files.list(index.resolve("quarantine"))) {
      assertEquals(2, files.count());
    }
  }

  @Test
  void cancellationAndSimulatedEnospcPreserveLastCommittedReader() throws Exception {
    var cas = new ContentAddressedStore(root.resolve("cas"));
    Path index = root.resolve("index");
    var b = new GenerationBuilder(index, cas);
    try (var p = new GenerationPublisher(index)) {
      p.publish(
          b.build(manifest(cas, "old", 2), List.of(), GenerationBuilder.Fault.none(), () -> false),
          GenerationBuilder.Fault.none());
      String old = p.currentId();
      assertThrows(
          IOException.class,
          () ->
              b.build(
                  manifest(cas, "cancel", 3),
                  p.sources(),
                  GenerationBuilder.Fault.none(),
                  () -> true));
      assertThrows(
          IOException.class,
          () ->
              p.publish(
                  b.build(
                      manifest(cas, "disk", 3),
                      p.sources(),
                      GenerationBuilder.Fault.none(),
                      () -> false),
                  at -> {
                    if (at == GenerationBuilder.Boundary.BEFORE_POINTER)
                      throw new FileSystemException(
                          index.toString(), null, "No space left on device (injected ENOSPC)");
                  }));
      assertEquals(old, p.currentId());
      assertEquals(2, p.sources().size());
    }
  }

  @Test
  void identifierTokenizerPreservesOriginalAndOffsetsWithUnicode() throws Exception {
    var parts = CodeIdentifierAnalyzer.parts("HTTPServer snake_case caféValue");
    assertTrue(
        parts.stream()
            .anyMatch(p -> p.term().equals("httpserver") && p.start() == 0 && p.end() == 10));
    assertTrue(parts.stream().anyMatch(p -> p.term().equals("http")));
    assertTrue(parts.stream().anyMatch(p -> p.term().equals("server")));
    assertTrue(parts.stream().anyMatch(p -> p.term().equals("snake")));
    assertTrue(parts.stream().anyMatch(p -> p.term().equals("case")));
    assertTrue(parts.stream().anyMatch(p -> p.term().equals("café")));
    try (var analyzer = new CodeIdentifierAnalyzer();
        var tokens = analyzer.tokenStream("content", "fooBar")) {
      var term =
          tokens.addAttribute(org.apache.lucene.analysis.tokenattributes.CharTermAttribute.class);
      var position =
          tokens.addAttribute(
              org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute.class);
      tokens.reset();
      List<String> values = new ArrayList<>();
      while (tokens.incrementToken()) {
        values.add(term.toString());
        assertTrue(position.getPositionIncrement() >= 0);
      }
      tokens.end();
      assertEquals(List.of("foobar", "foo", "bar"), values);
    }
  }
}
