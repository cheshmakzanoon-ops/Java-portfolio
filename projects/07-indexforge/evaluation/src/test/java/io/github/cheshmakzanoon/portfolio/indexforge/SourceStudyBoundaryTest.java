package io.github.cheshmakzanoon.portfolio.indexforge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceStudyBoundaryTest {
  @TempDir Path root;

  @Test
  void phaseRecordsRejectDuplicatesEscapesTruncationSymlinksAndOverwrite() throws Exception {
    Path record = root.resolve("identity.properties");
    SourceStudyFiles.write(record, Map.of("code", "a".repeat(64), "model", "NONE"));
    assertEquals("NONE", SourceStudyFiles.read(record).get("model"));
    SourceStudyFiles.check(SourceStudyFiles.read(record), "code", "a".repeat(64));
    assertThrows(
        IOException.class,
        () -> SourceStudyFiles.check(SourceStudyFiles.read(record), "code", "b".repeat(64)));
    assertThrows(
        FileAlreadyExistsException.class,
        () -> SourceStudyFiles.write(record, Map.of("model", "OTHER")));
    Files.writeString(record, "model=NONE\nmodel=OTHER\n");
    assertThrows(IOException.class, () -> SourceStudyFiles.read(record));
    Files.writeString(record, "bad\\key=value\n");
    assertThrows(IOException.class, () -> SourceStudyFiles.read(record));
    Files.writeString(record, "code");
    assertThrows(IOException.class, () -> SourceStudyFiles.read(record));
    Files.writeString(record, "");
    assertThrows(IOException.class, () -> SourceStudyFiles.read(record));
    Files.writeString(record, "x".repeat(16385));
    assertThrows(IOException.class, () -> SourceStudyFiles.read(record));
    assertThrows(
        IOException.class,
        () ->
            SourceStudyFiles.write(root.resolve("newline"), Map.of("model", "NONE\ncode=forged")));
    assertThrows(
        IOException.class,
        () -> SourceStudyFiles.write(root.resolve("badkey"), Map.of("bad=key", "value")));
    Path link = root.resolve("link");
    Files.createSymbolicLink(link, record);
    assertThrows(IOException.class, () -> SourceStudyFiles.read(link));
    assertThrows(IOException.class, () -> SourceStudyFiles.hash(link));
  }

  @Test
  void pathPacketsRequireEveryQueryDistinctNormalizedBoundedPathsAndImmutableFiles()
      throws Exception {
    Path file = root.resolve("paths.tsv");
    var rows = Map.of("q1", List.of("src/A.java", "src/B.java"), "q2", List.<String>of());
    SourceStudyFiles.paths(file, rows);
    assertEquals(rows, SourceStudyFiles.paths(file, rows.keySet()));
    assertThrows(FileAlreadyExistsException.class, () -> SourceStudyFiles.paths(file, rows));
    assertThrows(IOException.class, () -> SourceStudyFiles.paths(file, Set.of("q1")));
    Files.writeString(file, "q1\t../escape\nq2\t\n");
    assertThrows(IOException.class, () -> SourceStudyFiles.paths(file, rows.keySet()));
    Files.writeString(file, "q1\tA|A\nq2\t\n");
    assertThrows(IOException.class, () -> SourceStudyFiles.paths(file, rows.keySet()));
    Files.writeString(file, "q1\tA\nq1\tB\n");
    assertThrows(IOException.class, () -> SourceStudyFiles.paths(file, rows.keySet()));
    Files.writeString(file, "q1\tA\n");
    assertThrows(IOException.class, () -> SourceStudyFiles.paths(file, rows.keySet()));
    var many = new ArrayList<String>();
    for (int i = 0; i < 21; i++) many.add("src/F" + i + ".java");
    Files.writeString(file, "q1\t" + String.join("|", many) + "\nq2\t\n");
    assertThrows(IOException.class, () -> SourceStudyFiles.paths(file, rows.keySet()));
  }

  @Test
  void phaseArtifactManifestDetectsAddedChangedAndNonregularInputs() throws Exception {
    Files.writeString(root.resolve("model.bin"), "artifact");
    SourceStudyFiles.write(root.resolve("identity.properties"), Map.of("model", "NONE"));
    var first = SourceStudyFiles.manifest(root);
    assertEquals(Set.of("model.bin"), first.keySet());
    Files.writeString(root.resolve("model.bin"), "changed");
    assertNotEquals(first, SourceStudyFiles.manifest(root));
    Files.writeString(root.resolve("extra.txt"), "extra");
    assertEquals(2, SourceStudyFiles.manifest(root).size());
    Files.createDirectory(root.resolve("nested"));
    assertThrows(IOException.class, () -> SourceStudyFiles.manifest(root));
  }

  @Test
  void protocolRequiresEveryApprovedFileExactHashAndNoAliasOrDuplicate() throws Exception {
    var names =
        List.of(
            "PREREGISTRATION.md",
            "folds.tsv",
            "comparator-reference.tsv",
            "r0-reference.tsv",
            "IMPLEMENTATION-ADDENDUM-1.md",
            "IMPLEMENTATION-ADDENDUM-2.md",
            "IMPLEMENTATION-ADDENDUM-3.md",
            "IMPLEMENTATION-ADDENDUM-4.md",
            "IMPLEMENTATION-ADDENDUM-5.md");
    var lines = new ArrayList<String>();
    for (String name : names) {
      Files.writeString(root.resolve(name), "synthetic protocol fixture " + name);
      lines.add(SourceStudyFiles.hash(root.resolve(name)) + "  " + name);
    }
    Path manifest = root.resolve("FREEZE.sha256");
    Files.write(manifest, lines);
    SourceWave3Study.verifyProtocol(root);
    Files.writeString(root.resolve("folds.tsv"), "changed");
    assertThrows(IOException.class, () -> SourceWave3Study.verifyProtocol(root));
    Files.writeString(root.resolve("folds.tsv"), "synthetic protocol fixture folds.tsv");
    Files.write(manifest, lines.subList(0, lines.size() - 1));
    assertThrows(IOException.class, () -> SourceWave3Study.verifyProtocol(root));
    var duplicate = new ArrayList<>(lines);
    duplicate.add(lines.getFirst());
    Files.write(manifest, duplicate);
    assertThrows(IOException.class, () -> SourceWave3Study.verifyProtocol(root));
    Files.writeString(manifest, "a".repeat(64) + "  ../escape\n");
    assertThrows(IOException.class, () -> SourceWave3Study.verifyProtocol(root));
    assertEquals(RankingStrategy.SOURCE_USAGE, SourceWave3Study.strategy("R3"));
    assertEquals(RankingStrategy.CLASSIC, SourceWave3Study.strategy("ORIGINAL_BASELINE"));
    assertThrows(IllegalArgumentException.class, () -> SourceWave3Study.strategy("unregistered"));
    assertThrows(
        IllegalArgumentException.class,
        () -> SourceWave3Study.run("heldout", root, root, root, root));
  }
}
