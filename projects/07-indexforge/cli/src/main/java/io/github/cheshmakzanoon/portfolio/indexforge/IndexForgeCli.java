package io.github.cheshmakzanoon.portfolio.indexforge;

import java.nio.file.*;
import java.util.*;

public final class IndexForgeCli {
  private IndexForgeCli() {}

  public static void main(String[] args) throws Exception {
    if (args.length < 2)
      throw new IllegalArgumentException("command data [arguments]; see contracts/cli.md");
    String command = args[0];
    Path data = Path.of(args[1]);
    if (command.startsWith("wave2-")) {
      if (args.length != 4)
        throw new IllegalArgumentException("wave2-{audit,fit,validate} data fixtures output");
      Wave2Study.run(
          command.substring("wave2-".length()), data, Path.of(args[2]), Path.of(args[3]));
      return;
    }
    if (command.equals("up")) {
      Files.createDirectories(data.resolve("sources"));
      System.out.println("Prepared local IndexForge data " + data.toAbsolutePath());
      return;
    }
    if (command.equals("demo")) {
      DemoScenario.run(data, args.length > 2 ? Integer.parseInt(args[2]) : 20000);
      return;
    }
    if (command.equals("evaluate")) {
      RelevanceEvaluator.run(
          data, Path.of(args[2]), Path.of(args[3]), args.length > 4 ? args[4] : "development");
      return;
    }
    if (command.equals("benchmark")) {
      PerformanceHarness.run(
          data,
          Path.of(args[2]),
          args.length > 3 ? Integer.parseInt(args[3]) : 100000,
          args.length > 4 ? Integer.parseInt(args[4]) : 30);
      return;
    }
    if (args.length < 4) throw new IllegalArgumentException("tenant and repository required");
    String tenant = args[2], repository = args[3];
    AuthorizationFilter auth = new AuthorizationFilter();
    var access = auth.update("local-cli", tenant, Set.of(repository), true);
    try (SearchEngine engine = new SearchEngine(data, auth)) {
      switch (command) {
        case "ingest" -> {
          if (args.length < 8)
            throw new IllegalArgumentException(
                "ingest data tenant repo sourceRoot relativePath revision key [deletedPath...]");
          var reader =
              new SafeSourceReader(
                  Path.of(args[4]), SafeSourceReader.Limits.defaults(), engine.contentStore());
          Set<String> deleted =
              args.length > 8
                  ? new TreeSet<>(Arrays.asList(args).subList(8, args.length))
                  : Set.of();
          var manifest =
              reader.directory(
                  tenant, repository, args[6], Path.of(args[5]), false, deleted, () -> false);
          var job =
              engine.ingest(access, args[7], manifest, GenerationBuilder.Fault.none(), () -> false);
          System.out.println("job=" + job.id() + " manifest=" + job.manifestHash());
        }
        case "search" -> {
          if (args.length < 5) throw new IllegalArgumentException("search text required");
          var response =
              engine.search(
                  access.principal(), new SearchEngine.Request(args[4], 20, null, Map.of()));
          System.out.println(
              "generation="
                  + response.generationId()
                  + " total="
                  + response.totalHits()
                  + " relation="
                  + response.relation());
          for (var hit : response.results())
            System.out.println(
                hit.score() + "\t" + hit.path() + "\t" + hit.snippet().replace('\n', ' '));
        }
        case "resume" -> {
          engine.resume(access, args[4], GenerationBuilder.Fault.none(), () -> false);
          System.out.println("Resumed job=" + args[4]);
        }
        case "reindex" -> {
          engine.reindex(access);
          System.out.println(
              "Reindex committed generation=" + engine.generations(tenant).currentId());
        }
        case "verify-generation" -> {
          try (var lease = engine.generations(tenant).acquire()) {
            System.out.println(
                "verified generation=" + lease.id() + " documents=" + lease.reader().numDocs());
          }
        }
        default -> throw new IllegalArgumentException("unsupported command " + command);
      }
    }
  }
}
