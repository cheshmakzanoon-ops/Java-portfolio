package io.github.cheshmakzanoon.portfolio.stacks;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Actual shaded CLI and executable Boot origins, parser child startup, migration and HTTP parity.
 */
class SourcePackagedIT {
  @TempDir Path root;
  private int invocation;

  private Path cli() {
    return Path.of("target/stacks-cli-1.0.0-SNAPSHOT.jar").toAbsolutePath();
  }

  private Path service() {
    return Path.of("../service/target/stacks-service-1.0.0-SNAPSHOT-exec.jar")
        .toAbsolutePath()
        .normalize();
  }

  private String java() {
    return Path.of(System.getProperty("java.home"), "bin/java").toString();
  }

  private record Result(int exit, String output) {}

  private List<String> sourceOptions(Path model, Path catalog) {
    return List.of(
        "-Dstacks.ranking-strategy=SOURCE_USAGE",
        "-Dstacks.ranking-profile=CODE_HIGH",
        "-Dstacks.ranking-mode=SOURCE_AWARE",
        "-Dstacks.ranking-model=" + model,
        "-Dstacks.api-catalog=" + catalog);
  }

  private Result cli(List<String> options, String... args) throws Exception {
    var command =
        new ArrayList<String>(
            List.of(java(), "-Xms32m", "-Xmx1792m", "-XX:ActiveProcessorCount=1"));
    command.addAll(options);
    command.add("-jar");
    command.add(cli().toString());
    command.addAll(List.of(args));
    Path log = root.resolve("cli-" + (invocation++) + ".log");
    var process =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    try {
      assertTrue(process.waitFor(40, TimeUnit.SECONDS), "packaged CLI deadline");
      assertTrue(Files.size(log) <= 1_048_576, "bounded diagnostic output");
      return new Result(process.exitValue(), Files.readString(log));
    } finally {
      stop(process);
    }
  }

