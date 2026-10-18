package io.github.cheshmakzanoon.portfolio.tenantshield;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import tools.jackson.databind.*;

/**
 * Closed-loop authenticated HTTP load driver; real tenant data, network calls and persisted audit.
 */
public final class Workload {
  private static final ObjectMapper JSON = new ObjectMapper();

  private Workload() {}

  static PolicyEngine.Definition definition() {
    Map<String, Set<String>> roles = new TreeMap<>();
    roles.put("reader", Set.of());
    for (int r = 1; r < 100; r++) roles.put("role-" + r, Set.of("reader"));
    List<PolicyEngine.Rule> rules = new ArrayList<>();
    rules.add(
        new PolicyEngine.Rule(
            "read",
            "document:read",
            "document",
            PolicyEngine.Effect.ALLOW,
            "hasRole(\"reader\") and subject.department == resource.department and subject.clearance >= resource.classification"));
    rules.add(
        new PolicyEngine.Rule(
            "suspend", "document:read", "document", PolicyEngine.Effect.DENY, "subject.suspended"));
    for (int n = 0; n < 198; n++)
      rules.add(
          new PolicyEngine.Rule(
              "purpose-" + n,
              "document:read",
              "document",
              PolicyEngine.Effect.ALLOW,
              "context.purpose == \"purpose-" + n + "\""));
    return new PolicyEngine.Definition(
        roles, List.of(new PolicyEngine.Grant("reader", "document:read", "document")), rules);
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 2)
      throw new IllegalArgumentException("usage: Workload seed|http output.json [requestCount]");
    if (args[0].equals("seed")) {
      seed();
      return;
    }
    if (!args[0].equals("http")) throw new IllegalArgumentException("mode");
    int requests = args.length > 2 ? Integer.parseInt(args[2]) : 100000;
    if (requests < 1 || requests > 1000000) throw new IllegalArgumentException("request bound");
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    String alice = token(client, "alice"), admin = token(client, "admin");
    for (int n = 0; n < 1000; n++) {
      // Real warmup, excluded from timed samples; unavailable services fail immediately.
      if (request(client, alice, n).statusCode() != 200)
        throw new IllegalStateException("HTTP warmup failed");
    }
    long queriesBefore = queries(client, admin);
    long[] latency = new long[requests];
    AtomicInteger next = new AtomicInteger(),
        denied = new AtomicInteger(),
        errors = new AtomicInteger(),
        inFlight = new AtomicInteger(),
        maxInFlight = new AtomicInteger();
    long start = System.nanoTime();
    ExecutorService workers = Executors.newFixedThreadPool(16, Thread.ofVirtual().factory());
    try {
      List<Future<?>> jobs = new ArrayList<>();
      for (int worker = 0; worker < 16; worker++)
        jobs.add(
            workers.submit(
                () -> {
                  for (int n;
                      !Thread.currentThread().isInterrupted()
                          && (n = next.getAndIncrement()) < requests; ) {
                    long begin = System.nanoTime();
                    int active = inFlight.incrementAndGet();
                    maxInFlight.accumulateAndGet(active, Math::max);
                    try {
                      HttpResponse<String> r = request(client, alice, n);
                      if (r.statusCode() != 200) errors.incrementAndGet();
                      else if (!JSON.readTree(r.body()).get("decision").asText().equals("ALLOW"))
                        denied.incrementAndGet();
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                      throw new IllegalStateException("HTTP workload interrupted", e);
                    } catch (Exception e) {
                      errors.incrementAndGet();
                    } finally {
                      latency[n] = System.nanoTime() - begin;
                      inFlight.decrementAndGet();
                    }
                  }
                }));
      long deadline = start + Duration.ofMinutes(15).toNanos();
      for (var job : jobs) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new TimeoutException("HTTP workload deadline");
        job.get(remaining, TimeUnit.NANOSECONDS);
      }
    } finally {
      workers.shutdownNow();
      if (!workers.awaitTermination(15, TimeUnit.SECONDS))
        throw new IllegalStateException("HTTP workload workers did not terminate");
    }
    long elapsed = System.nanoTime() - start;
    long after = queries(client, admin);
    Arrays.sort(latency);
    int expectedDenials = 0;
    for (int n = 0; n < requests; n++) if (((n / 50) % 1000) % 5 == 0) expectedDenials++;
    boolean success = errors.get() == 0 && denied.get() == expectedDenials;
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("schemaVersion", 1);
    result.put("project", "tenantshield");
    result.put(
        "workload",
        requests == 100000
            ? "50x200x100x1000-authenticated-http-100k-v1"
            : "explicit-smoke-http-v1");
    result.put(
        "sourceRevision",
        System.getenv().getOrDefault("TENANTSHIELD_SOURCE_FINGERPRINT", "UNRECORDED"));
    result.put("timestamp", Instant.now().toString());
    result.put(
        "command",
        ProcessHandle.current()
            .info()
            .commandLine()
            .orElse(System.getProperty("sun.java.command")));
    result.put("environment", "environment.json");
    result.put(
        "rawArtifact",
        ".work/A05/runtime/" + Path.of(args[1]).getFileName().toString().replace(".json", ".jfr"));
    result.put("seed", 20261018);
    result.put("tenants", 50);
    result.put("policyVersionsPerTenant", 200);
    result.put("rolesPerPolicy", 100);
    result.put("activeRulesPerTenant", 200);
    result.put("resourcesPerTenant", 1000);
    result.put("requests", requests);
    result.put("concurrency", 16);
    result.put("warmupRequests", 1000);
    result.put("poolSize", 8);
    result.put(
        "measurements",
        Map.of(
            "throughputPerSecond",
            requests / (elapsed / 1e9),
            "p50Millis",
            percentile(latency, .5),
            "p95Millis",
            percentile(latency, .95),
            "p99Millis",
            percentile(latency, .99),
            "durationSeconds",
            elapsed / 1e9,
            "queryCountIncludingMetricsReads",
            after - queriesBefore,
            "cacheSize",
            cacheSize(client, admin),
            "denials",
            denied.get(),
            "errors",
            errors.get(),
            "maximumClientInFlight",
            maxInFlight.get()));
    result.put("assertions", Map.of("passed", success ? 1 : 0, "failed", success ? 0 : 1));
    result.put("exitCode", success ? 0 : 1);
    result.put(
        "limitations",
        List.of(
            "Closed-loop load; coordinated omission underestimates latency during server stalls.",
            "Disposable local OIDC; tokens obtained before measurement, JWT validation occurs per request.",
            "Telemetry reads are included in SQL query delta; no invented CPU/allocation measurements."));
    Files.writeString(
        Path.of(args[1]), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n");
    if (errors.get() != 0 || denied.get() != expectedDenials)
      throw new AssertionError(
          "HTTP workload errors or unexpected deny count: "
              + errors.get()
              + " / "
              + denied.get()
              + " expected "
              + expectedDenials);
  }

  static double percentile(long[] data, double q) {
    return data[Math.min(data.length - 1, (int) Math.ceil(data.length * q) - 1)] / 1e6;
  }

  static HttpResponse<String> request(HttpClient client, String token, int n) throws Exception {
    int tenant = n % 50, resource = (n / 50) % 1000;
    return client.send(
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:18500/v1/tenants/load-" + tenant + "/decisions"))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"action\":\"document:read\",\"resource\":{\"type\":\"document\",\"id\":\"doc-"
                        + resource
                        + "\"},\"context\":{\"purpose\":\"support\"}}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  static String token(HttpClient client, String user) throws Exception {
    var r =
        client.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:18502/realms/tenantshield/protocol/openid-connect/token"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "grant_type=password&client_id=portfolio-demo&username="
                            + user
                            + "&password=local-pass"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (r.statusCode() != 200) throw new IllegalStateException("local token request failed");
    return JSON.readTree(r.body()).get("access_token").asText();
  }

  static JsonNode metrics(HttpClient client, String token, int tenant) throws Exception {
    var r =
        client.send(
            HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:18500/v1/tenants/load-" + tenant + "/metrics"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (r.statusCode() != 200) throw new IllegalStateException("metrics unavailable");
    return JSON.readTree(r.body());
  }

  static long queries(HttpClient client, String token) throws Exception {
    long total = 0;
    for (int i = 0; i < 50; i++) total += metrics(client, token, i).get("queryCount").asLong();
    return total;
  }

  static long cacheSize(HttpClient client, String token) throws Exception {
    long total = 0;
    for (int i = 0; i < 50; i++) total += metrics(client, token, i).get("cacheSize").asLong();
    return total;
  }

  static void seed() throws Exception {
    PolicyEngine.Definition d = definition();
    var compiled = PolicyEngine.compile(d);
    String content = JSON.writeValueAsString(d);
    String issuer = "http://127.0.0.1:18502/realms/tenantshield";
    try (Connection c =
        DriverManager.getConnection(
            required("TENANTSHIELD_MIGRATION_URL"),
            required("TENANTSHIELD_MIGRATION_USER"),
            required("TENANTSHIELD_MIGRATION_PASSWORD"))) {
      c.setAutoCommit(false);
      for (int t = 0; t < 50; t++) {
        String tenant = "load-" + t;
        try (PreparedStatement check =
            c.prepareStatement("SELECT count(*) FROM tenant WHERE tenant_id=?")) {
          check.setString(1, tenant);
          try (ResultSet r = check.executeQuery()) {
            r.next();
            if (r.getLong(1) > 0) {
              validateSeed(c, tenant, compiled.digest());
              continue;
            }
          }
        }
        try (PreparedStatement s = c.prepareStatement("INSERT INTO tenant VALUES(?,1)")) {
          s.setString(1, tenant);
          s.executeUpdate();
        }
        for (String user : List.of("alice", "admin")) {
          try (PreparedStatement s =
              c.prepareStatement(
                  "INSERT INTO membership VALUES(?,?,?,ARRAY['reader'],'support',3,false,?,true)")) {
            s.setString(1, tenant);
            s.setString(2, issuer);
            s.setString(3, user);
            s.setBoolean(4, user.equals("admin"));
            s.executeUpdate();
          }
        }
        try (PreparedStatement versions =
                c.prepareStatement(
                    "INSERT INTO policy_version(tenant_id,version,content,digest) VALUES(?,?,?,?)");
            PreparedStatement roles = c.prepareStatement("INSERT INTO role VALUES(?,?,?)");
            PreparedStatement edges = c.prepareStatement("INSERT INTO role_edge VALUES(?,?,?,?)");
            PreparedStatement grants =
                c.prepareStatement(
                    "INSERT INTO \"grant\" VALUES(?,?,'reader','document:read','document')")) {
          for (int v = 1; v <= 200; v++) {
            versions.setString(1, tenant);
            versions.setInt(2, v);
            versions.setString(3, content);
            versions.setString(4, compiled.digest());
            versions.addBatch();
          }
          versions.executeBatch();
          for (int v = 1; v <= 200; v++) {
            for (var entry : d.parents().entrySet()) {
              roles.setString(1, tenant);
              roles.setInt(2, v);
              roles.setString(3, entry.getKey());
              roles.addBatch();
              for (String parent : entry.getValue()) {
                edges.setString(1, tenant);
                edges.setInt(2, v);
                edges.setString(3, entry.getKey());
                edges.setString(4, parent);
                edges.addBatch();
              }
            }
            grants.setString(1, tenant);
            grants.setInt(2, v);
            grants.addBatch();
          }
          roles.executeBatch();
          edges.executeBatch();
          grants.executeBatch();
        }
        try (PreparedStatement s =
            c.prepareStatement("INSERT INTO resource VALUES(?,?,'document',?,2,1)")) {
          for (int r = 0; r < 1000; r++) {
            s.setString(1, tenant);
            s.setString(2, "doc-" + r);
            s.setString(3, r % 5 == 0 ? "finance" : "support");
            s.addBatch();
          }
          s.executeBatch();
        }
        try (PreparedStatement s = c.prepareStatement("INSERT INTO active_policy VALUES(?,200)")) {
          s.setString(1, tenant);
          s.executeUpdate();
        }
        c.commit();
        System.out.println(
            "Seeded " + tenant + " (200 immutable revisions,100 roles,1000 resources)");
      }
    }
  }

  static void validateSeed(Connection c, String tenant, String digest) throws SQLException {
    try (var q =
        c.prepareStatement(
            "SELECT p.version,p.digest,(SELECT count(*) FROM policy_version v WHERE v.tenant_id=a.tenant_id),(SELECT count(*) FROM role r WHERE r.tenant_id=a.tenant_id),(SELECT count(*) FROM resource r WHERE r.tenant_id=a.tenant_id) FROM active_policy a JOIN policy_version p ON p.tenant_id=a.tenant_id AND p.version=a.version WHERE a.tenant_id=?")) {
      q.setString(1, tenant);
      try (var r = q.executeQuery()) {
        if (!r.next()
            || r.getLong(1) != 200
            || !digest.equals(r.getString(2))
            || r.getLong(3) != 200
            || r.getLong(4) != 20000
            || r.getLong(5) != 1000)
          throw new IllegalStateException(
              "stale workload seed " + tenant + "; run explicit reset-workload.sh then seed again");
      }
    }
  }

  static String required(String name) {
    String value = System.getenv(name);
    if (value == null) throw new IllegalStateException("missing " + name);
    return value;
  }
}
