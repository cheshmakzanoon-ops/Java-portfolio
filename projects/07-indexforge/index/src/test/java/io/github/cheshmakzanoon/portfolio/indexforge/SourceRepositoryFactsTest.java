package io.github.cheshmakzanoon.portfolio.indexforge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceRepositoryFactsTest {
  @TempDir Path root;

  private static SourceDocument source(String path, String body) {
    return new SourceDocument(
        "tenant",
        "repository",
        "revision",
        new SnapshotManifest.Entry(path, Digests.sha256(body), "java", "source", List.of(), "", ""),
        body);
  }

  @Test
  void identicalSourceHasCanonicalGraphBytesAcrossGenerationNamesAndReordering() throws Exception {
    var first = source("A.java", "final class A { int read(int n) { return n; } }");
    var second = source("B.java", "final class B { int write(int n) { return n; } }");
    var a = new SourceRepositoryFacts.Builder("tenant", "repository");
    a.add(first, SourceJavaParser.parse(first.content()));
    a.add(second, SourceJavaParser.parse(second.content()));
    var b = new SourceRepositoryFacts.Builder("tenant", "repository");
    b.add(second, SourceJavaParser.parse(second.content()));
    b.add(first, SourceJavaParser.parse(first.content()));
    Path g1 = Files.createDirectory(root.resolve("random-generation-one")),
        g2 = Files.createDirectory(root.resolve("different-random-generation"));
    SourceRepositoryFacts.write(g1, a.finish());
    SourceRepositoryFacts.write(g2, b.finish());
    String file = SourceRepositoryFacts.fileName("tenant", "repository");
    assertArrayEquals(Files.readAllBytes(g1.resolve(file)), Files.readAllBytes(g2.resolve(file)));
    var restored = SourceRepositoryFacts.read(g1, "tenant", "repository");
    assertTrue(restored.inventoryObserved());
    assertEquals(2, restored.documents().size());
    assertEquals(first.key(), restored.documents().getFirst().key());
    assertThrows(IOException.class, () -> SourceRepositoryFacts.read(g1, "tenant", "denied"));
    byte[] broken = Files.readAllBytes(g1.resolve(file));
    broken[broken.length - 1] ^= 1;
    Files.write(g1.resolve(file), broken);
    assertThrows(IOException.class, () -> SourceRepositoryFacts.read(g1, "tenant", "repository"));
  }

  @Test
  void unsupportedSourceIsExplicitNotACompleteZeroCallGraph() throws Exception {
    var input = source("TooLarge.java", "x".repeat(65537));
    var builder = new SourceRepositoryFacts.Builder("tenant", "repository");
    builder.add(input, SourceFacts.Unit.unknown(SourceFacts.Status.SOURCE_QUOTA));
    SourceRepositoryFacts.write(root, builder.finish());
    var restored = SourceRepositoryFacts.read(root, "tenant", "repository");
    assertFalse(restored.documents().getFirst().facts().declarationInventoryKnown());
    var wrong =
        new SourceDocument("tenant", "denied", input.revision(), input.entry(), input.content());
    assertThrows(
        IOException.class,
        () -> builder.add(wrong, SourceFacts.Unit.unknown(SourceFacts.Status.SOURCE_QUOTA)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SourceRepositoryFacts.Snapshot(
                "tenant", "repository", false, restored.documents()));
  }
}
