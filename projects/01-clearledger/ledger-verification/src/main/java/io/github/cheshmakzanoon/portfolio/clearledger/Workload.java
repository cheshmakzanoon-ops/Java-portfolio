package io.github.cheshmakzanoon.portfolio.clearledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Seeded closed-loop system workload. Expected balances are computed before SQL by ReferenceLedger.
 */
public final class Workload {
  static UUID id(String name) {
    return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
  }

  record Intent(UUID tenant, String key, Journal journal, boolean reverse) {}

  static final class ClientOutcomes {
    final AtomicLong retries = new AtomicLong();
    final AtomicLong temporaryResults = new AtomicLong();
    final AtomicLong exhaustions = new AtomicLong();
    final AtomicLong completed = new AtomicLong();
    final AtomicLong failed = new AtomicLong();

    Map<String, Object> snapshot() {
      return Map.of(
          "temporaryClientResults", temporaryResults.get(),
          "clientRetryAttempts", retries.get(),
          "clientRetryExhaustions", exhaustions.get(),
          "clientAttemptBudgetPerOperation", 6,
          "completedInputGroups", completed.get(),
          "failedInputGroups", failed.get());
    }
  }

  @FunctionalInterface
  interface Pause {
    void sleep(long millis) throws InterruptedException;
  }

  static <T> T retryClient(java.util.function.Supplier<T> request, ClientOutcomes outcomes) {
    return retryClient(request, outcomes, Thread::sleep);
  }

  static <T> T retryClient(
      java.util.function.Supplier<T> request, ClientOutcomes outcomes, Pause pause) {
    for (int attempt = 0; attempt < 6; attempt++) {
      try {
        return request.get();
      } catch (LedgerStore.LedgerException temporary) {
        if (!temporary.temporary) throw temporary;
        outcomes.temporaryResults.incrementAndGet();
        if (attempt == 5) {
          outcomes.exhaustions.incrementAndGet();
          throw temporary;
        }
        try {
          pause.sleep(Math.min(1600, 100L << attempt) + ThreadLocalRandom.current().nextLong(100));
          outcomes.retries.incrementAndGet();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("CLIENT_RETRY_INTERRUPTED", interrupted);
        }
      }
    }
    throw new IllegalStateException("CLIENT_RETRY_BOUND");
  }

