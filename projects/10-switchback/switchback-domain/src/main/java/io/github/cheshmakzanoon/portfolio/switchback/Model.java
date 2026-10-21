package io.github.cheshmakzanoon.portfolio.switchback;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** All units are integral: seconds, meters, grams. Snapshots are immutable and tenant scoped. */
public final class Model {
  private Model() {}

  private static final Map<Problem, String> HASHES =
      Collections.synchronizedMap(new IdentityHashMap<>());
  public static final int MAX_JOBS = 500, MAX_VEHICLES = 25, MAX_LOCATIONS = 501;
  // Even a malformed maximum-sized route must remain representable during independent validation.
  private static final long MAX_ROUTE_SECONDS = (MAX_JOBS + 1L) * 10_000_000 + MAX_JOBS * 604_800L;
  public static final Instant MAX_INPUT_INSTANT =
      Instant.ofEpochSecond(Instant.MAX.getEpochSecond() - MAX_ROUTE_SECONDS);

  public record Arc(int seconds, int meters) {
    public Arc {
      bounded(seconds, 0, 10_000_000, "seconds");
      bounded(meters, 0, 100_000_000, "meters");
    }
  }

  public record Matrix(String version, List<List<Arc>> entries) {
    public Matrix {
      text(version);
      bounded(entries.size(), 1, MAX_LOCATIONS, "locations");
      entries = entries.stream().map(List::copyOf).toList();
      for (var row : entries) {
        if (row.size() != entries.size() || row.stream().anyMatch(Objects::isNull))
          throw new IllegalArgumentException("complete square matrix required");
      }
      for (int i = 0; i < entries.size(); i++)
        if (entries.get(i).get(i).seconds() != 0 || entries.get(i).get(i).meters() != 0)
          throw new IllegalArgumentException("diagonal must be zero");
    }

    public Arc arc(int from, int to) {
      return entries.get(from).get(to);
    }
  }

  public record Job(
      String id,
      int location,
      int grams,
      int serviceSeconds,
      Instant windowStart,
      Instant windowEnd,
      Set<String> skills,
      int priority) {
    public Job {
      text(id);
      bounded(location, 0, MAX_LOCATIONS - 1, "location");
      bounded(grams, 0, 1_000_000_000, "grams");
      bounded(serviceSeconds, 0, 604800, "service");
      timeRange(windowStart, windowEnd);
      skills = Set.copyOf(skills);
      bounded(skills.size(), 0, 32, "skills");
      skills.forEach(Model::text);
      bounded(priority, 1, 1_000_000, "priority");
    }
  }

  public record Vehicle(
      String id,
      int capacityGrams,
      Set<String> skills,
      Instant shiftStart,
      Instant shiftEnd,
      int currentLocation,
      Instant availableAt,
      Set<String> onboard,
      int remainingLoad,
      List<String> frozenPrefix,
      boolean failed) {
    public Vehicle {
      text(id);
      bounded(capacityGrams, 0, 1_000_000_000, "capacity");
      skills = Set.copyOf(skills);
      bounded(skills.size(), 0, 32, "skills");
      skills.forEach(Model::text);
      timeRange(shiftStart, shiftEnd);
      instant(availableAt);
      bounded(currentLocation, 0, MAX_LOCATIONS - 1, "current location");
      onboard = Set.copyOf(onboard);
      frozenPrefix = List.copyOf(frozenPrefix);
      bounded(remainingLoad, 0, capacityGrams, "remaining load");
      if (availableAt.isBefore(shiftStart) || availableAt.isAfter(shiftEnd))
        throw new IllegalArgumentException("availability outside shift");
      if (new HashSet<>(frozenPrefix).size() != frozenPrefix.size())
        throw new IllegalArgumentException("duplicate frozen stop");
    }
  }

  public record Completed(String jobId, String vehicleId, Instant completedAt) {
    public Completed {
      text(jobId);
      text(vehicleId);
      instant(completedAt);
    }
  }

  public record Objective(
      String version, int metersWeight, int secondsWeight, int disruptionWeight) {
    public Objective {
      text(version);
      bounded(metersWeight, 0, 1000, "distance weight");
      bounded(secondsWeight, 0, 1000, "time weight");
      bounded(disruptionWeight, 0, 1_000_000, "disruption weight");
    }
  }

  public record Previous(String vehicleId, int index) {
    public Previous {
      text(vehicleId);
      bounded(index, 0, MAX_JOBS, "previous index");
    }
  }

