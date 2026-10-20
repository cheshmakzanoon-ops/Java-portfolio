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
      JsonMapper.builder(
              com.fasterxml.jackson.core.JsonFactory.builder()
                  .streamReadConstraints(
                      com.fasterxml.jackson.core.StreamReadConstraints.builder()
                          .maxNestingDepth(300)
                          .maxStringLength(65536)
                          .maxNumberLength(20)
                          .maxDocumentLength(32L * 1024 * 1024)
                          .build())
                  .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .build())
          .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .build();

  private ReportWriter() {}

  public static void json(Path path, ReportDocument report) throws IOException {
    ReportValidator.validate(report);
    Files.writeString(
        path,
        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report),
        StandardCharsets.UTF_8);
  }

  public static ReportDocument read(Path path) throws IOException {
    if (Files.size(path) > 32L * 1024 * 1024) throw new IOException("report exceeds 32 MiB");
    ReportDocument document;
    try (var input = Files.newInputStream(path)) {
      document = JSON.readValue(input, ReportDocument.class);
    }
    ReportValidator.validate(document);
    return document;
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
    return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'\"><title>"
        + escape(title)
        + """
          </title><style>
          :root{color-scheme:light;--ink:#172b40;--muted:#526478;--line:#d9e2ec;--accent:#2456a6}
          *{box-sizing:border-box}html{scroll-padding-top:5rem}body{margin:0;background:#f3f6fa;color:var(--ink);font:16px/1.6 system-ui,sans-serif}
          main{max-width:1200px;margin:2rem auto;padding:2.5rem;background:white;border:1px solid var(--line);border-radius:12px}
          header{border-bottom:1px solid var(--line);margin-bottom:1.5rem;padding-bottom:1.5rem}.eyebrow{margin:0;color:var(--accent);font-size:.8rem;font-weight:700;letter-spacing:.1em;text-transform:uppercase}
          h1{font-size:clamp(1.6rem,4vw,2.3rem);line-height:1.2;margin:.6rem 0 0;overflow-wrap:anywhere}h2{font-size:1.3rem;margin-top:2.5rem}p{max-width:85ch;color:var(--muted)}
          nav{position:sticky;top:0;z-index:1;display:flex;flex-wrap:wrap;gap:.5rem;padding:.75rem;background:#f3f6fa;border:1px solid var(--line);border-radius:8px}nav a{padding:.35rem .7rem;text-decoration:none;border-radius:4px}nav a:hover{background:#e0eafb}
          a{color:var(--accent)}:focus-visible{outline:3px solid #2456a6;outline-offset:3px}.skip-link{position:absolute;left:1rem;top:-5rem;padding:.6rem 1rem;background:white;z-index:2}.skip-link:focus{top:1rem}
          .table-scroll th:not(:first-child){min-width:6rem}.table-hint{font-size:.875rem}.table-scroll{max-width:100%;overflow-x:auto;margin:1rem 0 2rem}.flame-tree{max-height:48rem;max-width:100%;overflow:auto;border:1px solid var(--line);border-radius:8px;padding:.5rem}table{min-width:40rem;border-collapse:collapse;width:100%;margin:1rem 0 2rem;font-size:.9rem;font-variant-numeric:tabular-nums}td,th{border:1px solid var(--line);padding:.65rem;text-align:left;overflow-wrap:anywhere}th{background:#edf2f8}tr:nth-child(even) td{background:#fafbfd}caption{text-align:left;font-weight:650;padding:.5rem 0}
          summary{cursor:pointer;background:#edf2f8;border:1px solid var(--line);border-radius:4px;margin:3px 0;padding:.4rem .6rem;overflow-wrap:anywhere}details{margin-left:1rem}meter{display:block;width:100%;height:.6rem;accent-color:var(--accent)}
          code,pre{font: .85rem/1.6 ui-monospace,monospace;overflow-wrap:anywhere}pre{background:#f3f6fa;border:1px solid var(--line);border-radius:8px;padding:1rem;white-space:pre-wrap}
          @media(max-width:700px){main{margin:0;padding:1rem;border:0;border-radius:0}nav{position:static}td,th{padding:.35rem;font-size:.8rem}details{margin-left:.5rem}}
          @media print{body,main{background:white}main{margin:0;padding:0;border:0}nav,.skip-link{display:none}h2{break-after:avoid}tr{break-inside:avoid}}
          </style></head><body><a class="skip-link" href="#report-main">Skip to report</a><main id="report-main" tabindex="-1"><header><p class="eyebrow">ThreadLens / JVM diagnostics</p><h1>
          """
        + escape(title)
        + "</h1></header>";
  }

  public static void html(Path path, ReportDocument report) throws IOException {
    ReportValidator.validate(report);
    StringBuilder out = new StringBuilder(start("ThreadLens " + report.recordingId()));
    out.append(
        "<nav><a href=\"#flame\">Flame stacks</a><a href=\"#timeline\">Timeline</a><a href=\"#allocation\">Allocations</a><a href=\"#contention\">Contention</a><a href=\"#limits\">Coverage and limits</a></nav>");
    out.append(
        "<p class=\"table-hint\">Scroll wide tables horizontally to inspect every column.</p>");
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
    out.append(
        "<div class=\"flame-tree\" role=\"region\" aria-label=\"Execution stack tree\" tabindex=\"0\">");
    flame(out, report.profile().flame(), Math.max(1, report.sampleCount()));
    out.append("</div>");
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
        "<h2 id=\"timeline\">Event-time timeline</h2><p>Fixed one-second buckets; durations assigned by event start. Unseen or incomplete windows cannot establish inactivity. Sampled-event tables use the recording's observation window and declared settings. Selected-method timing artifacts have a separate collection interval.</p><table><caption>Per-window observed totals</caption><tr><th>Start epoch ns</th><th>Samples</th><th>Weighted allocation bytes</th><th>GC ns</th><th>Monitor ns</th><th>Park ns</th><th>Incomplete</th></tr>");
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
    out.append(
        "<p>Selected-method timing counts come from the supplied agent artifacts. Their collection interval is not aligned to this recording; do not divide them by its duration.</p>");
    timingTable(
        out,
        "Selected method timings (separate from sampling proportions)",
        report.profile().timings());
    out.append(
            "<h2 id=\"limits\">Coverage, settings and missing data</h2><p>JDK/OS metadata below describe the analysis runtime. Producer platform identity is unavailable in this report. A sibling manifest declares event settings; it does not establish the producer platform.</p><pre>")
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
        .append("</pre></main></body></html>");
    writeHtml(path, out);
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

  private static void writeHtml(Path path, StringBuilder content) throws IOException {
    String html =
        content
            .toString()
            .replace(
                "<table>",
                "<div class=\"table-scroll\" role=\"region\" tabindex=\"0\" aria-label=\"Scrollable report table\"><table>")
            .replace("</table>", "</table></div>")
            .replace("<th>", "<th scope=\"col\">");
    Files.writeString(path, html, StandardCharsets.UTF_8);
  }

  public static void comparison(Path path, ReportDocument baseline, ReportDocument candidate)
      throws IOException {
    ReportValidator.validate(baseline);
    ReportValidator.validate(candidate);
    if (baseline.eventSettings().containsKey("producerSettings")
        || candidate.eventSettings().containsKey("producerSettings"))
      throw new IllegalArgumentException(
          "producer settings unknown; supply matching recording manifests before comparison");
    if (!baseline.eventSettings().equals(candidate.eventSettings()))
      throw new IllegalArgumentException(
          "recording settings differ; comparison would be misleading");
    StringBuilder out = new StringBuilder(start("ThreadLens differential report"));
    out.append(
        "<nav><a href=\"#samples\">Sample changes</a><a href=\"#allocation\">Allocations</a><a href=\"#contention\">Contention</a><a href=\"#limits\">Coverage and settings</a></nav>");
    out.append(
        "<p class=\"table-hint\">Scroll wide tables horizontally to inspect every column.</p>");
    out.append("<p>Baseline: ")
        .append(escape(baseline.recordingId()))
        .append("; candidate: ")
        .append(escape(candidate.recordingId()))
        .append(
            ".</p><p>Units: percentage points of self execution samples. Each run uses its own total observed samples as denominator; null means missing evidence. Different durations and workload rates confound absolute counts. Allocation weights and contention are observed coverage, not universal attribution.</p><h2 id=\"samples\">Self-sample changes</h2><table><caption>Self-sample proportion deltas</caption><tr><th>Method</th><th>Baseline samples</th><th>Candidate samples</th><th>Percentage-point delta</th></tr>");
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
    out.append("</table><h2 id=\"allocation\">Allocation baseline → candidate</h2><pre>")
        .append(escape(JSON.writeValueAsString(baseline.profile().allocations())))
        .append("\n→\n")
        .append(escape(JSON.writeValueAsString(candidate.profile().allocations())))
        .append("</pre><h2 id=\"contention\">Monitor contention baseline → candidate</h2><pre>")
        .append(escape(JSON.writeValueAsString(baseline.profile().monitors())))
        .append("\n→\n")
        .append(escape(JSON.writeValueAsString(candidate.profile().monitors())))
        .append("</pre><h2 id=\"limits\">Settings, windows and loss</h2><pre>")
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
        .append("</pre></main></body></html>");
    writeHtml(path, out);
  }
}
