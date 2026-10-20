package io.github.cheshmakzanoon.portfolio.byteguard.report;

import io.github.cheshmakzanoon.portfolio.byteguard.bytecode.*;
import io.github.cheshmakzanoon.portfolio.byteguard.input.*;
import io.github.cheshmakzanoon.portfolio.byteguard.interprocedural.*;
import io.github.cheshmakzanoon.portfolio.byteguard.rules.*;
import java.util.*;

public final class FindingReporter {
  public List<Finding> findings(Analyzer.Result result) {
    TreeMap<String, Finding> findings = new TreeMap<>();
    for (var summary : result.analysis().summaries().values())
      for (var flow : summary.flows().values())
        for (var source : flow.taint().labels().entrySet()) {
          if (source.getKey().startsWith("P:") || (source.getValue() & flow.mask()) == 0) continue;
          if (!source.getKey().startsWith("S:")) continue;
          SourceIdentity identity = SourceIdentity.fromLabel(source.getKey());
          InstructionId sourceId = identity.instruction();
          String fingerprint = StableIdentity.finding(flow.rule(), sourceId, flow.sink());
          Finding finding =
              new Finding(
                  flow.rule(),
                  RuleModels.VERSION,
                  "warning",
                  flow.taint().unknown() ? "low" : "medium",
                  fingerprint,
                  identity.descriptor(),
                  flow.descriptor(),
                  sourceId,
                  flow.sink(),
                  flow.callPath(),
                  flow.truncated(),
                  location(sourceId, result),
                  location(flow.sink(), result),
                  List.of(
                      "Bounded modeled flows only; absence of findings is not a security guarantee.",
                      "Field-sensitive, object-insensitive heap and conservative array summaries."));
          findings.merge(
              fingerprint, finding, (a, b) -> a.callPath().size() >= b.callPath().size() ? a : b);
        }
    return List.copyOf(findings.values());
  }

  private Finding.Location location(InstructionId at, Analyzer.Result result) {
    int dot = at.method().indexOf('.');
    String owner = at.method().substring(0, dot);
    var c = result.classes().get(owner);
    MethodIr method = c.methods().get(at.method());
    String file = method.sourceFile;
    if (file != null) {
      file = file.replace('\\', '/');
      file = file.substring(file.lastIndexOf('/') + 1);
      if (file.isBlank() || file.equals(".") || file.equals("..")) file = null;
    }
    String uri =
        file == null
            ? owner + ".class"
            : (owner.contains("/") ? owner.substring(0, owner.lastIndexOf('/') + 1) : "") + file;
    uri = uri.replaceAll("^/+", "").replace("../", "__/");
    try {
      uri = new java.net.URI(null, null, "/" + uri, null).toASCIIString().substring(1);
    } catch (java.net.URISyntaxException malformed) {
      uri = "unknown.class";
    }
    if (uri.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) uri = "./" + uri;
    return new Finding.Location(uri, method.line(at.ordinal()), at.method(), at.ordinal());
  }
}
