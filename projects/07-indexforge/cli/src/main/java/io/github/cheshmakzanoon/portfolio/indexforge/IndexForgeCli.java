package io.github.cheshmakzanoon.portfolio.indexforge;

import java.nio.file.*;
import java.util.*;

public final class IndexForgeCli {
  private IndexForgeCli() {}

  public static void main(String[] args) throws Exception {
    validate(args);
    String command = args[0];
    Path data = Path.of(args[1]);
    if (command.equals("prepare-api-catalog")) {
      if (args.length != 3)
        throw new IllegalArgumentException("prepare-api-catalog workerDirectory newCatalogFile");
      var catalog = SourceCatalogBuilder.prepare(data);
      catalog.writeNew(Path.of(args[2]));
      System.out.println(
          "catalog="
              + catalog.identity()
              + " source="
              + catalog.sourceHash()
              + " worker="
              + catalog.workerHash()
              + " jdk="
              + catalog.jdk());
      return;
    }
    if (command.startsWith("wave3-")) {
      if (args.length != 5)
        throw new IllegalArgumentException(
            "wave3-{audit,fit,validate} data fixtures output catalogFile");
      SourceWave3Study.run(
          command.substring("wave3-".length()),
          data,
          Path.of(args[2]),
          Path.of(args[3]),
          Path.of(args[4]));
      return;
    }
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

  private static void validate(String[] args) {
    if (args.length < 2)
      throw new IllegalArgumentException("command data [arguments]; see contracts/cli.md");
    int min, max;
    switch (args[0]) {
      case "up" -> {
        min = 2;
        max = 2;
      }
      case "prepare-api-catalog" -> {
        min = 3;
        max = 3;
      }
      case "demo" -> {
        min = 2;
        max = 3;
      }
      case "evaluate" -> {
        min = 4;
        max = 5;
      }
      case "benchmark" -> {
        min = 3;
        max = 5;
      }
      case "wave2-audit", "wave2-fit", "wave2-validate" -> {
        min = 4;
        max = 4;
      }
      case "wave3-audit", "wave3-fit", "wave3-validate" -> {
        min = 5;
        max = 5;
      }
      case "ingest" -> {
        min = 8;
        max = 100008;
      }
      case "search", "resume" -> {
        min = 5;
        max = 5;
      }
      case "reindex", "verify-generation" -> {
        min = 4;
        max = 4;
      }
      default -> throw new IllegalArgumentException("unsupported command " + args[0]);
    }
    if (args.length < min || args.length > max)
      throw new IllegalArgumentException(
          "invalid arguments for " + args[0] + "; see contracts/cli.md");
    if (args[0].equals("demo") && args.length == 3) bounded(args[2], 3, 100000);
    if (args[0].equals("benchmark")) {
      if (args.length >= 4) bounded(args[3], 3, 100000);
      if (args.length == 5) bounded(args[4], 1, 3600);
    }
    if (args[0].equals("evaluate")
        && args.length == 5
        && !Set.of("development", "final").contains(args[4]))
      throw new IllegalArgumentException("evaluate phase must be development or final");
    if (args[0].equals("search")) QueryAst.parse(args[4]);
    if (args[0].equals("resume") && !args[4].matches("[a-f0-9]{64}"))
      throw new IllegalArgumentException("resume requires a SHA256 job id");
  }

  private static void bounded(String value, int minimum, int maximum) {
    int parsed;
    try {
      parsed = Integer.parseInt(value);
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException("integer required", invalid);
    }
    if (parsed < minimum || parsed > maximum)
      throw new IllegalArgumentException("value must be " + minimum + ".." + maximum);
  }
}
