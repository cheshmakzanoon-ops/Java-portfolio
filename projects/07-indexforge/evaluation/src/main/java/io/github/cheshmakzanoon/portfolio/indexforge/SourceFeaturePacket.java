package io.github.cheshmakzanoon.portfolio.indexforge;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/** Versioned label-blind wave3 export; caller metadata is never a learned numeric coordinate. */
public record SourceFeaturePacket(List<SourceFeaturePacket.Query> queries) {
  private static final String HEADER = "indexforge-source-export-v1\t" + SourceRelations.SCHEMA;
  private static final int BYTE_LIMIT = 134_217_728;
  private static final Set<String> REASONS =
      Set.of(
          "SYNTAX_BYPASS",
          "UNKNOWN_DECLARATION_MEMBERSHIP",
          "UNKNOWN_MATCHED_USAGE",
          "FEWER_THAN_TWO_MATCHED_DECLARATIONS",
          "APPLIED_OBSERVED_LOWER_BOUND");

  public record Query(
      Wave2Inputs.Header header,
      List<SearchEngine.AuditCandidate> candidates,
      List<String> usageKeys,
      String usageReason) {
    public Query {
      candidates = List.copyOf(candidates);
      usageKeys = List.copyOf(usageKeys);
      if (!header.split().equals("development")
          || candidates.size() > 500
          || usageKeys.size() != candidates.size()
          || !REASONS.contains(usageReason))
        throw new IllegalArgumentException("source export query scope");
      Set<String> keys = new HashSet<>(), paths = new HashSet<>();
      for (var row : candidates)
        if (row.features().length != 16
            || row.key() == null
            || row.key().isBlank()
            || row.key().length() > 4096
            || !SnapshotManifest.normalize(row.path()).equals(row.path())
            || !keys.add(row.key())
            || !paths.add(row.path()))
          throw new IllegalArgumentException("source export candidate identity/schema");
      if (new HashSet<>(usageKeys).size() != usageKeys.size()
          || !keys.equals(new HashSet<>(usageKeys)))
        throw new IllegalArgumentException(
            "usage order must be a permutation of the same authorized pool");
      if (!PlainTextQuery.identifier(header.text())
          && !usageKeys.equals(candidates.stream().map(SearchEngine.AuditCandidate::key).toList()))
        throw new IllegalArgumentException("nonidentifier usage must preserve original pool");
    }

    public List<String> usagePaths() {
      Map<String, String> paths = new HashMap<>();
      for (var row : candidates) paths.put(row.key(), row.path());
      return usageKeys.stream().map(paths::get).toList();
    }
  }

  public SourceFeaturePacket {
    queries = List.copyOf(queries);
    if (queries.size() != 40 || queries.stream().map(q -> q.header().id()).distinct().count() != 40)
      throw new IllegalArgumentException("forty unique development source exports required");
  }

