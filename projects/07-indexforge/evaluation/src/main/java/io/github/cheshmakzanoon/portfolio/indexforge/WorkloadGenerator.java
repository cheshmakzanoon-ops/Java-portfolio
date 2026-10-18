package io.github.cheshmakzanoon.portfolio.indexforge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class WorkloadGenerator {
  public record Generated(long bytes, List<SnapshotManifest> manifests) {}

  private WorkloadGenerator() {}

  public static Generated generate(ContentAddressedStore store, int documents, long seed)
      throws IOException {
    if (documents < 3 || documents > 100_000) throw new IllegalArgumentException("workload size");
    List<SnapshotManifest> manifests = new ArrayList<>();
    long bytes = 0;
    Random random = new Random(seed);
    for (int tenant = 0; tenant < 3; tenant++) {
      List<SnapshotManifest.Entry> entries = new ArrayList<>();
      for (int i = tenant; i < documents; i += 3) {
        String symbol = "resolveGeneration" + i;
        String text =
            "/** Source snapshot atomic publication reader lease repository authorization. */\npublic class Component"
                + i
                + " { public long "
                + symbol
                + "(long prior) { long revision = "
                + random.nextInt(1_000_000)
                + "L; return Math.max(prior, revision); } }\n";
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        bytes += b.length;
        entries.add(
            new SnapshotManifest.Entry(
                "src/Component" + i + ".java",
                store.put(b),
                "java",
                "source",
                List.of(symbol, "Component" + i),
                "io.demo:component" + i,
                "1.0"));
      }
      manifests.add(
          new SnapshotManifest(
              "tenant" + tenant, "public", "seed-" + seed, true, entries, Set.of()));
    }
    return new Generated(bytes, List.copyOf(manifests));
  }
}
