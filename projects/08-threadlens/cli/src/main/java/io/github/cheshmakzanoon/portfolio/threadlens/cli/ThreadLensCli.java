package io.github.cheshmakzanoon.portfolio.threadlens.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cheshmakzanoon.portfolio.threadlens.jfr.*;
import io.github.cheshmakzanoon.portfolio.threadlens.report.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

public final class ThreadLensCli {
  private ThreadLensCli() {}

  public static void main(String[] args) throws Exception {
    if (args.length == 0)
      throw new IllegalArgumentException(
          "capabilities | analyze input.jfr output-directory | compare baseline.json candidate.json output.html | benchmark output-directory agent.jar [--smoke]");
    switch (args[0]) {
      case "capabilities" ->
          System.out.println(
              new ObjectMapper()
                  .writerWithDefaultPrettyPrinter()
                  .writeValueAsString(EventCapabilities.discover()));
      case "analyze" -> {
        if (args.length != 3 && !(args.length == 5 && args[3].equals("--timings")))
          throw new IllegalArgumentException(
              "analyze input.jfr output-directory [--timings agent-directory]");
        Path input = Path.of(args[1]), output = Path.of(args[2]);
        Files.createDirectories(output);
        var imported = new RecordingImporter().read(input);
        var profile = imported.profile();
        Map<String, Integer> activeLimits = Map.of();
        if (args.length == 5) {
          var timingImporter =
              new io.github.cheshmakzanoon.portfolio.threadlens.report.TimingImporter();
          var timingResult = timingImporter.read(Path.of(args[4]));
          profile = timingImporter.merge(profile, timingResult);
          activeLimits = timingResult.activeLimits();
        }
        Map<String, Object> capabilities = new TreeMap<>();
        imported.capabilities().forEach(capabilities::put);
        Map<String, Long> losses = new TreeMap<>(profile.counters());
        losses.put("unsupportedFields", imported.unsupportedFields());
        losses.put("truncatedStacks", imported.truncatedStacks());
        losses.put("clippedStrings", imported.clippedStrings());
        // Event schemas do not prove which settings the producer used. A manifest supplies declared
        // settings.
        Path manifest = input.resolveSibling(input.getFileName() + ".settings.json");
        Map<String, String> settings =
            Files.exists(manifest)
                ? SettingsManifest.read(manifest)
                : Map.of(
                    "producerSettings", "unknown; no sibling .settings.json manifest supplied");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.newInputStream(input)) {
          byte[] block = new byte[8192];
          int n;
          while ((n = stream.read(block)) >= 0) digest.update(block, 0, n);
        }
        var report =
            ReportDocument.create(
                HexFormat.of().formatHex(digest.digest()),
                profile,
                settings,
                capabilities,
                losses,
                activeLimits);
        ReportWriter.json(output.resolve("report.json"), report);
        ReportWriter.html(output.resolve("report.html"), report);
        System.out.println(output.resolve("report.html").toAbsolutePath());
      }
      case "compare" -> {
        if (args.length != 4)
          throw new IllegalArgumentException("compare baseline.json candidate.json output.html");
        ReportWriter.comparison(
            Path.of(args[3]),
            ReportWriter.read(Path.of(args[1])),
            ReportWriter.read(Path.of(args[2])));
        System.out.println(Path.of(args[3]).toAbsolutePath());
      }
      case "benchmark" -> {
        if (args.length < 3 || args.length > 4 || args.length == 4 && !args[3].equals("--smoke"))
          throw new IllegalArgumentException("benchmark output-directory agent.jar [--smoke]");
        BenchmarkRunner.run(
            Path.of(args[1]), Path.of(args[2]), args.length == 4 && args[3].equals("--smoke"));
      }
      default -> throw new IllegalArgumentException("unknown command");
    }
  }
}
