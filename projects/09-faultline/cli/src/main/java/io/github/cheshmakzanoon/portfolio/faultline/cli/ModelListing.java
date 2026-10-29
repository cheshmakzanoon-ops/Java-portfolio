package io.github.cheshmakzanoon.portfolio.faultline.cli;

import io.github.cheshmakzanoon.portfolio.faultline.rules.RuleModels;
import java.util.*;

/** What a scan would trust and report, in the order an operator would audit it. */
final class ModelListing {
  private ModelListing() {}

  static String describe(RuleModels models, String origin) {
    SortedMap<String, RuleModels.Sink> sinks = models.sinks();
    SortedMap<String, Integer> sanitizers = models.sanitizers();
    StringBuilder out =
        new StringBuilder("Faultline rule model ")
            .append(RuleModels.VERSION)
            .append(" (")
            .append(origin)
            .append(")\n\nrules\n");
    List<String> byContext = new ArrayList<>(RuleModels.CONTEXTS.keySet());
    byContext.sort(Comparator.comparing(RuleModels.CONTEXTS::get));
    for (String rule : byContext) {
      int context = RuleModels.CONTEXTS.get(rule);
      long sinkCount = sinks.values().stream().filter(s -> s.id().equals(rule)).count();
      long cleaners = sanitizers.values().stream().filter(c -> c == context).count();
      out.append(String.format("  %-18s context %-3d", rule, context));
      if (rule.equals("BG-SHELL-COMMAND"))
        out.append(
            " modeled structurally: Runtime.exec(String[]) and ProcessBuilder.start with sh/bash -c");
      else out.append(String.format(" sinks %-3d", sinkCount));
      out.append(" sanitizers ").append(cleaners).append('\n');
    }
    out.append("\nsources (").append(models.sources().size()).append(")\n");
    for (String source : models.sources()) out.append("  ").append(source).append('\n');
    out.append("\nsinks (").append(sinks.size()).append(")\n");
    for (var sink : sinks.entrySet())
      out.append(
          String.format(
              "  %-18s arg %d  %s%n",
              sink.getValue().id(), sink.getValue().argument(), sink.getKey()));
    out.append("\nsanitizers (").append(sanitizers.size()).append(")\n");
    sanitizers.entrySet().stream()
        .sorted(
            Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
        .forEach(s -> out.append(String.format("  context %-3d  %s%n", s.getValue(), s.getKey())));
    out.append("\nSanitizers are trusted declarations by exact descriptor, not proofs.\n");
    return out.toString();
  }
}
