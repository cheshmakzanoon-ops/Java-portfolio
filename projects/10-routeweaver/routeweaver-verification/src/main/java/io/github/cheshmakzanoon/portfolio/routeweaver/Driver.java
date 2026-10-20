package io.github.cheshmakzanoon.portfolio.routeweaver;

import static io.github.cheshmakzanoon.portfolio.routeweaver.Model.*;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Live HTTP assertions and raw benchmark measurements; every score is independently recomputed. */
public final class Driver {
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private final String base;
  private int assertions;

  private Driver(String base) {
    this.base = base;
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 1) throw new IllegalArgumentException("demo URL | corpus OUTPUT");
    if ("demo".equals(args[0]))
      new Driver(args.length > 1 ? args[1] : "http://127.0.0.1:19000").demo();
    else if ("corpus".equals(args[0])) corpus(Path.of(args[1]));
    else if ("poll-diagnostic".equals(args[0])) PollingDiagnostic.run();
    else if ("fixture".equals(args[0])) System.out.println(Json.write(Fixtures.demo()));
    else throw new IllegalArgumentException("unknown command");
  }

  private String request(String path, Object body, int expected) throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(40));
    String token = System.getenv("ROUTEWEAVER_DEMO_TOKEN");
    if (token != null) b.header("Authorization", "Bearer " + token);
    if (body == null) b.GET();
    else
      b.header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
    var r = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
    String response;
    try (var input = r.body()) {
      response = boundedResponse(input);
    }
    check(
        r.statusCode() == expected,
        "HTTP "
            + path
            + " expected="
            + expected
            + " actual="
            + r.statusCode()
            + " body="
            + response.substring(0, Math.min(4096, response.length())));
    return response;
  }

  static String boundedResponse(java.io.InputStream input) throws java.io.IOException {
    int limit = 32 * 1024 * 1024;
    byte[] bytes = input.readNBytes(limit + 1);
    if (bytes.length > limit) throw new java.io.IOException("HTTP response exceeds 32 MiB");
    return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
  }

  private void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
    assertions++;
  }

  private Store.JobView solve(Problem p, String requestId) throws Exception {
    return solve(p, requestId, 5000, 1000);
  }

  private Store.JobView solve(Problem p, String requestId, int budget, int steps) throws Exception {
    var q =
        Json.read(
            request(
                "/v1/problems/" + p.id() + "/solve",
                new Store.SolveRequest(p.version(), requestId, 42, budget, steps),
                202),
            Store.JobView.class);
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (System.nanoTime() < deadline) {
      var job = Json.read(request("/v1/solve-jobs/" + q.id(), null, 200), Store.JobView.class);
      if (Set.of("COMPLETED", "CANCELLED", "FAILED").contains(job.state())) {
        check(!"FAILED".equals(job.state()), "solver failed " + job.failure());
        check(job.incumbent() != null, "retained validated incumbent required");
        Validation v = new Validator().validate(p, job.incumbent().candidate());
        check(v.valid(), "hard violations in solve result");
        return job;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("solver exceeded bounded wait");
  }

  private Store.PlanView publish(Problem p, Store.JobView job, long revision) throws Exception {
    return Json.read(
        request(
            "/v1/plans/" + p.id() + "/publish",
            new Store.PublishRequest(job.id(), p.version(), revision),
            200),
        Store.PlanView.class);
  }

  private void demo() throws Exception {
    Problem p = Fixtures.demo();
    request("/v1/problems", p, 201);
    Validation baseline = new Heuristics().baseline(p);
    check(baseline.valid(), "baseline valid");
    check(baseline.candidate().unassigned().isEmpty(), "all 60 original jobs assigned");
    Store.JobView original = solve(p, "demo-original-v1");
    Validation storedBaseline =
        Json.read(
            request("/v1/solve-jobs/" + original.id() + "/baseline", null, 200), Validation.class);
    check(
        original.incumbent().score().compareTo(storedBaseline.score()) <= 0,
        "same-version incumbent never worsens");
    Store.PlanView first = publish(p, original, 0);
    check(first.solution().score().hard() == 0, "first published zero hard violations");
    Timeline broken =
        first.solution().timelines().stream()
            .filter(t -> !t.stops().isEmpty() && t.stops().size() > 2)
            .findFirst()
            .orElseThrow();
    Instant at = broken.stops().getFirst().departure();
    List<Replanner.Observation> observations = new ArrayList<>();
    for (Vehicle v : p.vehicles()) {
      Timeline timeline =
          first.solution().timelines().stream()
              .filter(t -> t.vehicleId().equals(v.id()))
              .findFirst()
              .orElseThrow();
      int prefix = 0;
      while (prefix < timeline.stops().size()
          && !timeline.stops().get(prefix).departure().isAfter(at)) prefix++;
      observations.add(
          new Replanner.Observation(v.id(), prefix, v.id().equals(broken.vehicleId()), 1, true));
    }
    Job urgent = new Job("URGENT", 0, 1000, 30, at, at.plusSeconds(3600), Set.of("urgent"), 100);
    Problem revised =
        Json.read(
            request(
                "/v1/plans/" + p.id() + "/replan",
                new Api.ReplanRequest(1, 1, at, observations, List.of(urgent)),
                201),
            Problem.class);
    check(revised.version() == 2, "version advances");
    check(
        revised.completed().stream()
            .anyMatch(c -> c.jobId().equals(broken.stops().getFirst().jobId())),
        "completed history preserved");
    check(
        revised.audit().stream().anyMatch(s -> s.contains("RELEASE_FAILED_COMMITMENTS")),
        "released failed commitments audited");
    Store.JobView replanned = solve(revised, "demo-revised-v2");
    Store.PlanView second = publish(revised, replanned, 1);
    Validation validated = new Validator().validate(revised, second.solution().candidate());
    check(validated.valid(), "second published zero hard violations");
    check(
        validated.explanations().containsValue("STRANDED_CARGO"),
        "failed onboard cargo remains stranded");
    check(
        validated.candidate().routes().stream()
            .filter(r -> r.vehicleId().equals("V5"))
            .findFirst()
            .orElseThrow()
            .jobs()
            .contains("URGENT"),
        "standby handles urgent depot job");
    Set<String> assigned = new HashSet<>();
    for (Route route : validated.candidate().routes())
      for (String job : route.jobs()) check(assigned.add(job), "no duplicate " + job);
    for (Completed done : revised.completed())
      check(!assigned.contains(done.jobId()), "completed work not reassigned");
    for (Vehicle v : revised.vehicles()) {
      Route route =
          validated.candidate().routes().stream()
              .filter(r -> r.vehicleId().equals(v.id()))
              .findFirst()
              .orElseThrow();
      check(
          route.jobs().subList(0, v.frozenPrefix().size()).equals(v.frozenPrefix()),
          "frozen work preserved");
    }
    request("/v1/plans/" + p.id() + "/publish", new Store.PublishRequest(original.id(), 1, 2), 409);
    check(
        second.problemVersion() == 2 && first.problemVersion() == 1, "published versions recorded");
    System.out.println(
        Json.write(
            Map.ofEntries(
                Map.entry("schemaVersion", 1),
                Map.entry("project", "routeweaver"),
                Map.entry("command", "live-http-demo"),
                Map.entry("assertions", assertions),
                Map.entry("originalJobs", 60),
                Map.entry("vehicles", 6),
                Map.entry("baseline", storedBaseline.score()),
                Map.entry("optimized", original.incumbent().score()),
                Map.entry("replanned", validated.score()),
                Map.entry("stranded", validated.explanations()),
                Map.entry("baselineRoutes", storedBaseline.candidate().routes()),
                Map.entry("baselineTimelines", storedBaseline.timelines()),
                Map.entry("publishedRoutes", first.solution().candidate().routes()),
                Map.entry("replannedRoutes", validated.candidate().routes()),
                Map.entry("completedHistory", revised.completed()),
                Map.entry("revisionAudit", revised.audit()),
                Map.entry("stalePublicationStatus", 409),
                Map.entry("inputHashes", List.of(p.canonicalHash(), revised.canonicalHash())),
                Map.entry("algorithmVersion", original.algorithmVersion()),
                Map.entry("objectiveVersion", original.configVersion()),
                Map.entry("inputVersions", List.of(1, 2)),
                Map.entry("testedSource", original.sourceRevision()),
                Map.entry(
                    "queueLatencyNanos",
                    Duration.between(original.createdAt(), original.startedAt()).toNanos()))));
  }

  private static void checkBaseline(Problem p, Validation baseline) {
    Validation checked = new Validator().validate(p, baseline.candidate());
    if (!checked.valid() || !checked.score().equals(baseline.score()))
      throw new AssertionError("invalid or forged persisted baseline");
  }

  private static void corpus(Path output) throws Exception {
    Files.createDirectories(output);
    String serviceUrl = System.getenv("ROUTEWEAVER_BENCHMARK_URL");
    Driver system = serviceUrl == null ? null : new Driver(serviceUrl);
    List<Map<String, Object>> measurements = new ArrayList<>();
    String source =
        System.getenv().getOrDefault("ROUTEWEAVER_SOURCE_REVISION", "working-tree-unverified");
    // Preregistered deterministic corpus, three independent solver runs each. No asserted speedup
    // target.
    int[] sizes = {100, 100, 100, 250, 250, 250, 500, 500, 500, 100};
    int[] vehicles = {5, 5, 5, 10, 10, 10, 25, 25, 25, 5};
    String[] difficulty = {
      "easy",
      "tight",
      "overconstrained",
      "easy",
      "tight",
      "overconstrained",
      "easy",
      "tight",
      "overconstrained",
      "improvable"
    };
    for (int index = 0; index < sizes.length; index++)
      for (int run = 0; run < 3; run++) {
        Problem p =
            index == 9
                ? Fixtures.improvable()
                : Fixtures.problem(sizes[index], vehicles[index], 4200 + index, difficulty[index]);
        if (system != null)
          p =
              new Problem(
                  p.tenant(),
                  p.id() + "-bench-" + run,
                  p.version(),
                  p.serviceZone(),
                  p.depot(),
                  p.planningInstant(),
                  p.matrix(),
                  p.jobs(),
                  p.vehicles(),
                  p.completed(),
                  p.previous(),
                  p.objective(),
                  p.audit());
        final Problem input = p;
        long admissionNanos = 0;
        Store.JobView remote = null;
        long start = System.nanoTime();
        Validation baseline = system == null ? new Heuristics().baseline(p) : null;
        long baselineNanos = system == null ? System.nanoTime() - start : 0;
        List<Map<String, Object>> trace = new ArrayList<>();
        start = System.nanoTime();
        long origin = start;
        Validation optimized;
        if (system == null) {
          optimized =
              new TimefoldAdapter()
                  .solve(
                      p,
                      baseline,
                      new TimefoldAdapter.Settings(42, 30000, 100000, source),
                      () -> false,
                      v ->
                          trace.add(
                              Map.of(
                                  "elapsedNanos", System.nanoTime() - origin, "score", v.score())));
        } else {
          long admitted = System.nanoTime();
          system.request("/v1/problems", input, 201);
          admissionNanos = System.nanoTime() - admitted;
          remote = system.solve(input, "corpus-" + index + "-" + run, 30000, 100000);
          optimized = remote.incumbent();
          baseline =
              Json.read(
                  system.request("/v1/solve-jobs/" + remote.id() + "/baseline", null, 200),
                  Validation.class);
          checkBaseline(input, baseline);

          for (int offset = 0; offset <= 100000; offset += 1000) {
            List<Store.TracePoint> points =
                Json.readList(
                    system.request(
                        "/v1/solve-jobs/" + remote.id() + "/trace?offset=" + offset + "&limit=1000",
                        null,
                        200),
                    Store.TracePoint.class);
            for (Store.TracePoint point : points)
              if (point.authoritative())
                trace.add(
                    Map.of(
                        "elapsedNanos",
                        Duration.between(remote.startedAt(), point.at()).toNanos(),
                        "score",
                        point.score()));
            if (points.size() < 1000) break;
          }
        }
        long solveNanos = System.nanoTime() - start;
        if (remote != null && !trace.isEmpty())
          baselineNanos = ((Number) trace.getFirst().get("elapsedNanos")).longValue();
        Validation checked = new Validator().validate(p, optimized.candidate());
        if (!checked.valid() || checked.score().compareTo(baseline.score()) > 0)
          throw new AssertionError("invalid/degraded corpus result");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instance", index);
        m.put("run", run);
        m.put("seed", 4200 + index);
        m.put("difficulty", difficulty[index]);
        m.put("jobs", sizes[index]);
        m.put("vehicles", vehicles[index]);
        m.put("inputHash", p.canonicalHash());
        m.put("baseline", baseline.score());
        m.put("optimized", checked.score());
        m.put("feasibility", checked.feasibility());
        m.put("unassigned", checked.candidate().unassigned().size());
        m.put("baselineNanos", baselineNanos);
        m.put("solveNanos", solveNanos);
        m.put(
            "baselineBoundary",
            system == null
                ? "in-process complete deterministic baseline"
                : "worker claim to first durable validated candidate; bounded by solve budget");
        m.put("validationBenchmarkArtifact", "validation-jmh.json");
        m.put(
            "systemWarmup",
            "No excluded system warmup; run0 includes cold/JIT effects, report separately from runs1/2");
        m.put(
            "measuredBoundary",
            system == null
                ? "in-process validation and planning"
                : "live HTTP, durable queue, PostgreSQL and CPU solver");
        if (remote != null) {
          m.put(
              "queueLatencyNanos",
              Duration.between(remote.createdAt(), remote.startedAt()).toNanos());
          m.put("problemCreateHttpNanos", admissionNanos);
          m.put(
              "durableSolveNanos",
              Duration.between(remote.startedAt(), remote.finishedAt()).toNanos());
          m.put("serviceSource", remote.sourceRevision());
        }
        m.put(
            "clientUsedHeapBytes",
            Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
        m.put("scoreTrace", trace);
        m.put(
            "sameAssignedDemand",
            baseline
                .candidate()
                .unassigned()
                .keySet()
                .equals(checked.candidate().unassigned().keySet()));
        if (baseline
                .candidate()
                .unassigned()
                .keySet()
                .equals(checked.candidate().unassigned().keySet())
            && baseline.score().operatingCost() > 0)
          m.put(
              "operatingCostImprovementFraction",
              (baseline.score().operatingCost() - checked.score().operatingCost())
                  / (double) baseline.score().operatingCost());
        m.put("solverSeed", 42);
        m.put("solverBudgetMillis", 30000);
        m.put("solverStepLimit", 100000);
        if (remote != null) {
          m.put("algorithmVersion", remote.algorithmVersion());
          m.put("objectiveVersion", remote.configVersion());
          m.put("stopCondition", remote.stopCondition());
        }
        m.put("source", source);
        m.put("timestamp", Instant.now().toString());
        measurements.add(m);
        Files.writeString(output.resolve("corpus-raw.json"), Json.write(measurements));
        System.out.println(
            "measured instance=" + index + " run=" + run + " score=" + checked.score());
      }
  }
}