  public static void main(String[] args) throws Exception {
    int count = args.length > 0 ? Integer.parseInt(args[0]) : 100000;
    int writers = args.length > 1 ? Integer.parseInt(args[1]) : 1;
    Path output = Path.of(args.length > 2 ? args[2] : "target/workload");
    Files.createDirectories(output);
    if (count < 1 || count > 100000 || !Set.of(1, 8, 32).contains(writers))
      throw new IllegalArgumentException("WORKLOAD_BOUND");
    String url =
        System.getenv()
            .getOrDefault("LEDGER_URL", "jdbc:postgresql://127.0.0.1:18101/clearledger_benchmark");
    String adminPassword =
        System.getenv().getOrDefault("LEDGER_ADMIN_PASSWORD", "local-only-admin");
    LedgerStore.migrate(url, "postgres", adminPassword);
    var oracle = new ReferenceLedger();
    var intents = new ArrayList<Intent>();
    var random = new Random(20261017);
    try (var admin = new LedgerStore(url, "postgres", adminPassword, 1);
        var store =
            new LedgerStore(url, "clearledger_runtime", "local-only-clearledger", writers)) {
      admin.tx(
          c -> {
            for (int t = 0; t < 20; t++) {
              var tenant = id("fixture-tenant-" + t);
              LedgerStore.execute(c, "INSERT INTO ledger.tenant VALUES(?)", tenant);
              LedgerStore.execute(
                  c,
                  "INSERT INTO ledger.accounting_period VALUES(?,?,DATE '2026-01-01',DATE '2026-12-31',false)",
                  tenant,
                  id("period-" + t));
              try (var statement =
                  c.prepareStatement("INSERT INTO ledger.account VALUES(?,?,?,?,?)")) {
                for (int a = 0; a < 500; a++) {
                  statement.setObject(1, tenant);
                  statement.setObject(2, id("account-" + t + "-" + a));
                  statement.setString(3, List.of("CAD", "USD", "JPY").get(a % 3));
                  statement.setString(4, a % 2 == 0 ? "DEBIT" : "CREDIT");
                  statement.setString(5, "synthetic-" + a);
                  statement.addBatch();
                }
                statement.executeBatch();
              }
            }
            return null;
          });
      for (int i = 0; i < count; i++) {
        int t, currency, a, b;
        if (i < 10000) {
          t = i % 20;
          a = i / 20;
          currency = a % 3;
          int cardinality = (499 - currency) / 3 + 1;
          b = (((a - currency) / 3 + 1) % cardinality) * 3 + currency;
        } else {
          t = random.nextInt(20);
          currency = random.nextInt(3);
          int cardinality = (499 - currency) / 3 + 1;
          a = random.nextInt(cardinality) * 3 + currency;
          b = random.nextInt(cardinality) * 3 + currency;
          if (a == b) b = (((a - currency) / 3 + 1) % cardinality) * 3 + currency;
        }
        var n = BigInteger.valueOf(random.nextInt(10000) + 1);
        var lines = new ArrayList<Journal.Line>();
        lines.add(new Journal.Line(id("account-" + t + "-" + a), Journal.Side.DEBIT, n));
        lines.add(new Journal.Line(id("account-" + t + "-" + b), Journal.Side.CREDIT, n));
        if (i % 5 == 0) {
          var extra = BigInteger.valueOf(random.nextInt(1000) + 1);
          lines.add(new Journal.Line(id("account-" + t + "-" + a), Journal.Side.CREDIT, extra));
          lines.add(new Journal.Line(id("account-" + t + "-" + b), Journal.Side.DEBIT, extra));
        }
        var j =
            new Journal(
                id("period-" + t),
                LocalDate.of(2026, 1, 2),
                List.of("CAD", "USD", "JPY").get(currency),
                "fixture-" + i,
                lines);
        oracle.apply(j);
        boolean reverse = i % 1000 == 0;
        if (reverse) oracle.apply(j.reversed(j.date()));
        intents.add(new Intent(id("fixture-tenant-" + t), "fixture-" + i, j, reverse));
      }
      var expectedBalances = new HashMap<UUID, BigInteger>();
      for (int t = 0; t < 20; t++)
        for (int account = 0; account < 500; account++)
          expectedBalances.put(id("account-" + t + "-" + account), BigInteger.ZERO);
      expectedBalances.putAll(oracle.balances());
      var expected = new StringBuilder("account,debit_minus_credit_minor\n");
      expectedBalances.entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .forEach(e -> expected.append(e.getKey()).append(',').append(e.getValue()).append('\n'));
      Files.writeString(output.resolve("expected-balances.csv"), expected);
      var samples = new long[count];
      var outcomes = new ClientOutcomes();
      var next = new AtomicInteger();
      var pool = Executors.newFixedThreadPool(writers);
      var peakHeap = new AtomicLong();
      var lockWaits = new AtomicLong();
      var lockSamples = new AtomicLong();
      var successfulLockSamples = new AtomicLong();
      var failedLockSamples = new AtomicLong();
      var sampler = Executors.newSingleThreadScheduledExecutor();
      sampler.scheduleAtFixedRate(
          () -> {
            peakHeap.accumulateAndGet(
                ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), Math::max);
            try {
              long waiting =
                  admin.tx(
                      c ->
                          Long.valueOf(
                              LedgerStore.query(
                                      c,
                                      "SELECT count(*) n FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'")
                                  .getFirst()
                                  .get("n")));
              lockWaits.accumulateAndGet(waiting, Math::max);
              lockSamples.addAndGet(waiting);
              successfulLockSamples.incrementAndGet();
            } catch (RuntimeException ignored) {
              failedLockSamples.incrementAndGet();
            }
          },
          0,
          250,
          TimeUnit.MILLISECONDS);
      long started = System.nanoTime();
      var tasks = new ArrayList<Future<?>>();
      for (int w = 0; w < writers; w++)
        tasks.add(
            pool.submit(
                () -> {
                  int i;
                  while ((i = next.getAndIncrement()) < count) {
                    var intent = intents.get(i);
                    long before = System.nanoTime();
                    try {
                      var posted =
                          retryClient(
                              () -> store.post(intent.tenant, intent.key, intent.journal),
                              outcomes);
                      if (i % 100 == 0) {
                        var duplicate =
                            retryClient(
                                () -> store.post(intent.tenant, intent.key, intent.journal),
                                outcomes);
                        if (!posted.equals(duplicate))
                          throw new AssertionError("DUPLICATE_CHANGED_ID");
                      }
                      if (intent.reverse) {
                        retryClient(
                            () ->
                                store.reverse(
                                    intent.tenant,
                                    posted,
                                    "reversal-" + intent.key,
                                    intent.journal.date()),
                            outcomes);
                      }
                      samples[i] = System.nanoTime() - before;
                      outcomes.completed.incrementAndGet();
                    } catch (RuntimeException | Error failure) {
                      outcomes.failed.incrementAndGet();
                      throw failure;
                    }
                  }
                }));
      try {
        for (var task : tasks) task.get();
      } finally {
        pool.shutdownNow();
        sampler.shutdownNow();
        try {
          if (!pool.awaitTermination(30, TimeUnit.SECONDS))
            throw new IllegalStateException("WORKLOAD_WORKERS_DID_NOT_STOP");
        } finally {
          new ObjectMapper()
              .writerWithDefaultPrettyPrinter()
              .writeValue(output.resolve("client-outcomes.json").toFile(), outcomes.snapshot());
        }
      }
      long elapsed = System.nanoTime() - started;
      var actual = new HashMap<UUID, BigInteger>();
      for (int t = 0; t < 20; t++)
        for (var row : store.balances(id("fixture-tenant-" + t))) {
          var signed =
              new BigInteger(row.get("debits")).subtract(new BigInteger(row.get("credits")));
          actual.put(UUID.fromString(row.get("id")), signed);
        }
      if (!expectedBalances.equals(actual)) throw new AssertionError("REFERENCE_BALANCE_MISMATCH");
      var violations = SourceAudit.journals(admin);
      if (!violations.isEmpty()) throw new AssertionError("ACCOUNTING_IMBALANCE");
      var projector = new Projector(store);
      long lag = projector.pending();
      long replayStart = System.nanoTime();
      projector.replay();
      long replayNanos = System.nanoTime() - replayStart;
      var raw = new StringBuilder("index,latency_nanos\n");
      for (int i = 0; i < samples.length; i++)
        raw.append(i).append(',').append(samples[i]).append('\n');
      Files.writeString(output.resolve("latencies.csv"), raw);
      Arrays.sort(samples);
      var result = new LinkedHashMap<String, Object>();
      result.put("schemaVersion", 1);
      result.put(
          "command",
          System.getProperty("java.home")
              + "/bin/java -Xmx512m -cp "
              + System.getProperty("java.class.path")
              + " "
              + Workload.class.getName()
              + " "
              + String.join(" ", args));
      result.put("exitCode", 0);
      result.put("rawArtifact", output.resolve("latencies.csv").toString());
      result.put("project", "clearledger");
      result.put("workload", "marketplace-v1");
      result.put("seed", 20261017);
      result.put("journals", count);
      result.put("tenants", 20);
      result.put("accounts", 10000);
      result.put("accountsTouched", oracle.balances().size());
      result.put("tenantsTouched", intents.stream().map(Intent::tenant).distinct().count());
      result.put("writers", writers);
      result.put("timestamp", Instant.now().toString());
      result.put(
          "sourceRevision",
          System.getenv().getOrDefault("LEDGER_SOURCE_REVISION", "unrecorded-working-tree"));
      result.put("throughputJournalsPerSecond", count / (elapsed / 1e9));
      result.put("elapsedSeconds", elapsed / 1e9);
      result.put("p50Millis", samples[(int) ((samples.length - 1) * .50)] / 1e6);
      result.put("p95Millis", samples[(int) ((samples.length - 1) * .95)] / 1e6);
      result.put("p99Millis", samples[(int) ((samples.length - 1) * .99)] / 1e6);
      result.put("transactionRetries", store.retryCount());
      result.putAll(outcomes.snapshot());
      result.put("maxSampledLockWaiters", lockWaits.get());
      result.put("lockWaiterSampleTotal", lockSamples.get());
      result.put("successfulLockSamples", successfulLockSamples.get());
      result.put("failedLockSamples", failedLockSamples.get());
      result.put("lockWaitMeasurementAvailable", successfulLockSamples.get() > 0);
      result.put("projectionLagEventsBeforeReplay", lag);
      result.put("shadowReplaySeconds", replayNanos / 1e9);
      result.put("peakSampledHeapBytes", peakHeap.get());
      result.put("java", System.getProperty("java.runtime.version"));
      result.put("jvmFlags", ManagementFactory.getRuntimeMXBean().getInputArguments());
      result.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
      result.put("cores", Runtime.getRuntime().availableProcessors());
      result.put(
          "cpuQuota",
          Files.exists(Path.of("/sys/fs/cgroup/cpu.max"))
              ? Files.readString(Path.of("/sys/fs/cgroup/cpu.max")).trim()
              : "unavailable");
      result.put(
          "cpu",
          Files.readAllLines(Path.of("/proc/cpuinfo")).stream()
              .filter(l -> l.startsWith("model name"))
              .findFirst()
              .orElse("unavailable"));
      result.put(
          "memoryLimit",
          Files.exists(Path.of("/sys/fs/cgroup/memory.max"))
              ? Files.readString(Path.of("/sys/fs/cgroup/memory.max")).trim()
              : "unavailable");
      result.put(
          "databaseSettings",
          admin.tx(
              c ->
                  LedgerStore.query(
                      c,
                      "SELECT name,setting FROM pg_settings WHERE name IN('server_version','shared_buffers','max_connections','synchronous_commit','fsync','full_page_writes') ORDER BY name")));
      result.put(
          "assertions",
          Map.of(
              "referenceBalances",
              "PASS",
              "journalBalance",
              "PASS",
              "duplicateReceipts",
              "PASS",
              "shadowReplay",
              "PASS"));
      result.put(
          "limitations",
          List.of(
              "closed-loop clients: latency includes transaction retries and bounded same-key client backoff, but not open-loop queue delay",
              "database and clients share host",
              "no production claims",
              "peak heap sampled every250ms; not process RSS",
              "no warmup separated from durable fresh-dataset writes"));
      new ObjectMapper()
          .writerWithDefaultPrettyPrinter()
          .writeValue(output.resolve("results.json").toFile(), result);
      System.out.println(new ObjectMapper().writeValueAsString(result));
    }
  }
}
