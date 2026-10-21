package io.github.cheshmakzanoon.portfolio.stacks;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Reads only the requested split's query and labels; other split payloads remain uninterpreted. */
final class EvaluationJudgments {
  private static final Map<String, Integer> COUNTS =
      Map.of("development", 40, "validation", 20, "held-out", 20);

  private EvaluationJudgments() {}

  static List<RelevanceEvaluator.Judged> read(Path fixtures, Set<String> wanted)
      throws IOException {
    if (wanted.isEmpty() || !COUNTS.keySet().containsAll(wanted))
      throw new IllegalArgumentException("evaluation splits");
    Path path = fixtures.resolve("judgments.tsv");
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 2_097_152)
      throw new IOException("judgment regular file/byte quota");
    List<RelevanceEvaluator.Judged> result = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    Map<String, Integer> counts = new HashMap<>();
    try (var input = Files.newBufferedReader(path)) {
      String line;
      while ((line = input.readLine()) != null) {
        String[] prefix = line.split("\t", 3);
        if (prefix.length < 2 || !wanted.contains(prefix[1])) continue;
        String[] fields = line.split("\t", -1);
        if (fields.length != 5
            || fields[0].isBlank()
            || fields[0].length() > 128
            || !ids.add(fields[0])
            || !Set.of("identifier", "prose").contains(fields[2]))
          throw new IOException("evaluation query identity");
        try {
          QueryAst.parse(fields[3]);
          Map<String, Integer> grades = new TreeMap<>();
          for (String item : fields[4].split(",", -1)) {
            int colon = item.lastIndexOf(':');
            if (colon < 1) throw new IllegalArgumentException("grade syntax");
            String key = item.substring(0, colon);
            int grade = Integer.parseInt(item.substring(colon + 1));
            if (!SnapshotManifest.normalize(key).equals(key)
                || grade < 1
                || grade > 3
                || grades.putIfAbsent(key, grade) != null)
              throw new IllegalArgumentException("grade path/bounds/duplicate");
          }
          result.add(
              new RelevanceEvaluator.Judged(
                  fields[0], fields[1], fields[2], fields[3], Map.copyOf(grades)));
        } catch (IllegalArgumentException invalid) {
          throw new IOException("invalid evaluation query/grades", invalid);
        }
        if (counts.merge(fields[1], 1, Integer::sum) > COUNTS.get(fields[1]))
          throw new IOException("evaluation split quota");
      }
    }
    for (String split : wanted)
      if (!Objects.equals(counts.get(split), COUNTS.get(split)))
        throw new IOException("evaluation split mismatch");
    return List.copyOf(result);
  }
}
