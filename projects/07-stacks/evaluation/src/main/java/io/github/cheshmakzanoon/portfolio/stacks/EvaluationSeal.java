package io.github.cheshmakzanoon.portfolio.stacks;

import java.io.IOException;
import java.nio.file.*;

/** Shared fixture identity prevents accidentally opening a new held-out model in another output. */
public final class EvaluationSeal {
  private EvaluationSeal() {}

  public static void claim(Path fixtures, Path output, String decision) throws IOException {
    if (decision.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096)
      throw new IOException("held-out decision quota");
    Files.createDirectories(output);
    claimOne(fixtures.resolve("HELDOUT-OPENED.txt"), decision);
    claimOne(output.resolve("HELDOUT-OPENED.txt"), decision);
  }

  private static void claimOne(Path path, String decision) throws IOException {
    if (Files.exists(path)) {
      if (Files.size(path) > 4096 || !Files.readString(path).equals(decision))
        throw new IOException("held-out already opened for a different or corrupt model identity");
    } else Files.writeString(path, decision, StandardOpenOption.CREATE_NEW);
  }
}
