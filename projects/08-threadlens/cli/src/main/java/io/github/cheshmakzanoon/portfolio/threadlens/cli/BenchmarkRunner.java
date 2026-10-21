package io.github.cheshmakzanoon.portfolio.threadlens.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cheshmakzanoon.portfolio.threadlens.analysis.ProfileComparator;
import io.github.cheshmakzanoon.portfolio.threadlens.jfr.EventCapabilities;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * Full protocol is 84 fresh JVMs × (30 s warmup + 60 s measurement), randomized within paired
 * blocks.
 */
public final class BenchmarkRunner {
  private static final ObjectMapper JSON = new ObjectMapper();

  private BenchmarkRunner() {}

  public static void run(Path directory, Path agent, boolean smoke) throws Exception {
    if (Files.exists(directory)) {
      try (var existing = Files.list(directory)) {
        if (existing.findAny().isPresent())
          throw new IllegalArgumentException(
              "benchmark output directory must be empty; preserve earlier trials");
      }
    }
    Files.createDirectories(directory);
    agent = agent.toAbsolutePath();
    int pairs = smoke ? 1 : 7, warmup = smoke ? 0 : 30, seconds = smoke ? 2 : 60;
    long seed = 42908;
    Map<String, Object> environment = new TreeMap<>();
    environment.put("started", Instant.now().toString());
    String sourceFingerprint = System.getProperty("threadlens.sourceFingerprint", "UNKNOWN");
    environment.put("sourceFingerprint", sourceFingerprint);
    environment.put("sourceRevision", System.getProperty("threadlens.sourceRevision", "UNKNOWN"));
    environment.put("agentJarSha256", sha256(agent));
    Path workloadArtifact =
        Path.of(
            io.github.cheshmakzanoon.portfolio.threadlens.workloads.WorkloadMain.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
    environment.put("workloadArtifact", workloadArtifact.toString());
    environment.put(
        "workloadArtifactSha256",
        Files.isRegularFile(workloadArtifact)
            ? sha256(workloadArtifact)
            : "CLASSPATH_DIRECTORY; source fingerprint required");
    environment.put("javaVendor", System.getProperty("java.vendor"));
    environment.put("javaVersion", System.getProperty("java.runtime.version"));
    environment.put("os", System.getProperty("os.name"));
    environment.put("arch", System.getProperty("os.arch"));
    environment.put("processors", Runtime.getRuntime().availableProcessors());
    environment.put("seed", seed);
    environment.put("pairs", pairs);
    environment.put("warmupSeconds", warmup);
    environment.put("measurementSeconds", seconds);
    environment.put("acceptanceEligible", !smoke && sourceFingerprint.matches("[0-9a-f]{64}"));
    environment.put(
        "hostContention",
        "operator must record exclusive host reservation; runner cannot infer external contention");
    environment.put("jdkFlags", List.of("-Xms256m", "-Xmx256m", "-XX:ActiveProcessorCount=4"));
    environment.put("eventSettings", EventCapabilities.settings(EventCapabilities.discover()));
    environment.put("collectionConfiguration", EventCapabilities.CONFIGURATION_VERSION);
    for (String file :
        List.of(
            "/proc/cpuinfo",
            "/proc/meminfo",
            "/sys/fs/cgroup/cpu.max",
            "/sys/fs/cgroup/memory.max")) {
      Path path = Path.of(file);
      if (Files.isReadable(path)) environment.put(file, Files.readString(path));
    }
    JSON.writerWithDefaultPrettyPrinter()
        .writeValue(directory.resolve("environment.json").toFile(), environment);
    List<Map<String, Object>> order = new ArrayList<>();
    Random random = new Random(seed);
    Map<String, Object> results = new TreeMap<>();
    boolean withinAllGates = true;
    for (String workload : List.of("cpu", "allocation", "contention", "virtual")) {
      Map<String, List<JsonNode>> observations = new TreeMap<>();
      for (String mode : List.of("baseline", "jfr", "instrumented"))
        observations.put(mode, new ArrayList<>());
      for (int pair = 0; pair < pairs; pair++) {
        List<String> modes = new ArrayList<>(List.of("baseline", "jfr", "instrumented"));
        Collections.shuffle(modes, random);
        for (String mode : modes) {
          String id = workload + "-pair" + pair + "-" + mode;
          Path run = directory.resolve(id);
          Files.createDirectory(run);
          Path output = run.resolve("measurement.json"), recording = run.resolve("recording.jfr");
          List<String> command =
              new ArrayList<>(
                  List.of(
                      Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                      "-Xms256m",
                      "-Xmx256m",
                      "-XX:ActiveProcessorCount=4"));
          if (mode.equals("instrumented")) {
            Path config = run.resolve("threadlens.yaml").toAbsolutePath();
            Files.writeString(
                config,
                "allowPackages: [io.github.cheshmakzanoon.portfolio.threadlens.workloads]\nmethodNames: [plantedCpuHotspot, plantedAllocationPressure, plantedMonitorContention]\nmaxMethods: 3\nqueueCapacity: 65536\noutputDirectory: "
                    + run.resolve("agent").toAbsolutePath()
                    + "\n");
            command.add("-javaagent:" + agent + "=config=" + config);
          }
          command.addAll(
              List.of(
                  "-cp",
                  System.getProperty("java.class.path"),
                  "io.github.cheshmakzanoon.portfolio.threadlens.workloads.WorkloadMain",
                  workload,
                  "false",
                  Integer.toString(warmup),
                  Integer.toString(seconds),
                  mode,
                  output.toAbsolutePath().toString(),
                  recording.toAbsolutePath().toString()));
          Map<String, Object> item = new LinkedHashMap<>();
          item.put("id", id);
          item.put("pair", pair);
          item.put("mode", mode);
          item.put("workload", workload);
          item.put("command", command);
          item.put("started", Instant.now().toString());
          order.add(item);
          JSON.writerWithDefaultPrettyPrinter()
              .writeValue(directory.resolve("run-order.json").toFile(), order);
          Process process =
              new ProcessBuilder(command)
                  .redirectErrorStream(true)
                  .redirectOutput(run.resolve("process.log").toFile())
                  .start();
          try {
            ChildJvm.await(process, warmup + seconds + 60L, "workload " + id);
          } finally {
            item.put("exitCode", process.isAlive() ? null : process.exitValue());
            item.put("ended", Instant.now().toString());
            JSON.writerWithDefaultPrettyPrinter()
                .writeValue(directory.resolve("run-order.json").toFile(), order);
          }
          JsonNode observation = JSON.readTree(output.toFile());
          if (observation.path("warmupSeconds").asInt() != warmup
              || observation.path("actualMeasurementSeconds").asDouble() < seconds)
            throw new IllegalStateException("invalid benchmark duration");
          observations.get(mode).add(observation);
          // Preserve the producing child's Recording.getSettings() sidecar. Do not overwrite
          // actual producer settings with a declaration inferred in this parent JVM.
          if (!mode.equals("baseline")) {
            var settings = SettingsManifest.read(Path.of(recording + ".settings.json"));
            if (!"false".equals(settings.get("jdk.ThreadPark#stackTrace")))
              throw new IllegalStateException(
                  "workload park-stack setting differs from declared configuration");
            if (!"50/s".equals(settings.get("jdk.ObjectAllocationSample#throttle")))
              throw new IllegalStateException(
                  "workload allocation setting differs from declared configuration");
          }
          if (mode.equals("instrumented")) {
            ObserverTrialStatus.requireComplete(run.resolve("agent/observer-status.txt"));
            Path summary = run.resolve("agent/timing-summary.tsv");
            if (!Files.isRegularFile(summary)
                || !Files.isRegularFile(run.resolve("agent/timing-histograms.hlog"))
                || Files.readAllLines(run.resolve("agent/methods.tsv")).size() < 2
                || Files.readAllLines(summary).stream()
                        .skip(1)
                        .mapToLong(row -> Long.parseLong(row.split("\\t")[1]))
                        .sum()
                    == 0)
              throw new IllegalStateException(
                  "instrumented trial lacks actual transformed-method timing evidence");
          }
          JSON.writerWithDefaultPrettyPrinter()
              .writeValue(directory.resolve("run-order.json").toFile(), order);
        }
      }
      Map<String, Object> comparisons = new TreeMap<>();
      for (String mode : List.of("jfr", "instrumented")) {
        if (smoke) {
          comparisons.put(mode, Map.of("status", "SMOKE_ONLY", "pairs", pairs));
          continue;
        }
        double[] baselineThroughput = new double[pairs],
            observedThroughput = new double[pairs],
            baselineP99 = new double[pairs],
            observedP99 = new double[pairs];
        for (int pair = 0; pair < pairs; pair++) {
          baselineThroughput[pair] =
              observations.get("baseline").get(pair).path("throughputOpsPerSecond").asDouble();
          observedThroughput[pair] =
              observations.get(mode).get(pair).path("throughputOpsPerSecond").asDouble();
          baselineP99[pair] =
              observations.get("baseline").get(pair).path("latencyP99Nanos").asDouble();
          observedP99[pair] = observations.get(mode).get(pair).path("latencyP99Nanos").asDouble();
        }
        var throughput = ProfileComparator.paired(baselineThroughput, observedThroughput);
        var p99 = ProfileComparator.paired(baselineP99, observedP99);
        double tLimit = mode.equals("jfr") ? 5 : 10, lLimit = mode.equals("jfr") ? 10 : 15;
        boolean throughputPass = throughput.medianPercent() >= -tLimit;
        boolean latencyPass = p99.medianPercent() <= lLimit;
        withinAllGates &= throughputPass && latencyPass;
        comparisons.put(
            mode,
            Map.of(
                "throughputPercentChange",
                throughput,
                "p99PercentChange",
                p99,
                "throughputGatePass",
                throughputPass,
                "p99GatePass",
                latencyPass));
      }
      results.put(workload, comparisons);
      JSON.writerWithDefaultPrettyPrinter()
          .writeValue(directory.resolve("summary.json").toFile(), results);
    }
    environment.put("ended", Instant.now().toString());
    JSON.writerWithDefaultPrettyPrinter()
        .writeValue(directory.resolve("environment.json").toFile(), environment);
    // Publish all raw trials and summary before declaring a failed acceptance gate.
    if (!smoke && !withinAllGates)
      throw new IllegalStateException(
          "P08-A5 overhead acceptance failed; inspect summary.json and preserved raw trials");
  }

  private static String sha256(Path path) throws java.io.IOException {
    try {
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      try (var input = Files.newInputStream(path)) {
        byte[] bytes = new byte[8192];
        int count;
        while ((count = input.read(bytes)) != -1) digest.update(bytes, 0, count);
      }
      return java.util.HexFormat.of().formatHex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
