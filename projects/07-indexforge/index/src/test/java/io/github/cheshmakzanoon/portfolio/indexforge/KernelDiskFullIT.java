package io.github.cheshmakzanoon.portfolio.indexforge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Linux reference integration: inject a real kernel ENOSPC at pointer publication. */
class KernelDiskFullIT {
  @TempDir Path root;

  @Test
  void kernelEnospcBeforePointerRetainsSearchableCommittedGeneration() throws Exception {
    assertTrue(Files.exists(Path.of("/dev/full")), "Linux reference fixture requires /dev/full");
    var cas = new ContentAddressedStore(root.resolve("cas"));
    var builder = new GenerationBuilder(root.resolve("index"), cas);
    var entry =
        new SnapshotManifest.Entry(
            "A.java",
            cas.put("class A {}".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            "java",
            "source",
            List.of("A"),
            "",
            "");
    var first = new SnapshotManifest("t", "r", "one", false, List.of(entry), Set.of());
    String old;
    try (var publisher = new GenerationPublisher(root.resolve("index"))) {
      var built = builder.build(first, List.of(), GenerationBuilder.Fault.none(), () -> false);
      publisher.publish(built, GenerationBuilder.Fault.none());
      old = built.id();
      var replacement =
          builder.build(
              new SnapshotManifest("t", "r", "two", false, List.of(entry), Set.of()),
              publisher.sources(),
              GenerationBuilder.Fault.none(),
              () -> false);
      IOException failure =
          assertThrows(
              IOException.class,
              () ->
                  publisher.publish(
                      replacement,
                      at -> {
                        if (at == GenerationBuilder.Boundary.BEFORE_POINTER)
                          try (var full = Files.newOutputStream(Path.of("/dev/full"))) {
                            full.write(1);
                          }
                      }));
      assertTrue(failure.getMessage().contains("No space left on device"), failure.toString());
      try (var lease = publisher.acquire()) {
        assertEquals(old, lease.id());
        assertEquals(1, lease.searcher().count(new MatchAllDocsQuery()));
      }
    }
    try (var reopened = new GenerationPublisher(root.resolve("index"));
        var lease = reopened.acquire()) {
      assertEquals(old, lease.id());
      assertEquals("one", reopened.sources().getFirst().revision());
    }
  }
}
