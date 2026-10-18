package io.github.cheshmakzanoon.portfolio.indexforge;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.apache.lucene.store.FSDirectory;

/** One authorized, generation-leased graph view. No tenant-wide graph/statistics cache exists. */
public final class SourceFeatureView implements AutoCloseable {
  private static final Semaphore ADMISSION = new Semaphore(8, true);
  private final AuthorizationFilter authorization;
  private final AuthorizationFilter.Access access;
  private final SourceSymbolGraph.Work work = new SourceSymbolGraph.Work();
  private final Map<String, SourceSymbolGraph> graphs = new HashMap<>();
  private final Map<String, SourceFacts.Unit> facts = new HashMap<>();
  private final Map<Integer, String> keys = new HashMap<>();
  private boolean available = true, closed;

  public SourceFeatureView(
      List<CandidateRetriever.Candidate> candidates,
      GenerationLease lease,
      SourceApiCatalog catalog,
      AuthorizationFilter authorization,
      AuthorizationFilter.Access access)
      throws IOException {
    if (candidates.size() > 500) throw new IllegalArgumentException("source candidate quota");
    this.authorization = Objects.requireNonNull(authorization);
    this.access = Objects.requireNonNull(access);
    if (!ADMISSION.tryAcquire()) throw new IOException("structural inference admission limit");
    try {
      if (!authorization.current(access)) throw new SecurityException("access denied");
      requireSchema(lease, catalog);
      if (!(lease.reader().directory() instanceof FSDirectory directory))
        throw new IOException("source graph requires committed filesystem generation");
      Map<String, List<CandidateRetriever.Candidate>> repositories = new TreeMap<>();
      for (var candidate : candidates) {
        if (candidate.document() < 0
            || candidate.document() >= lease.reader().maxDoc()
            || keys.putIfAbsent(candidate.document(), candidate.key()) != null)
          throw new IllegalArgumentException("source candidate identity");
        var document =
            lease
                .reader()
                .storedFields()
                .document(
                    candidate.document(), Set.of("tenant", "repository", "key", "source_schema"));
        authorization.require(access, document.get("tenant"), document.get("repository"));
        if (!candidate.key().equals(document.get("key"))
            || !SourceFacts.SCHEMA.equals(document.get("source_schema")))
          throw new IOException("source candidate schema/key mismatch; reindex required");
        repositories
            .computeIfAbsent(document.get("repository"), unused -> new ArrayList<>())
            .add(candidate);
      }
      long remaining = SourceRepositoryFacts.DECODED_LIMIT;
      int remainingRows = 65_536;
      for (var repository : repositories.entrySet()) {
        authorization.require(access, access.tenant(), repository.getKey());
        var snapshot =
            SourceRepositoryFacts.read(
                directory.getDirectory(),
                access.tenant(),
                repository.getKey(),
                remaining,
                remainingRows);
        if (!snapshot.inventoryObserved()) {
          unavailable();
          break;
        }
        Map<String, SourceFacts.Unit> partition = new HashMap<>();
        for (var document : snapshot.documents()) {
          byte[] encoded = SourceWire.encode(document.facts());
          remaining -= SourceWire.decodedBytes(encoded);
          remainingRows -= SourceRepositoryFacts.rows(document.facts());
          partition.put(document.key(), document.facts());
        }
        for (var candidate : repository.getValue()) {
          var unit = partition.get(candidate.key());
          if (unit == null) throw new IOException("source graph missing indexed candidate");
          var document =
              lease.reader().storedFields().document(candidate.document(), Set.of("source_facts"));
          var binary = document.getBinaryValue("source_facts");
          if (binary == null
              || !Arrays.equals(
                  SourceWire.encode(unit),
                  Arrays.copyOfRange(binary.bytes, binary.offset, binary.offset + binary.length)))
            throw new IOException("source graph/index summary mismatch");
          facts.put(candidate.key(), unit);
        }
        graphs.put(
            repository.getKey(),
            new SourceSymbolGraph(
                access.tenant(), repository.getKey(), snapshot.documents(), catalog));
      }
      if (!authorization.current(access)) throw new SecurityException("access denied");
    } catch (SourceRepositoryFacts.QueryQuota quota) {
      unavailable();
    } catch (IOException | RuntimeException failure) {
      close();
      throw failure;
    }
  }