  public void writeNew(Path path) throws IOException {
    long bytes = HEADER.length() + 1;
    try (var out =
        Files.newBufferedWriter(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      out.write(HEADER);
      out.newLine();
      for (var query : queries) {
        String line =
            "Q\t"
                + encode(query.header().id())
                + "\t"
                + encode(query.header().type())
                + "\t"
                + encode(query.header().text())
                + "\t"
                + query.usageReason();
        out.write(line);
        out.newLine();
        bytes += line.length() + 1;
        Map<String, Integer> usage = new HashMap<>();
        for (int i = 0; i < query.usageKeys().size(); i++) usage.put(query.usageKeys().get(i), i);
        for (int i = 0; i < query.candidates().size(); i++) {
          var row = query.candidates().get(i);
          var text =
              new StringBuilder("D\t")
                  .append(encode(row.key()))
                  .append('\t')
                  .append(encode(row.path()))
                  .append('\t')
                  .append(i)
                  .append('\t')
                  .append(usage.get(row.key()));
          for (double value : row.features()) text.append('\t').append(value);
          bytes += text.length() + 1;
          if (bytes > BYTE_LIMIT || text.length() > 16384)
            throw new IOException("source export byte/record quota");
          out.write(text.toString());
          out.newLine();
        }
      }
    }
  }

  public static SourceFeaturePacket read(Path path) throws IOException {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > BYTE_LIMIT)
      throw new IOException("source export regular file/byte quota");
    var queries = new ArrayList<Query>();
    Wave2Inputs.Header header = null;
    var candidates = new ArrayList<SearchEngine.AuditCandidate>();
    var usage = new TreeMap<Integer, String>();
    String reason = null;
    try (var input =
        new BufferedReader(
            new InputStreamReader(
                Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)))) {
      if (!HEADER.equals(line(input))) throw new IOException("source export schema");
      String text;
      while ((text = line(input)) != null) {
        String[] fields = text.split("\t", -1);
        if (fields.length == 5 && fields[0].equals("Q")) {
          if (header != null) queries.add(finish(header, candidates, usage, reason));
          if (queries.size() >= 40) throw new IOException("source query quota");
          header =
              new Wave2Inputs.Header(
                  decode(fields[1]), "development", decode(fields[2]), decode(fields[3]));
          reason = fields[4];
          candidates = new ArrayList<>();
          usage = new TreeMap<>();
        } else if (fields.length == 21 && fields[0].equals("D") && header != null) {
          int rank = Integer.parseInt(fields[3]), usageRank = Integer.parseInt(fields[4]);
          if (rank != candidates.size() || rank >= 500 || usageRank < 0 || usageRank >= 500)
            throw new IOException("source export rank/quota");
          String key = decode(fields[1]);
          double[] values = new double[16];
          for (int i = 0; i < 16; i++) values[i] = Double.parseDouble(fields[i + 5]);
          if (usage.putIfAbsent(usageRank, key) != null)
            throw new IOException("duplicate source usage rank");
          candidates.add(new SearchEngine.AuditCandidate(key, decode(fields[2]), values));
        } else throw new IOException("source export record syntax");
      }
      if (header != null) queries.add(finish(header, candidates, usage, reason));
      return new SourceFeaturePacket(queries);
    } catch (IllegalArgumentException invalid) {
      throw new IOException("invalid source export", invalid);
    }
  }

  private static Query finish(
      Wave2Inputs.Header header,
      List<SearchEngine.AuditCandidate> candidates,
      TreeMap<Integer, String> usage,
      String reason)
      throws IOException {
    if (!usage.isEmpty() && (usage.firstKey() != 0 || usage.lastKey() != usage.size() - 1))
      throw new IOException("noncontiguous source usage ranks");
    return new Query(header, candidates, List.copyOf(usage.values()), reason);
  }

  public List<PairwiseData.Query> attachDevelopmentLabels(
      Map<String, Map<String, Integer>> labels, int dimensions) {
    if (dimensions != 12 && dimensions != 16)
      throw new IllegalArgumentException("source family dimension");
    if (!labels
        .keySet()
        .equals(
            queries.stream()
                .map(q -> q.header().id())
                .collect(java.util.stream.Collectors.toSet())))
      throw new IllegalArgumentException("development source label identity");
    var result = new ArrayList<PairwiseData.Query>();
    for (var query : queries) {
      var grades = labels.get(query.header().id());
      var rows = new ArrayList<PairwiseData.Row>();
      for (int i = 0; i < query.candidates().size(); i++) {
        var row = query.candidates().get(i);
        rows.add(
            new PairwiseData.Row(
                row.path(),
                Arrays.copyOf(row.features(), dimensions),
                grades.getOrDefault(row.path(), 0),
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

  private static String line(Reader input) throws IOException {
    var out = new StringBuilder();
    int c;
    while ((c = input.read()) != -1) {
      if (c == '\n') return out.toString();
      if (c == '\r' || out.length() == 16384)
        throw new IOException("source export record quota/syntax");
      out.append((char) c);
    }
    return out.isEmpty() ? null : out.toString();
  }

  private static String encode(String text) {
    return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
  }

  private static String decode(String text) throws IOException {
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(Base64.getDecoder().decode(text)))
        .toString();
  }
}
