package io.github.cheshmakzanoon.portfolio.latearrival;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.cheshmakzanoon.portfolio.latearrival.Explainer.Report;
import io.github.cheshmakzanoon.portfolio.latearrival.Explainer.Row;
import io.github.cheshmakzanoon.portfolio.latearrival.Explainer.Step;
import io.github.cheshmakzanoon.portfolio.latearrival.Explainer.Verdict;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;

/**
 * {@code explain events.jsonl [--run ID] [--partitions N] [--now MILLIS] [--format text|json]}:
 * what the event-time engine does with a file of v1 records, without Kafka or PostgreSQL. Exit 0
 * after a replay, 2 for unusable input.
 */
public final class ExplainMain {
  static final long MAXIMUM_BYTES = 16L * 1024 * 1024;
  static final String USAGE =
      "explain events.jsonl [--run ID] [--partitions N] [--now MILLIS] [--format text|json]";
  private static final ObjectMapper JSON =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  private ExplainMain() {}

  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  public static int run(String[] args, PrintStream out, PrintStream err) {
    try {
      Path file = null;
      String run = null, format = "text";
      int partitions = 1;
      Long now = null;
      for (int i = 0; i < args.length; i++) {
        switch (args[i]) {
          case "--run" -> run = value(args, ++i);
          case "--partitions" -> partitions = Integer.parseInt(value(args, ++i));
          case "--now" -> now = Long.parseLong(value(args, ++i));
          case "--format" -> format = value(args, ++i);
          default -> {
            if (args[i].startsWith("--") || file != null) throw new IllegalArgumentException(USAGE);
            file = Path.of(args[i]);
          }
        }
      }
      if (file == null) throw new IllegalArgumentException(USAGE);
      if (!format.equals("text") && !format.equals("json"))
        throw new IllegalArgumentException("format must be text or json");
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
        throw new IOException("not a regular file: " + file.getFileName());
      if (Files.size(file) > MAXIMUM_BYTES)
        throw new IOException(file.getFileName() + " exceeds 16 MiB");
      Report report =
          Explainer.replay(Files.readAllLines(file, StandardCharsets.UTF_8), run, partitions, now);
      if (format.equals("json")) out.println(JSON.writeValueAsString(json(report)));
      else out.print(text(report));
      return 0;
    } catch (IOException | IllegalArgumentException failure) {
      err.println("explain: " + failure.getMessage());
      return 2;
    }
  }

  private static String value(String[] args, int index) {
    if (index >= args.length) throw new IllegalArgumentException(USAGE);
    return args[index];
  }

  static String text(Report report) {
    StringBuilder out =
        new StringBuilder(
            String.format(
                "Replay of run %s: %d record(s) on %d partition(s), processed at %s%n",
                report.runId(),
                report.steps().size(),
                report.partitions(),
                Explainer.instant(report.nowMillis())));
    for (Step step : report.steps()) {
      out.append(
          String.format(
              "  line %-4d p%d@%-4d %-10s %-24s %s  %s%n",
              step.line(),
              step.partition(),
              step.offset(),
              step.verdict(),
              step.sensor() == null ? "-" : step.sensor(),
              step.eventTimeMillis() == null
                  ? ""
                  : "t=" + Explainer.instant(step.eventTimeMillis()),
              step.detail()));
      if (step.watermarkAfter() != step.watermarkBefore())
        out.append("      watermark ")
            .append(Explainer.instant(step.watermarkBefore()))
            .append(" -> ")
            .append(Explainer.instant(step.watermarkAfter()))
            .append('\n');
      for (String late : step.lateForFinalized())
        out.append("      too late for finalized window ").append(readable(late)).append('\n');
      for (String published : step.published())
        out.append("      published ").append(readable(published)).append('\n');
    }
    out.append(
        String.format(
            "%nSummary: %d accepted, %d duplicate, %d rejected; watermark %s%n",
            report.count(Verdict.ACCEPTED),
            report.count(Verdict.DUPLICATE),
            report.count(Verdict.REJECTED),
            Explainer.instant(report.watermark())));
    report
        .maxEventTimeByPartition()
        .forEach(
            (partition, max) -> {
              if (max == Long.MIN_VALUE)
                out.append("  partition ")
                    .append(partition)
                    .append(
                        " has seen no event, which holds the watermark back for every partition\n");
            });
    out.append("\nWindows\n");
    if (report.windows().isEmpty()) out.append("  none\n");
    for (Row row : report.windows())
      out.append(
          String.format(
              "  %s:%s %-8s [%s, %s)  count=%d sum=%d min=%s max=%s  rev %d %s%s%n",
              row.tenant(),
              row.sensor(),
              row.type(),
              Explainer.instant(row.start()),
              Explainer.instant(row.end()),
              row.count(),
              row.sum(),
              row.count() == 0 ? "-" : Long.toString(row.minimum()),
              row.count() == 0 ? "-" : Long.toString(row.maximum()),
              row.revision(),
              row.status(),
              row.finalizesAtWatermark() == null
                  ? ""
                  : (row.publishesAtWatermark() == null
                          ? " (final once the watermark reaches "
                          : " (published once the watermark reaches "
                              + Explainer.instant(row.publishesAtWatermark())
                              + ", final at ")
                      + Explainer.instant(row.finalizesAtWatermark())
                      + ")"));
    return out.toString();
  }

  /** {@code tenant:sensor:TYPE:millis [rev N ...]} with the window start as an instant. */
  static String readable(String window) {
    int space = window.indexOf(' ');
    String id = space < 0 ? window : window.substring(0, space);
    int colon = id.lastIndexOf(':');
    try {
      String start = Explainer.instant(Long.parseLong(id.substring(colon + 1)));
      return id.substring(0, colon + 1) + start + (space < 0 ? "" : window.substring(space));
    } catch (RuntimeException notAWindow) {
      return window;
    }
  }

  static Map<String, Object> json(Report report) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("runId", report.runId());
    out.put("partitions", report.partitions());
    out.put("nowMillis", report.nowMillis());
    out.put("watermarkMillis", report.watermark() == Long.MIN_VALUE ? null : report.watermark());
    out.put("duplicates", report.duplicates());
    List<Map<String, Object>> steps = new ArrayList<>();
    for (Step step : report.steps()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("line", step.line());
      item.put("partition", step.partition());
      item.put("offset", step.offset());
      item.put("eventId", step.eventId());
      item.put("sensor", step.sensor());
      item.put("eventTimeMillis", step.eventTimeMillis());
      item.put("verdict", step.verdict());
      item.put("detail", step.detail());
      item.put(
          "watermarkBeforeMillis",
          step.watermarkBefore() == Long.MIN_VALUE ? null : step.watermarkBefore());
      item.put(
          "watermarkAfterMillis",
          step.watermarkAfter() == Long.MIN_VALUE ? null : step.watermarkAfter());
      item.put("lateForFinalized", step.lateForFinalized());
      item.put("published", step.published());
      steps.add(item);
    }
    out.put("steps", steps);
    out.put("windows", report.windows());
    return out;
  }
}
