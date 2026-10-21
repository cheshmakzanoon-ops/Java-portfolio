package io.github.cheshmakzanoon.portfolio.stacks;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvaluationFingerprintTest {
  @TempDir Path root;

  public static final class Probe {
    public static void main(String[] arguments) throws Exception {
      System.out.println(RelevanceEvaluator.modelFingerprint("fixed-phase-name"));
    }
  }

  @Test
  void constantPhaseIdentityChangesWithExtractedEvaluationAndInferenceHelpers() throws Exception {
    Path evaluation = copyOrigin(RelevanceEvaluator.class, root.resolve("evaluation"));
    Path inference = copyOrigin(RankingCodeIdentity.class, root.resolve("query"));
    String before = fingerprint(evaluation, inference);
    String prefix = "io/github/cheshmakzanoon/portfolio/stacks/";
    Path reader = evaluation.resolve(prefix + "EvaluationJudgments.class");
    byte[] originalReader = Files.readAllBytes(reader);
    replaceConstant(reader, "evaluation splits", "evaluation splitz");
    assertNotEquals(
        before,
        fingerprint(evaluation, inference),
        "new evaluation helpers belong to the phase identity");
    Files.write(reader, originalReader);
    replaceConstant(
        inference.resolve(prefix + "SearchRankingPipeline.class"),
        "SYNTAX_BYPASS",
        "SYNTAX_BYPASZ");
    assertNotEquals(
        before,
        fingerprint(evaluation, inference),
        "a constant phase name still binds all inference helpers");
  }

  private Path copyOrigin(Class<?> anchor, Path copy) throws Exception {
    Path original = Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
    if (Files.isRegularFile(original)) {
      Files.createDirectories(copy);
      try (var jar = new java.util.jar.JarFile(original.toFile())) {
        var entries = jar.entries();
        while (entries.hasMoreElements()) {
          var entry = entries.nextElement();
          if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
          Path target = copy.resolve(entry.getName()).normalize();
          assertTrue(target.startsWith(copy));
          Files.createDirectories(target.getParent());
          try (var input = jar.getInputStream(entry)) {
            Files.copy(input, target);
          }
        }
      }
      return copy;
    }
    try (var files = Files.walk(original)) {
      for (Path file : files.toList()) {
        Path target = copy.resolve(original.relativize(file));
        if (Files.isDirectory(file)) Files.createDirectories(target);
        else Files.copy(file, target);
      }
    }
    return copy;
  }

  private String fingerprint(Path evaluation, Path inference) throws Exception {
    String classpath =
        evaluation
            + File.pathSeparator
            + inference
            + File.pathSeparator
            + System.getProperty("java.class.path");
    Path output = root.resolve("probe-" + UUID.randomUUID() + ".txt");
    Process process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx128m",
                "-cp",
                classpath,
                Probe.class.getName())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    try {
      assertTrue(process.waitFor(20, TimeUnit.SECONDS), "identity probe deadline");
      assertEquals(
          0,
          process.exitValue(),
          () -> {
            try {
              return Files.readString(output);
            } catch (IOException failure) {
              return failure.toString();
            }
          });
      String result = Files.readString(output).strip();
      assertTrue(result.matches("[a-f0-9]{64}"), result);
      return result;
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
      }
    }
  }

  private void replaceConstant(Path file, String oldValue, String newValue) throws Exception {
    byte[] bytes = Files.readAllBytes(file);
    byte[] oldBytes = oldValue.getBytes(StandardCharsets.UTF_8),
        replacement = newValue.getBytes(StandardCharsets.UTF_8);
    assertEquals(oldBytes.length, replacement.length);
    int found = -1;
    for (int i = 0; i <= bytes.length - oldBytes.length; i++) {
      if (Arrays.equals(bytes, i, i + oldBytes.length, oldBytes, 0, oldBytes.length)) {
        assertEquals(-1, found, "constant must be unique");
        found = i;
      }
    }
    assertTrue(found >= 0, "constant must exist");
    System.arraycopy(replacement, 0, bytes, found, replacement.length);
    Files.write(file, bytes);
  }
}
