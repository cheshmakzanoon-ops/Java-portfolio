package io.github.cheshmakzanoon.portfolio.stacks;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Bounded label-blind feature export. Grades and judgment groups are deliberately absent. */
public record FeaturePacket(List<FeaturePacket.Query> queries) {
  public record Query(Wave2Inputs.Header header, List<SearchEngine.AuditCandidate> candidates) {
    public Query {
      if (!header.split().equals("development") || candidates.size() > 500)
        throw new IllegalArgumentException("feature export split/quota");
      candidates = List.copyOf(candidates);
      for (var candidate : candidates) {
        if (candidate.key() == null
            || candidate.key().isBlank()
            || candidate.key().length() > 4096
            || !SnapshotManifest.normalize(candidate.path()).equals(candidate.path()))
          throw new IllegalArgumentException("feature candidate identity/path");
      }
      if (candidates.stream().map(SearchEngine.AuditCandidate::key).distinct().count()
              != candidates.size()
          || candidates.stream().map(SearchEngine.AuditCandidate::path).distinct().count()
              != candidates.size())
        throw new IllegalArgumentException("duplicate feature candidate");
    }
  }

  public FeaturePacket {
    queries = List.copyOf(queries);
    if (queries.size() != 40 || queries.stream().map(q -> q.header().id()).distinct().count() != 40)
      throw new IllegalArgumentException("forty unique development exports required");
  }

  public void writeNew(Path path) throws IOException {
    long bytes = 0;
    try (var output =
        Files.newBufferedWriter(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      for (var query : queries) {
        String header =
            "Q\t"
                + encode(query.header().id())
                + "\t"
                + encode(query.header().type())
                + "\t"
                + encode(query.header().text())
                + "\n";
        output.write(header);
        bytes += header.length();
        int rank = 0;
        for (var candidate : query.candidates()) {
          StringBuilder line =
              new StringBuilder("D\t")
                  .append(encode(candidate.key()))
                  .append('\t')
                  .append(encode(candidate.path()))
                  .append('\t')
                  .append(rank++);
          for (double feature : candidate.features()) line.append('\t').append(feature);
          line.append('\n');
          bytes += line.length();
          if (bytes > 134_217_728) throw new IOException("feature export byte quota");
          output.write(line.toString());
        }
      }
    }
  }

  public static FeaturePacket read(Path path) throws IOException {
    if (Files.size(path) > 134_217_728) throw new IOException("feature export byte quota");
    List<Query> queries = new ArrayList<>();
    Wave2Inputs.Header header = null;
    List<SearchEngine.AuditCandidate> rows = new ArrayList<>();
    try (var input = Files.newBufferedReader(path)) {
      String line;
      while ((line = input.readLine()) != null) {
        if (line.length() > 65536) throw new IOException("feature record quota");
        String[] fields = line.split("\t", -1);
        if (fields[0].equals("Q") && fields.length == 4) {
          if (header != null) queries.add(new Query(header, rows));
          if (queries.size() >= 40) throw new IOException("feature query quota");
          header =
              new Wave2Inputs.Header(
                  decode(fields[1]), "development", decode(fields[2]), decode(fields[3]));
          rows = new ArrayList<>();
        } else if (fields[0].equals("D") && fields.length == 14 && header != null) {
          if (rows.size() >= 500 || Integer.parseInt(fields[3]) != rows.size())
            throw new IOException("feature rank/quota");
          String key = decode(fields[1]), document = decode(fields[2]);
          if (key.isEmpty() || key.length() > 4096) throw new IOException("feature identity quota");
          SnapshotManifest.normalize(document);
          double[] values = new double[10];
          for (int i = 0; i < values.length; i++) values[i] = Double.parseDouble(fields[i + 4]);
          rows.add(new SearchEngine.AuditCandidate(key, document, values));
        } else throw new IOException("feature export syntax");
      }
      if (header != null) queries.add(new Query(header, rows));
      return new FeaturePacket(queries);
    } catch (IllegalArgumentException invalid) {
      throw new IOException("invalid feature export", invalid);
    }
  }

  public List<PairwiseData.Query> attachDevelopmentLabels(
      Map<String, Map<String, Integer>> judgments) {
    if (!judgments
        .keySet()
        .equals(
            queries.stream()
                .map(q -> q.header().id())
                .collect(java.util.stream.Collectors.toSet())))
      throw new IllegalArgumentException("development label identities");
    List<PairwiseData.Query> result = new ArrayList<>();
    for (var query : queries) {
      var grades = judgments.get(query.header().id());
      List<PairwiseData.Row> rows = new ArrayList<>();
      for (int i = 0; i < query.candidates().size(); i++) {
        var candidate = query.candidates().get(i);
        rows.add(
            new PairwiseData.Row(
                candidate.path(),
                candidate.features(),
                grades.getOrDefault(candidate.path(), 0),
                i));
      }
      result.add(
          new PairwiseData.Query(
              query.header().id(),
              Wave2Inputs.judgmentGroup(grades),
              query.header().text(),
              "development",
              rows));
    }
    return List.copyOf(result);
  }

  private static String encode(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String decode(String value) throws IOException {
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(Base64.getDecoder().decode(value)))
        .toString();
  }
}