  public record Problem(
      String tenant,
      String id,
      long version,
      ZoneId serviceZone,
      int depot,
      Instant planningInstant,
      Matrix matrix,
      List<Job> jobs,
      List<Vehicle> vehicles,
      List<Completed> completed,
      Map<String, Previous> previous,
      Objective objective,
      List<String> audit) {
    public Problem {
      text(tenant);
      text(id);
      if (!id.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,199}"))
        throw new IllegalArgumentException("problem id must be a URL-safe single segment");
      if (version < 1) throw new IllegalArgumentException("positive version required");
      Objects.requireNonNull(serviceZone);
      instant(planningInstant);
      Objects.requireNonNull(matrix);
      Objects.requireNonNull(objective);
      jobs = List.copyOf(jobs);
      vehicles = List.copyOf(vehicles);
      completed = List.copyOf(completed);
      previous = Map.copyOf(previous);
      audit = List.copyOf(audit);
      bounded(completed.size(), 0, 10000, "completed history");
      bounded(audit.size(), 0, 1000, "audit entries");
      for (String a : audit)
        if (a.length() > 2000) throw new IllegalArgumentException("audit entry bound");
      bounded(jobs.size(), 0, MAX_JOBS, "jobs");
      bounded(vehicles.size(), 1, MAX_VEHICLES, "vehicles");
      bounded(depot, 0, matrix.entries.size() - 1, "depot");
      Set<String> ids = new HashSet<>(),
          history = new HashSet<>(),
          vIds = new HashSet<>(),
          cargo = new HashSet<>();
      for (Job j : jobs) {
        if (!ids.add(j.id)) throw new IllegalArgumentException("duplicate job");
        bounded(j.location, 0, matrix.entries.size() - 1, "job location");
      }
      for (Completed c : completed) {
        if (!history.add(c.jobId) || ids.contains(c.jobId))
          throw new IllegalArgumentException("completed jobs cannot remain input");
        if (c.completedAt.isAfter(planningInstant))
          throw new IllegalArgumentException("future completed history");
      }
      for (Vehicle v : vehicles) {
        if (!vIds.add(v.id)) throw new IllegalArgumentException("duplicate vehicle");
        bounded(v.currentLocation, 0, matrix.entries.size() - 1, "vehicle location");
        if (v.availableAt.isBefore(planningInstant))
          throw new IllegalArgumentException("residual availability precedes planning instant");
        long load = 0;
        for (String job : v.onboard) {
          if (!ids.contains(job) || !cargo.add(job))
            throw new IllegalArgumentException("unknown or duplicate cargo");
          load =
              Math.addExact(
                  load,
                  jobs.stream().filter(j -> j.id.equals(job)).findFirst().orElseThrow().grams);
        }
        if (load != v.remainingLoad) throw new IllegalArgumentException("onboard load mismatch");
        for (String f : v.frozenPrefix)
          if (!ids.contains(f)) throw new IllegalArgumentException("unknown frozen job");
      }
      for (Completed c : completed)
        if (!vIds.contains(c.vehicleId))
          throw new IllegalArgumentException("unknown completed vehicle");
      Set<Previous> previousPositions = new HashSet<>();
      for (var e : previous.entrySet()) {
        if (!ids.contains(e.getKey()) || !vIds.contains(e.getValue().vehicleId))
          throw new IllegalArgumentException("invalid previous assignment");
        if (!previousPositions.add(e.getValue()))
          throw new IllegalArgumentException("previous route positions must be unique per vehicle");
      }
    }

    public Map<String, Job> jobMap() {
      Map<String, Job> map = new LinkedHashMap<>();
      jobs.forEach(j -> map.put(j.id, j));
      return Map.copyOf(map);
    }

    /**
     * Canonical byte representation is ordered explicitly and never relies on hash-map iteration.
     */
    public String canonicalHash() {
      String cached = HASHES.get(this);
      if (cached != null) return cached;
      StringBuilder b = new StringBuilder();
      append(
          b,
          tenant,
          id,
          version,
          serviceZone.getId(),
          depot,
          planningInstant,
          matrix.version,
          objective);
      for (var row : matrix.entries) for (var a : row) append(b, a.seconds, a.meters);
      jobs.stream()
          .sorted(Comparator.comparing(Job::id))
          .forEach(
              j ->
                  append(
                      b,
                      j.id,
                      j.location,
                      j.grams,
                      j.serviceSeconds,
                      j.windowStart,
                      j.windowEnd,
                      new TreeSet<>(j.skills),
                      j.priority));
      vehicles.stream()
          .sorted(Comparator.comparing(Vehicle::id))
          .forEach(
              v ->
                  append(
                      b,
                      v.id,
                      v.capacityGrams,
                      new TreeSet<>(v.skills),
                      v.shiftStart,
                      v.shiftEnd,
                      v.currentLocation,
                      v.availableAt,
                      new TreeSet<>(v.onboard),
                      v.remainingLoad,
                      v.frozenPrefix,
                      v.failed));
      completed.forEach(c -> append(b, c));
      new TreeMap<>(previous).forEach((k, v) -> append(b, k, v));
      audit.forEach(a -> append(b, a));
      try {
        String hash =
            HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(b.toString().getBytes(StandardCharsets.UTF_8)));
        synchronized (HASHES) {
          if (HASHES.size() > 32) HASHES.clear();
          HASHES.put(this, hash);
        }
        return hash;
      } catch (java.security.NoSuchAlgorithmException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  public record Route(String vehicleId, List<String> jobs) {
    public Route {
      text(vehicleId);
      bounded(jobs.size(), 0, MAX_JOBS, "route stops");
      jobs = List.copyOf(jobs);
      jobs.forEach(Model::text);
    }
  }

  public record Candidate(String inputHash, List<Route> routes, Map<String, String> unassigned) {
    public Candidate {
      text(inputHash);
      bounded(routes.size(), 0, MAX_VEHICLES, "candidate routes");
      bounded(unassigned.size(), 0, MAX_JOBS, "unassigned jobs");
      routes = List.copyOf(routes);
      unassigned = Map.copyOf(unassigned);
      unassigned.keySet().forEach(Model::text);
      for (String reason : unassigned.values())
        if (reason.length() > 2000) throw new IllegalArgumentException("unassigned reason bound");
    }
  }

  public record Score(long hard, long unassignedPriority, long operatingCost, long disruption)
      implements Comparable<Score> {
    public Score {
      if (hard < 0 || unassignedPriority < 0 || operatingCost < 0 || disruption < 0)
        throw new IllegalArgumentException("negative score component");
      Math.addExact(operatingCost, disruption);
    }

    public long soft() {
      return Math.addExact(operatingCost, disruption);
    }

    @Override
    public int compareTo(Score other) {
      int c = Long.compare(hard, other.hard);
      if (c == 0) c = Long.compare(unassignedPriority, other.unassignedPriority);
      if (c == 0) c = Long.compare(soft(), other.soft());
      return c;
    }
  }

  public record Violation(String code, String vehicle, String job, String message) {}

  public record Stop(
      String jobId,
      int location,
      Instant arrival,
      Instant serviceStart,
      Instant departure,
      long loadAfter) {}

  public record Timeline(
      String vehicleId, List<Stop> stops, Instant returnedAt, long meters, long travelSeconds) {
    public Timeline {
      stops = List.copyOf(stops);
    }
  }

  public record Validation(
      Candidate candidate,
      Score score,
      List<Violation> violations,
      List<Timeline> timelines,
      Map<String, String> explanations) {
    public Validation {
      violations = List.copyOf(violations);
      timelines = List.copyOf(timelines);
      explanations = Map.copyOf(explanations);
    }

    public boolean valid() {
      return violations.isEmpty();
    }

    public String feasibility() {
      return !valid()
          ? "HARD_VIOLATIONS"
          : candidate.unassigned.isEmpty() ? "FULLY_FEASIBLE" : "PARTIAL_FEASIBLE";
    }
  }

  public static Candidate candidate(Problem p, List<Route> routes) {
    Set<String> assigned = new HashSet<>();
    routes.forEach(r -> assigned.addAll(r.jobs));
    Map<String, String> unassigned = new TreeMap<>();
    p.jobs.forEach(
        j -> {
          if (!assigned.contains(j.id)) unassigned.put(j.id, "UNASSIGNED");
        });
    return new Candidate(p.canonicalHash(), routes, unassigned);
  }

  public static Instant importLocal(LocalDateTime time, ZoneId zone, ZoneOffset explicitOffset) {
    List<ZoneOffset> offsets = zone.getRules().getValidOffsets(time);
    if (offsets.isEmpty()) throw new IllegalArgumentException("nonexistent local time");
    if (explicitOffset == null && offsets.size() > 1)
      throw new IllegalArgumentException("ambiguous local time requires offset");
    ZoneOffset offset = explicitOffset == null ? offsets.getFirst() : explicitOffset;
    if (!offsets.contains(offset))
      throw new IllegalArgumentException("offset incompatible with zone");
    return time.toInstant(offset);
  }

  private static void append(StringBuilder b, Object... values) {
    for (Object v : values) {
      String s = String.valueOf(v);
      b.append(s.length()).append(':').append(s);
    }
  }

  public static void text(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 200
        || !value.matches("[a-zA-Z0-9_.:/@+ -]+"))
      throw new IllegalArgumentException("invalid bounded identifier");
  }

  private static void instant(Instant value) {
    Objects.requireNonNull(value);
    if (value.getNano() != 0)
      throw new IllegalArgumentException("integer second instants required");
    if (value.isAfter(MAX_INPUT_INSTANT))
      throw new IllegalArgumentException("instant leaves no bounded route arithmetic headroom");
  }

  private static void timeRange(Instant a, Instant b) {
    instant(a);
    instant(b);
    if (a.isAfter(b) || Duration.between(a, b).getSeconds() > 604800)
      throw new IllegalArgumentException("time range must be ordered and <=7 days");
  }

  private static void bounded(long x, long min, long max, String name) {
    if (x < min || x > max)
      throw new IllegalArgumentException(name + " outside [" + min + "," + max + "]");
  }
}
