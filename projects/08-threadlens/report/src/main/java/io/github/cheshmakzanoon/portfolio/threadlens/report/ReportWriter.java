package io.github.cheshmakzanoon.portfolio.threadlens.report;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.cheshmakzanoon.portfolio.threadlens.analysis.ProfileComparator;
import io.github.cheshmakzanoon.portfolio.threadlens.analysis.StackAggregator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Offline output; no scripts, remote resources, telemetry or unsanitized HTML. */
public final class ReportWriter {
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .build();

  private ReportWriter() {}

  public static void json(Path path, ReportDocument report) throws IOException {
    Files.writeString(
        path,
        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report),
        StandardCharsets.UTF_8);
  }

  public static ReportDocument read(Path path) throws IOException {
    return JSON.readValue(path.toFile(), ReportDocument.class);
  }

  public static String escape(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }

  private static String start(String title) {
    return "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'\"><title>"
        + escape(title)
        + "</title><style>body{font:16px system-ui;max-width:1100px;margin:auto;padding:2rem;background:#fafafa;color:#17222f}table{border-collapse:collapse;width:100%;margin:1rem 0}td,th{border:1px solid #bcc;padding:.4rem;text-align:left}summary{cursor:pointer;background:#ffdba6;margin:2px;padding:.2rem}details{margin-left:1rem}code{overflow-wrap:anywhere}caption{text-align:left;font-weight:bold}nav a{margin-right:1rem}meter{width:100%}</style><body><h1>"
        + escape(title)
        + "</h1>";
  }

  public static void html(Path path, ReportDocument report) throws IOException {
    StringBuilder out = new StringBuilder(start("ThreadLens " + report.recordingId()));
    out.append(
        "<nav><a href=\"#flame\">Flame stacks</a><a href=\"#timeline\">Timeline</a><a href=\"#allocation\">Allocations</a><a href=\"#contention\">Contention</a><a href=\"#limits\">Coverage and limits</a></nav>");
    out.append("<p>")
        .append(escape(report.missingEventStatus()))
        .append("</p><p>Observation window (epoch nanoseconds): ")
        .append(report.startNanos())
        .append("–")
        .append(report.endNanos())
        .append(".</p>");
    out.append(
            "<h2 id=\"flame\">Navigable execution stacks</h2><p>Units: samples. Width denominator: ")
        .append(report.sampleCount())
        .append(
            " observed execution samples. Inclusive prefixes include callees; self is the sampled leaf. These are sampled evidence, never exact call counts or CPU-time accounting.</p>");
    flame(out, report.profile().flame(), Math.max(1, report.sampleCount()));
    out.append(
        "<table><caption>Sampled methods: inclusive and self counts</caption><tr><th scope=\"col\">Method</th><th scope=\"col\">Inclusive samples</th><th scope=\"col\">Self samples</th></tr>");
    report.profile().methods().entrySet().stream()
        .sorted(
            Comparator.<java.util.Map.Entry<String, StackAggregator.MethodSamples>>comparingLong(
                    e -> e.getValue().self())
                .reversed()
                .thenComparing(java.util.Map.Entry::getKey))
        .forEach(
            e ->
                out.append("<tr><td><code>")
                    .append(escape(e.getKey()))
                    .append("</code></td><td>")
                    .append(e.getValue().inclusive())
                    .append("</td><td>")
                    .append(e.getValue().self())
                    .append("</td></tr>"));
    out.append("</table>");
    out.append(
        "<h2 id=\"timeline\">Event-time timeline</h2><p>Fixed one-second buckets; durations assigned by event start. Unseen or incomplete windows cannot establish inactivity. All tables share the observation window and settings below.</p><table><caption>Per-window observed totals</caption><tr><th>Start epoch ns</th><th>Samples</th><th>Weighted allocation bytes</th><th>GC ns</th><th>Monitor ns</th><th>Park ns</th><th>Incomplete</th></tr>");
    report
        .profile()
        .windows()
        .values()
        .forEach(
            w ->
                out.append("<tr><td>")
                    .append(w.startNanos())
                    .append("</td><td>")
                    .append(w.samples())
                    .append("</td><td>")
                    .append(w.allocatedBytes())
                    .append("</td><td>")
                    .append(w.gcNanos())
                    .append("</td><td>")
                    .append(w.contentionNanos())
                    .append("</td><td>")
                    .append(w.parkNanos())
                    .append("</td><td>")
                    .append(w.incomplete())
                    .append("</td></tr>"));
    out.append("</table>");
    out.append(
        "<h2 id=\"allocation\">Weighted sampled allocations</h2><p>Units: observed weighted bytes from jdk.ObjectAllocationSample.weight. Denominator: events enabled by the declared throttle; sampling-dependent coverage, no exact object counts.</p><table><caption>Allocation class</caption><tr><th>Class</th><th>Events</th><th>Weighted bytes</th></tr>");
    report
        .profile()
        .allocations()
        .forEach(
            (k, v) ->
                out.append("<tr><td>")
                    .append(escape(k))
                    .append("</td><td>")
                    .append(v.events())
                    .append("</td><td>")
                    .append(v.observedWeightedBytes())
                    .append("</td></tr>"));
    out.append(
        "</table><h2 id=\"contention\">Observed monitor contention and parking</h2><p>Units: nanoseconds of observed event duration, threshold declared in settings (unknown without a manifest); totals may overlap across threads. The tool cannot reconstruct every wait or attribute virtual-thread CPU sampling reliably.</p>");
    timingTable(out, "Java monitor-enter", report.profile().monitors());
    timingTable(out, "Thread park", report.profile().parks());
    timingTable(
        out,
        "Selected method timings (separate from sampling proportions)",
        report.profile().timings());
    out.append(
            "<h2 id=\"limits\">Coverage, settings and missing data</h2><p>JDK/OS metadata below describe the analysis runtime. Original producer identity is available only when a recording manifest accompanies the file; importing another JVM does not prove it ran on this platform.</p><pre>")
        .append(
            escape(
                JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(
                        java.util.Map.of(
                            "runtime",
                            report.jdkVendor()
                                + " "
                                + report.jdkVersion()
                                + " "
                                + report.os()
                                + " "
                                + report.architecture(),
                            "settings",
                            report.eventSettings(),
                            "capabilities",
                            report.supportedCapabilities(),
                            "lossCounters",
                            report.lossCounters(),
                            "aggregationCounters",
                            report.profile().counters(),
                            "absoluteLimits",
                            report.aggregationLimits(),
                            "activeCollectionLimits",
                            report.activeCollectionLimits()))))
        .append("</pre></body></html>");
    Files.writeString(path, out, StandardCharsets.UTF_8);
  }

  private static void flame(StringBuilder out, StackAggregator.Flame node, long denominator) {
    out.append("<details open><summary>")
        .append(escape(node.frame()))
        .append(" — inclusive ")
        .append(node.inclusive())
        .append(", self ")
        .append(node.self())
        .append("<meter min=\"0\" max=\"")
        .append(denominator)
        .append("\" value=\"")
        .append(node.inclusive())
        .append("\" aria-label=\"inclusive sample proportion\"></meter></summary>");
    for (var child : node.children()) flame(out, child, denominator);
    out.append("</details>");
  }

  private static void timingTable(
      StringBuilder out,
      String title,
      java.util.Map<
              String,
              io.github.cheshmakzanoon.portfolio.threadlens.analysis.ContentionSummary.Timing>
          data) {
    out.append("<table><caption>")
        .append(escape(title))
        .append(
            "; histogram ns (2 significant digits)</caption><tr><th>Subject</th><th>Observed events</th><th>Total ns</th><th>p50 ns</th><th>p95 ns</th><th>p99 ns</th></tr>");
    data.forEach(
        (k, v) ->
            out.append("<tr><td>")
                .append(escape(k))
                .append("</td><td>")
                .append(v.events())
                .append("</td><td>")
                .append(v.totalNanos())
                .append("</td><td>")
                .append(v.p50Nanos())
                .append("</td><td>")
                .append(v.p95Nanos())
                .append("</td><td>")
                .append(v.p99Nanos())
                .append("</td></tr>"));
    out.append("</table>");
  }

  public static void comparison(Path path, ReportDocument baseline, ReportDocument candidate)
      throws IOException {
    if (baseline.eventSettings().containsKey("producerSettings")
        || candidate.eventSettings().containsKey("producerSettings"))
      throw new IllegalArgumentException(
          "producer settings unknown; supply matching recording manifests before comparison");
    if (!baseline.eventSettings().equals(candidate.eventSettings()))
      throw new IllegalArgumentException(
          "recording settings differ; comparison would be misleading");
    StringBuilder out = new StringBuilder(start("ThreadLens differential report"));
    out.append("<p>Baseline: ")
        .append(escape(baseline.recordingId()))
        .append("; candidate: ")
        .append(escape(candidate.recordingId()))
        .append(
            ".</p><p>Units: percentage points of self execution samples. Each run uses its own total observed samples as denominator; null means missing evidence. Different durations and workload rates confound absolute counts. Allocation weights and contention are observed coverage, not universal attribution.</p><table><caption>Self-sample proportion deltas</caption><tr><th>Method</th><th>Baseline samples</th><th>Candidate samples</th><th>Percentage-point delta</th></tr>");
    for (var d : ProfileComparator.compare(baseline.profile(), candidate.profile()))
      out.append("<tr><td>")
          .append(escape(d.method()))
          .append("</td><td>")
          .append(d.baselineSamples())
          .append("</td><td>")
          .append(d.candidateSamples())
          .append("</td><td>")
          .append(d.percentagePointDelta() == null ? "missing" : d.percentagePointDelta())
          .append("</td></tr>");
    out.append("</table><h2>Allocation baseline → candidate</h2><pre>")
        .append(escape(JSON.writeValueAsString(baseline.profile().allocations())))
        .append("\n→\n")
        .append(escape(JSON.writeValueAsString(candidate.profile().allocations())))
        .append("</pre><h2>Monitor contention baseline → candidate</h2><pre>")
        .append(escape(JSON.writeValueAsString(baseline.profile().monitors())))
        .append("\n→\n")
        .append(escape(JSON.writeValueAsString(candidate.profile().monitors())))
        .append("</pre><h2>Settings, windows and loss</h2><pre>")
        .append(
            escape(
                JSON.writeValueAsString(
                    java.util.Map.of(
                        "settings",
                        baseline.eventSettings(),
                        "baselineWindow",
                        List.of(baseline.startNanos(), baseline.endNanos()),
                        "candidateWindow",
                        List.of(candidate.startNanos(), candidate.endNanos()),
                        "baselineLoss",
                        baseline.lossCounters(),
                        "candidateLoss",
                        candidate.lossCounters()))))
        .append("</pre></body></html>");
    Files.writeString(path, out, StandardCharsets.UTF_8);
  }
}