  private static void stop(Process process) throws Exception {
    if (process.isAlive()) {
      process.destroy();
      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        assertTrue(process.waitFor(5, TimeUnit.SECONDS), "owned packaged child reaped");
      }
    }
    assertFalse(process.isAlive());
  }

  private static HttpResponse<String> request(
      HttpClient client, String base, String path, String token, String body) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5));
    if (token != null) builder.header("Authorization", "Bearer " + token);
    if (body == null) builder.GET();
    else
      builder
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body));
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void actualBootWorkerMigratesLegacyIndexAndSharesR3OrderingWithPackagedCli() throws Exception {
    assertTrue(Files.isRegularFile(cli()), "fresh current shaded artifact required");
    assertTrue(Files.isRegularFile(service()), "fresh current Boot executable required");
    Path sources = Files.createDirectories(root.resolve("sources/demo/public")),
        data = root.resolve("data");
    Files.writeString(
        sources.resolve("One.java"),
        "final class One {private static int pulse(int n){return n;}int first(int n){return pulse(n);}}");
    Files.writeString(
        sources.resolve("Two.java"),
        "final class Two {private static int pulse(int n){return n;}int first(int n){return pulse(n);}int second(int n){return pulse(n);}}");
    Path model = root.resolve("model.bin"), catalog = root.resolve("catalog.bin");
    double[] scales = new double[16];
    Arrays.fill(scales, 1);
    new LinearRankingModel(
            new double[16], scales, new double[16], "a".repeat(64), "b".repeat(64), 1)
        .writeNew(model);
    new SourceApiCatalog(
            "a".repeat(64),
            SourceParserClient.bundleIdentity(),
            System.getProperty("java.runtime.version"),
            "synthetic integration catalog; no relevance evidence",
            Set.of(),
            List.of())
        .writeNew(catalog);
    var seed =
        cli(
            List.of(),
            "ingest",
            data.toString(),
            "demo",
            "public",
            sources.toString(),
            ".",
            "v1",
            "legacy");
    assertEquals(0, seed.exit(), seed.output());
    var options = sourceOptions(model, catalog);
    var blocked = cli(options, "search", data.toString(), "demo", "public", "pulse");
    assertNotEquals(0, blocked.exit());
    assertTrue(blocked.output().contains("reindex"));
    int port;
    try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      port = socket.getLocalPort();
    }
    String base = "http://127.0.0.1:" + port;
    var client = HttpClient.newHttpClient();
    var command =
        new ArrayList<String>(
            List.of(
                java(),
                "-Xms32m",
                "-Xmx1792m",
                "-XX:ActiveProcessorCount=1",
                "-jar",
                service().toString(),
                "--server.address=127.0.0.1",
                "--server.port=" + port,
                "--spring.profiles.active=dev",
                "--spring.main.banner-mode=off",
                "--stacks.issuer-port=0",
                "--stacks.data=" + data,
                "--stacks.sources=" + root.resolve("sources"),
                "--stacks.ranking-strategy=SOURCE_USAGE",
                "--stacks.ranking-profile=CODE_HIGH",
                "--stacks.ranking-mode=SOURCE_AWARE",
                "--stacks.ranking-model=" + model,
                "--stacks.api-catalog=" + catalog));
    Path bootLog = root.resolve("boot.log");
    var boot =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(bootLog.toFile())
            .start();
    try {
      long ready = System.nanoTime() + Duration.ofSeconds(40).toNanos();
      boolean healthy = false;
      while (System.nanoTime() < ready && boot.isAlive()) {
        try {
          healthy = request(client, base, "/health", null, null).statusCode() == 200;
          if (healthy) break;
        } catch (IOException unavailable) {
        }
        Thread.sleep(50);
      }
      assertTrue(healthy, "actual packaged Boot readiness; inspect bounded boot.log");
      String token = Files.readString(data.resolve("dev-token")).strip();
      assertEquals(
          401,
          request(client, base, "/v1/search", null, "{\"text\":\"pulse\",\"pageSize\":1}")
              .statusCode());
      var reindex = request(client, base, "/v1/reindexes", token, "{}");
      assertEquals(202, reindex.statusCode(), reindex.body());
      var id = Pattern.compile("\\\"id\\\":\\\"([a-f0-9]{64})\\\"").matcher(reindex.body());
      assertTrue(id.find(), reindex.body());
      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      String state = "";
      while (System.nanoTime() < deadline) {
        var status = request(client, base, "/v1/ingestions/" + id.group(1), token, null);
        assertEquals(200, status.statusCode(), status.body());
        state = status.body();
        if (state.contains("COMPLETE") || state.contains("FAILED")) break;
        Thread.sleep(25);
      }
      assertTrue(state.contains("COMPLETE"), state);
      var response =
          request(client, base, "/v1/search", token, "{\"text\":\"pulse\",\"pageSize\":1}");
      assertEquals(200, response.statusCode(), response.body());
      assertTrue(response.body().contains("Two.java"), response.body());
      assertFalse(response.body().contains("One.java"));
      assertTrue(response.body().contains("observed-indexed-source-usage"), response.body());
      assertTrue(response.body().contains("SOURCE_USAGE"));
      assertTrue(response.body().contains(SourceRelations.SCHEMA));
      assertTrue(response.body().contains(LinearRankingModel.read(model).identity()));
      assertTrue(response.body().contains(SourceApiCatalog.read(catalog).identity()));
      assertFalse(response.body().contains("n, number of documents"));
    } finally {
      stop(boot);
    }
    assertTrue(Files.size(bootLog) <= 1_048_576);
    var cliSearch = cli(options, "search", data.toString(), "demo", "public", "pulse");
    assertEquals(0, cliSearch.exit(), cliSearch.output());
    assertTrue(
        cliSearch.output().indexOf("Two.java") < cliSearch.output().indexOf("One.java"),
        cliSearch.output());
    var migration = cli(options, "reindex", data.toString(), "demo", "public");
    assertEquals(0, migration.exit(), migration.output());
    var reopened = cli(options, "search", data.toString(), "demo", "public", "pulse");
    assertEquals(0, reopened.exit(), reopened.output());
    assertTrue(
        reopened.output().indexOf("Two.java") < reopened.output().indexOf("One.java"),
        reopened.output());
    assertEquals(0, cli(options, "verify-generation", data.toString(), "demo", "public").exit());
  }
}
