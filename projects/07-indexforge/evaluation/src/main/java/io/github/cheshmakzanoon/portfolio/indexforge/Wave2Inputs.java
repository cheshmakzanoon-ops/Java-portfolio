package io.github.cheshmakzanoon.portfolio.indexforge;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Development/validation reader filters the split before parsing query content or judgments. */
public final class Wave2Inputs {
  private Wave2Inputs() {}

  public record Header(String id, String split, String type, String text) {
    public Header {
      if (id == null
          || id.isBlank()
          || id.length() > 128
          || !Set.of("development", "validation").contains(split)
          || !Set.of("identifier", "prose").contains(type))
        throw new IllegalArgumentException("wave2 header");
      QueryAst.parse(text);
    }
  }

  public static List<Header> headers(Path fixtures, Set<String> wanted) throws IOException {
    if (wanted.isEmpty() || !Set.of("development", "validation").containsAll(wanted))
      throw new IllegalArgumentException("only development/validation inputs are permitted");
    return readHeaders(fixtures, wanted);
  }

  private static List<Header> readHeaders(Path fixtures, Set<String> wanted) throws IOException {
    if (Files.size(fixtures.resolve("judgments.tsv")) > 2_097_152)
      throw new IOException("judgment byte quota");
    List<Header> rows = new ArrayList<>();
    try (var input = Files.newBufferedReader(fixtures.resolve("judgments.tsv"))) {
      String line;
      while ((line = input.readLine()) != null) {
        String[] prefix = line.split("\t", 3);
        if (prefix.length < 2 || !wanted.contains(prefix[1])) continue;
        String[] fields = line.split("\t", 5);
        if (fields.length != 5 || rows.size() >= 60)
          throw new IOException("wave2 query header quota");
        try {
          rows.add(new Header(fields[0], fields[1], fields[2], fields[3]));
        } catch (IllegalArgumentException invalid) {
          throw new IOException("invalid wave2 header", invalid);
        }
      }
    }
    if (rows.stream().map(Header::id).distinct().count() != rows.size())
      throw new IOException("duplicate query ID");
    return List.copyOf(rows);
  }

  public static Map<String, Map<String, Integer>> labels(Path fixtures, List<Header> headers)
      throws IOException {
    for (var header : headers)
      if (!header.split().equals("development"))
        throw new IllegalArgumentException("development labels only");
    return evaluationLabels(fixtures, headers);
  }

  static Map<String, Map<String, Integer>> evaluationLabels(Path fixtures, List<Header> headers)
      throws IOException {
    Map<String, Header> wanted = new HashMap<>();
    for (var header : headers)
      if (wanted.put(header.id(), header) != null)
        throw new IllegalArgumentException("duplicate query header");
    Map<String, Map<String, Integer>> result = new TreeMap<>();
    try (var input = Files.newBufferedReader(fixtures.resolve("judgments.tsv"))) {
      String line;
      while ((line = input.readLine()) != null) {
        String[] prefix = line.split("\t", 3);
        if (prefix.length < 2
            || !wanted.containsKey(prefix[0])
            || !wanted.get(prefix[0]).split().equals(prefix[1])) continue;
        String[] fields = line.split("\t", 5);
        if (fields.length != 5) throw new IOException("wave2 development labels");
        Map<String, Integer> grades = new TreeMap<>();
        try {
          for (String item : fields[4].split(",")) {
            int colon = item.lastIndexOf(':');
            if (colon < 1) throw new IllegalArgumentException("grade record");
            int grade = Integer.parseInt(item.substring(colon + 1));
            if (grade < 1 || grade > 3 || grades.put(item.substring(0, colon), grade) != null)
              throw new IllegalArgumentException("grade bounds/duplicate");
          }
        } catch (IllegalArgumentException invalid) {
          throw new IOException("invalid development grade", invalid);
        }
        if (result.put(fields[0], Map.copyOf(grades)) != null)
          throw new IOException("duplicate development labels");
      }
    }
    if (!result.keySet().equals(wanted.keySet()))
      throw new IOException("missing development labels");
    return Collections.unmodifiableMap(result);
  }

  public static String judgmentGroup(Map<String, Integer> grades) {
    StringJoiner out = new StringJoiner(",", "{", "}");
    for (var entry : new TreeMap<>(grades).entrySet()) {
      if (entry.getValue() < 1 || entry.getValue() > 3)
        throw new IllegalArgumentException("grade bounds");
      out.add(quote(entry.getKey()) + ":" + entry.getValue());
    }
    return Digests.sha256(out.toString());
  }

  static String quote(String text) {
    StringBuilder out = new StringBuilder("\"");
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 32 || c >= 128) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          else out.append(c);
        }
      }
    }
    return out.append('"').toString();
  }
}
