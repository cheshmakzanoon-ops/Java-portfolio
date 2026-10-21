package io.github.cheshmakzanoon.portfolio.switchback;

import static io.github.cheshmakzanoon.portfolio.switchback.Model.*;

import java.time.Instant;
import java.util.*;

/**
 * Replans only at observed stop boundaries. No mid-road interpolation, cargo transfer or reloading.
 */
public final class Replanner {
  public record Observation(
      String vehicleId,
      int completedPrefix,
      boolean broken,
      int freezeNext,
      boolean releaseFailedCommitments) {
    public Observation {
      Model.text(vehicleId);
      if (completedPrefix < 0 || freezeNext < 0)
        throw new IllegalArgumentException("negative prefix");
    }
  }

  public Problem replan(
      Problem old,
      Candidate published,
      Instant planningInstant,
      List<Observation> observations,
      List<Job> urgent) {
    Validation validated = new Validator().validate(old, published);
    if (!validated.valid()) throw new IllegalArgumentException("invalid old published plan");
    if (planningInstant.isBefore(old.planningInstant()))
      throw new IllegalArgumentException("planning time went backwards");
    Map<String, Observation> byVehicle = new HashMap<>();
    for (Observation o : observations)
      if (byVehicle.put(o.vehicleId(), o) != null)
        throw new IllegalArgumentException("duplicate observation");
    for (String id : byVehicle.keySet())
      if (old.vehicles().stream().noneMatch(v -> v.id().equals(id)))
        throw new IllegalArgumentException("unknown observed vehicle");
    List<Completed> completed = new ArrayList<>(old.completed());
    Set<String> done = new HashSet<>();
    List<Vehicle> vehicles = new ArrayList<>();
    List<String> audit = new ArrayList<>(old.audit());
    Map<String, Previous> previous = new TreeMap<>();
    Map<String, Job> jobs = old.jobMap();
    for (Vehicle v : old.vehicles()) {
      Route route =
          published.routes().stream()
              .filter(r -> r.vehicleId().equals(v.id()))
              .findFirst()
              .orElseThrow();
      Timeline timeline =
          validated.timelines().stream()
              .filter(t -> t.vehicleId().equals(v.id()))
              .findFirst()
              .orElseThrow();
      Observation o =
          byVehicle.getOrDefault(
              v.id(), new Observation(v.id(), 0, v.failed(), v.frozenPrefix().size(), false));
      if (o.completedPrefix() > route.jobs().size())
        throw new IllegalArgumentException("observed prefix exceeds route");
      int location = v.currentLocation();
      Instant available = v.availableAt();
      Set<String> onboard = new HashSet<>(v.onboard());
      if (v.currentLocation() == old.depot()) onboard.addAll(route.jobs());
      for (int i = 0; i < o.completedPrefix(); i++) {
        Stop stop = timeline.stops().get(i);
        if (stop.departure().isAfter(planningInstant))
          throw new IllegalArgumentException("completed stop is after observed instant");
        done.add(stop.jobId());
        completed.add(new Completed(stop.jobId(), v.id(), stop.departure()));
        onboard.remove(stop.jobId());
        location = stop.location();
        available = stop.departure();
      }
      if (available.isBefore(planningInstant)) available = planningInstant;
      List<String> remaining = route.jobs().subList(o.completedPrefix(), route.jobs().size());
      int freeze =
          Math.max(
              Math.max(0, v.frozenPrefix().size() - o.completedPrefix()),
              Math.min(o.freezeNext(), remaining.size()));
      List<String> frozen = List.copyOf(remaining.subList(0, Math.min(freeze, remaining.size())));
      if (o.broken() && !frozen.isEmpty()) {
        if (!o.releaseFailedCommitments())
          throw new IllegalArgumentException(
              "BLOCKED_FROZEN_FAILED: explicit audited release required");
        audit.add(
            "RELEASE_FAILED_COMMITMENTS vehicle="
                + v.id()
                + " jobs="
                + String.join(",", frozen)
                + " sourceVersion="
                + old.version());
        frozen = List.of();
      }
      for (int i = 0; i < remaining.size(); i++)
        previous.put(remaining.get(i), new Previous(v.id(), i));
      long load = 0;
      for (String id : onboard) load = Math.addExact(load, jobs.get(id).grams());
      vehicles.add(
          new Vehicle(
              v.id(),
              v.capacityGrams(),
              v.skills(),
              v.shiftStart(),
              v.shiftEnd(),
              location,
              available,
              onboard,
              Math.toIntExact(load),
              frozen,
              o.broken()));
    }
    List<Job> remaining =
        new ArrayList<>(old.jobs().stream().filter(j -> !done.contains(j.id())).toList());
    remaining.addAll(urgent);
    audit.add(
        "OBSERVED_STOP_BOUNDARY version="
            + old.version()
            + " completed="
            + new TreeSet<>(done)
            + " urgent="
            + urgent.stream().map(Job::id).toList());
    return new Problem(
        old.tenant(),
        old.id(),
        Math.addExact(old.version(), 1),
        old.serviceZone(),
        old.depot(),
        planningInstant,
        old.matrix(),
        remaining,
        vehicles,
        completed,
        previous,
        old.objective(),
        audit);
  }

  public Validation repair(Problem revised, Candidate old) {
    List<Route> routes = new ArrayList<>();
    Set<String> remaining = revised.jobMap().keySet();
    for (Vehicle v : revised.vehicles()) {
      List<String> jobs =
          v.failed()
              ? List.of()
              : old.routes().stream()
                  .filter(r -> r.vehicleId().equals(v.id()))
                  .findFirst()
                  .map(r -> r.jobs().stream().filter(remaining::contains).toList())
                  .orElse(List.of());
      routes.add(new Route(v.id(), jobs));
    }
    return new Validator().validate(revised, Model.candidate(revised, routes));
  }
}