  private void unavailable() {
    available = false;
    graphs.clear();
    facts.clear();
  }

  public static void requireSchema(GenerationLease lease, SourceApiCatalog catalog)
      throws IOException {
    var metadata = lease.reader().getIndexCommit().getUserData();
    if (!SourceFacts.SCHEMA.equals(metadata.get("source_schema"))
        || !SourceSymbolGraph.SCHEMA.equals(metadata.get("source_graph"))
        || !catalog.workerHash().equals(metadata.get("source_worker"))
        || !catalog.jdk().equals(metadata.get("source_jdk")))
      throw new IOException(
          "structural generation identity/schema mismatch; explicit reindex required");
  }

  public Map<Integer, double[]> features(Set<String> query, SourceRelations.Options options)
      throws IOException {
    check();
    var result = new LinkedHashMap<Integer, double[]>();
    try {
      for (var candidate : keys.entrySet()) {
        double[] values = new double[6];
        if (available)
          values =
              SourceRelations.extract(
                  candidate.getValue(),
                  facts.get(candidate.getValue()),
                  graph(candidate.getValue()),
                  query,
                  options,
                  work);
        result.put(candidate.getKey(), values);
      }
    } catch (SourceSymbolGraph.Budget quota) {
      result.clear();
      for (int document : keys.keySet()) result.put(document, new double[6]);
    }
    check();
    return Collections.unmodifiableMap(result);
  }

  public Map<Integer, SourceUsageRanker.Inventory> usage(String query) {
    check();
    var result = new LinkedHashMap<Integer, SourceUsageRanker.Inventory>();
    try {
      Map<String, SourceSymbolGraph.Inventory> observed = new HashMap<>();
      if (available)
        for (var partition : graphs.entrySet()) {
          var selected =
              keys.values().stream()
                  .filter(key -> repository(key).equals(partition.getKey()))
                  .toList();
          observed.putAll(partition.getValue().observe(selected, query, work));
        }
      for (var candidate : keys.entrySet()) {
        var inventory = observed.get(candidate.getValue());
        if (inventory == null || !inventory.membershipKnown())
          result.put(candidate.getKey(), SourceUsageRanker.Inventory.unknown());
        else {
          List<SourceUsageRanker.Member> members = new ArrayList<>();
          for (var member : inventory.members())
            members.add(
                new SourceUsageRanker.Member(
                    member.identity(),
                    member.javaName(),
                    member.observedCallers(),
                    !member.usable()
                        ? SourceUsageRanker.Status.UNKNOWN
                        : member.observedCallers() == 32
                            ? SourceUsageRanker.Status.SATURATED_OBSERVED
                            : SourceUsageRanker.Status.USABLE_OBSERVED));
          result.put(candidate.getKey(), new SourceUsageRanker.Inventory(true, members));
        }
      }
    } catch (SourceSymbolGraph.Budget quota) {
      result.clear();
      for (int document : keys.keySet())
        result.put(document, SourceUsageRanker.Inventory.unknown());
    }
    check();
    return Collections.unmodifiableMap(result);
  }

  private SourceSymbolGraph graph(String key) {
    return graphs.get(repository(key));
  }

  private String repository(String key) {
    String prefix = access.tenant() + "/";
    if (!key.startsWith(prefix)) throw new SecurityException("source scope");
    int slash = key.indexOf('/', prefix.length());
    if (slash < 0) throw new IllegalArgumentException("source key");
    return key.substring(prefix.length(), slash);
  }

  private void check() {
    if (closed) throw new IllegalStateException("closed source view");
    if (!authorization.current(access)) throw new SecurityException("access denied");
  }

  @Override
  public void close() {
    if (!closed) {
      closed = true;
      graphs.clear();
      facts.clear();
      ADMISSION.release();
    }
  }
}
