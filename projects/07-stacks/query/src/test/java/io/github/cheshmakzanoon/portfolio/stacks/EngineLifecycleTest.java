package io.github.cheshmakzanoon.portfolio.stacks;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineLifecycleTest {
  @TempDir Path root;

  private static SnapshotManifest manifest(SearchEngine engine, String tenant, String revision)
      throws IOException {
    String text = "class Document { String value = \"searchable " + revision + "\"; }";
    return new SnapshotManifest(
        tenant,
        "repo",
        revision,
        false,
        List.of(
            new SnapshotManifest.Entry(
                "Document.java",
                engine.contentStore().put(text.getBytes(StandardCharsets.UTF_8)),
                "java",
                "source",
                List.of("Document"),
                "",
                "")),
        Set.of());
  }

  @Test
  void rejectedShutdownLeavesAllTenantsReadableAndRetainsExclusiveOwnership() throws Exception {
    var auth = new AuthorizationFilter();
    var first = auth.update("first", "first", Set.of("repo"), true);
    var second = auth.update("second", "second", Set.of("repo"), true);
    var engine = new SearchEngine(root, auth);
    try {
      engine.ingest(
          first,
          "one",
          manifest(engine, "first", "one"),
          GenerationBuilder.Fault.none(),
          () -> false);
      engine.ingest(
          second,
          "two",
          manifest(engine, "second", "two"),
          GenerationBuilder.Fault.none(),
          () -> false);
      try (var held = engine.generations("second").acquire()) {
        assertThrows(IOException.class, engine::close);
        for (String principal : List.of("first", "second"))
          assertEquals(
              1,
              engine
                  .search(principal, new SearchEngine.Request("searchable", 10, null, Map.of()))
                  .totalHits());
        assertThrows(IOException.class, () -> new SearchEngine(root, auth));
        assertEquals(1, held.reader().numDocs());
      }
      engine.close();
      assertThrows(IllegalStateException.class, () -> engine.generations("first"));
      assertThrows(
          IllegalStateException.class,
          () -> engine.register(first, "late", manifest(engine, "first", "late")));
      try (var reopened = new SearchEngine(root, auth)) {
        assertEquals(
            1,
            reopened
                .search("second", new SearchEngine.Request("searchable", 10, null, Map.of()))
                .totalHits());
      }
    } finally {
      engine.close();
    }
  }

  @Test
  void shutdownWaitsForAnAdmittedWriterBeforeReleasingTheDirectory() throws Exception {
    var auth = new AuthorizationFilter();
    var admin = auth.update("admin", "tenant", Set.of("repo"), true);
    var engine = new SearchEngine(root, auth);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var tasks = Executors.newFixedThreadPool(2)) {
      var manifest = manifest(engine, "tenant", "finished");
      var writer =
          tasks.submit(
              () ->
                  engine.ingest(
                      admin,
                      "writer",
                      manifest,
                      stage -> {
                        if (stage == GenerationBuilder.Boundary.BUILD_START) {
                          entered.countDown();
                          try {
                            if (!release.await(10, TimeUnit.SECONDS))
                              throw new IOException("test barrier deadline");
                          } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IOException(interrupted);
                          }
                        }
                      },
                      () -> false));
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      var stopping =
          tasks.submit(
              () -> {
                engine.close();
                return null;
              });
      try {
        assertThrows(TimeoutException.class, () -> stopping.get(100, TimeUnit.MILLISECONDS));
      } finally {
        release.countDown();
      }
      writer.get(10, TimeUnit.SECONDS);
      stopping.get(10, TimeUnit.SECONDS);
      try (var reopened = new SearchEngine(root, auth)) {
        assertEquals(
            1,
            reopened
                .search("admin", new SearchEngine.Request("finished", 10, null, Map.of()))
                .totalHits());
      }
    } finally {
      release.countDown();
      engine.close();
    }
  }

  @Test
  void revocationAfterBuildRemovesStagingWithoutChangingTheCommittedGeneration() throws Exception {
    var auth = new AuthorizationFilter();
    var admin = auth.update("admin", "tenant", Set.of("repo"), true);
    auth.update("reader", "tenant", Set.of("repo"), false);
    try (var engine = new SearchEngine(root, auth)) {
      engine.ingest(
          admin,
          "old",
          manifest(engine, "tenant", "original"),
          GenerationBuilder.Fault.none(),
          () -> false);
      String generation = engine.generations("tenant").currentId();
      var replacement = manifest(engine, "tenant", "replacement");
      assertThrows(
          SecurityException.class,
          () ->
              engine.ingest(
                  admin,
                  "revoked",
                  replacement,
                  stage -> {
                    if (stage == GenerationBuilder.Boundary.AFTER_WARM)
                      auth.update("admin", "tenant", Set.of(), false);
                  },
                  () -> false));
      assertEquals(generation, engine.generations("tenant").currentId());
      assertEquals(
          1,
          engine
              .search("reader", new SearchEngine.Request("original", 10, null, Map.of()))
              .totalHits());
      try (var stages = Files.list(root.resolve("index/tenant/staging"))) {
        assertEquals(0, stages.count());
      }
    }
  }
}
