package io.github.cheshmakzanoon.portfolio.stacks;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class DemoScenario {
  private DemoScenario() {}

  public static void run(Path data, int count) throws Exception {
    AuthorizationFilter auth = new AuthorizationFilter();
    List<AuthorizationFilter.Access> access = new ArrayList<>();
    for (int i = 0; i < 3; i++)
      access.add(auth.update("demo" + i, "tenant" + i, Set.of("public"), true));
    long bytes;
    try (SearchEngine engine = new SearchEngine(data, auth)) {
      var generated = WorkloadGenerator.generate(engine.contentStore(), count, 7007);
      bytes = generated.bytes();
      for (int i = 0; i < 3; i++)
        engine.ingest(
            access.get(i),
            "seed-" + count,
            generated.manifests().get(i),
            GenerationBuilder.Fault.none(),
            () -> false);
      var request = new SearchEngine.Request("resolveGeneration0", 10, null, Map.of());
      var result = engine.search("demo0", request);
      if (result.results().isEmpty()
          || !result.results().getFirst().path().equals("src/Component0.java"))
        throw new AssertionError("identifier retrieval");
      var prose =
          engine.search(
              "demo0", new SearchEngine.Request("atomic publication", 10, null, Map.of()));
      if (prose.totalHits() < count / 3) throw new AssertionError("prose retrieval");
      var denied =
          new SnapshotManifest(
              "tenant0",
              "denied",
              "v1",
              false,
              List.of(
                  new SnapshotManifest.Entry(
                      "Private.java",
                      engine
                          .contentStore()
                          .put("inaccessibleSecret".getBytes(StandardCharsets.UTF_8)),
                      "java",
                      "source",
                      List.of("inaccessibleSecret"),
                      "",
                      "")),
              Set.of());
      var admin = auth.update("admin", "tenant0", Set.of("public", "denied"), true);
      engine.ingest(admin, "denied", denied, GenerationBuilder.Fault.none(), () -> false);
      if (engine
                  .search(
                      "demo0", new SearchEngine.Request("inaccessibleSecret", 10, null, Map.of()))
                  .totalHits()
              != 0
          || !engine.suggest("demo0", "inaccessible").isEmpty())
        throw new AssertionError("authorization leak");
      SnapshotManifest initial = generated.manifests().getFirst();
      String old = engine.generations("tenant0").currentId();
      var replacement =
          new SnapshotManifest(
              "tenant0",
              "public",
              "v2",
              true,
              initial.entries().subList(1, initial.entries().size()),
              Set.of(initial.entries().getFirst().path()));
      try (ExecutorService searches = Executors.newSingleThreadExecutor()) {
        Future<?> concurrent =
            searches.submit(
                () -> {
                  try {
                    for (int n = 0; n < 100; n++) {
                      var response =
                          engine.search(
                              "demo0", new SearchEngine.Request("atomic", 10, null, Map.of()));
                      long before = initial.entries().size(), after = before - 1;
                      if (response.totalHits() != before && response.totalHits() != after)
                        throw new AssertionError("mixed-generation count");
                    }
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  }
                });
        engine.ingest(
            access.getFirst(),
            "replacement-" + count,
            replacement,
            GenerationBuilder.Fault.none(),
            () -> false);
        concurrent.get(60, TimeUnit.SECONDS);
      }
      if (old.equals(engine.generations("tenant0").currentId()))
        throw new AssertionError("replacement not published");
      var interrupted =
          new SnapshotManifest(
              "tenant0", "public", "interrupted", false, initial.entries(), Set.of());
      try {
        engine.ingest(
            access.getFirst(),
            "interrupted-" + count,
            interrupted,
            boundary -> {
              if (boundary == GenerationBuilder.Boundary.BEFORE_COMMIT)
                throw new IOException("demonstration interrupted build");
            },
            () -> false);
        throw new AssertionError("failure not injected");
      } catch (IOException expected) {
        if (!expected.getMessage().contains("demonstration")) throw expected;
      }
    }
    try (SearchEngine recovered = new SearchEngine(data, auth)) {
      var response =
          recovered.search("demo0", new SearchEngine.Request("atomic", 10, null, Map.of()));
      if (response.totalHits() != (count + 2) / 3 - 1)
        throw new AssertionError("restart atomicity");
      if (recovered
              .search("demo0", new SearchEngine.Request("inaccessibleSecret", 10, null, Map.of()))
              .totalHits()
          != 0) throw new AssertionError("restart isolation");
    }
    System.out.println(
        "{\"scenario\":\"stacks-transactional-demo-v1\",\"seed\":7007,\"documents\":"
            + count
            + ",\"sourceBytes\":"
            + bytes
            + ",\"tenants\":3,\"assertions\":\"passed\"}");
  }
}
