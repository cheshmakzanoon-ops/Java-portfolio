package io.github.cheshmakzanoon.portfolio.switchback;

import static io.github.cheshmakzanoon.portfolio.switchback.Model.*;

import java.time.*;
import java.util.*;

/**
 * Reference evaluator: recomputes every directed arc and all residual constraints from raw orders.
 */
public final class Validator {
  public Validation validate(Problem p, Candidate c) {
    List<Violation> errors = new ArrayList<>();
    List<Timeline> timelines = new ArrayList<>();
    Map<String, String> reasons = new TreeMap<>();
    Map<String, Job> jobs = p.jobMap();
    Map<String, Vehicle> vehicles = new HashMap<>();
    p.vehicles().forEach(v -> vehicles.put(v.id(), v));
    Map<String, String> cargo = new HashMap<>();
    p.vehicles().forEach(v -> v.onboard().forEach(j -> cargo.put(j, v.id())));
    Set<String> seen = new HashSet<>(), seenVehicles = new HashSet<>();
    long cost = 0, disruption = 0, unassigned = 0;
    if (!p.canonicalHash().equals(c.inputHash()))
      add(errors, "INPUT_VERSION", "", "", "candidate input identity differs");
    for (Route r : c.routes()) {
      Vehicle v = vehicles.get(r.vehicleId());
      if (v == null) {
        add(errors, "UNKNOWN_VEHICLE", r.vehicleId(), "", "unknown vehicle");
        continue;
      }
      if (!seenVehicles.add(v.id()))
        add(errors, "DUPLICATE_ROUTE", v.id(), "", "vehicle has multiple routes");
      if (v.failed() && !r.jobs().isEmpty())
        add(errors, "FAILED_VEHICLE", v.id(), "", "failed vehicle cannot dispatch");
      if (r.jobs().size() < v.frozenPrefix().size()
          || !r.jobs()
              .subList(0, Math.min(r.jobs().size(), v.frozenPrefix().size()))
              .equals(v.frozenPrefix()))
        add(
            errors,
            "FROZEN_PREFIX",
            v.id(),
            "",
            "committed prefix changed; audited revision required");
      long load = v.remainingLoad();
      for (String id : r.jobs()) {
        Job j = jobs.get(id);
        if (j != null && !v.onboard().contains(id)) load = Math.addExact(load, j.grams());
      }
      if (load > v.capacityGrams())
        add(errors, "CAPACITY", v.id(), "", "loaded grams exceed capacity");
      Instant time = v.availableAt();
      int location = v.currentLocation();
      long meters = 0, seconds = 0;
      List<Stop> stops = new ArrayList<>();
      int index = 0;
      for (String id : r.jobs()) {
        Job j = jobs.get(id);
        if (j == null) {
          add(errors, "UNKNOWN_JOB", v.id(), id, "unknown or completed job");
          continue;
        }
        if (!seen.add(id))
          add(errors, "DUPLICATE_JOB", v.id(), id, "delivery assigned more than once");
        if (c.unassigned().containsKey(id))
          add(errors, "PARTITION", v.id(), id, "assigned and unassigned overlap");
        if (!v.skills().containsAll(j.skills()))
          add(errors, "SKILLS", v.id(), id, "required skills missing");
        if (cargo.containsKey(id) && !cargo.get(id).equals(v.id()))
          add(errors, "CARGO_LOCATION", v.id(), id, "cargo is aboard another vehicle");
        if (!v.onboard().contains(id) && v.currentLocation() != p.depot())
          add(errors, "DEPOT_CARGO", v.id(), id, "moving vehicle cannot acquire depot cargo");
        Arc arc = p.matrix().arc(location, j.location());
        seconds = Math.addExact(seconds, arc.seconds());
        meters = Math.addExact(meters, arc.meters());
        Instant arrival = time.plusSeconds(arc.seconds());
        Instant start = arrival.isBefore(j.windowStart()) ? j.windowStart() : arrival;
        if (start.isAfter(j.windowEnd()))
          add(errors, "WINDOW", v.id(), id, "service starts after inclusive window end");
        time = start.plusSeconds(j.serviceSeconds());
        if (time.isAfter(v.shiftEnd()))
          add(errors, "SHIFT", v.id(), id, "service ends beyond shift");
        load = Math.subtractExact(load, j.grams());
        if (load < 0) add(errors, "NEGATIVE_LOAD", v.id(), id, "delivery exceeds remaining cargo");
        stops.add(new Stop(id, j.location(), arrival, start, time, load));
        location = j.location();
        Previous old = p.previous().get(id);
        if (old != null && (!old.vehicleId().equals(v.id()) || old.index() != index))
          disruption = Math.addExact(disruption, p.objective().disruptionWeight());
        index++;
      }
      if (!v.failed()) {
        Arc back = p.matrix().arc(location, p.depot());
        seconds = Math.addExact(seconds, back.seconds());
        meters = Math.addExact(meters, back.meters());
        time = time.plusSeconds(back.seconds());
        if (time.isAfter(v.shiftEnd()))
          add(errors, "RETURN_SHIFT", v.id(), "", "return leg exceeds shift");
      }
      cost =
          Math.addExact(
              cost,
              Math.addExact(
                  Math.multiplyExact(meters, p.objective().metersWeight()),
                  Math.multiplyExact(seconds, p.objective().secondsWeight())));
      timelines.add(new Timeline(v.id(), stops, time, meters, seconds));
    }
    for (Vehicle v : p.vehicles())
      if (!seenVehicles.contains(v.id()))
        add(errors, "MISSING_ROUTE", v.id(), "", "empty vehicle routes must be explicit");
    for (Job j : p.jobs())
      if (!seen.contains(j.id()) && !c.unassigned().containsKey(j.id()))
        add(errors, "PARTITION", "", j.id(), "job missing from assigned/unassigned partition");
    for (String id : c.unassigned().keySet()) {
      Job j = jobs.get(id);
      if (j == null) {
        add(errors, "UNKNOWN_UNASSIGNED", "", id, "not a remaining job");
        continue;
      }
      unassigned = Math.addExact(unassigned, j.priority());
      String reason = reason(p, j, cargo);
      reasons.put(id, reason);
      if (p.previous().containsKey(id))
        disruption = Math.addExact(disruption, p.objective().disruptionWeight());
    }
    return new Validation(
        c, new Score(errors.size(), unassigned, cost, disruption), errors, timelines, reasons);
  }

  private String reason(Problem p, Job j, Map<String, String> cargo) {
    if (cargo.containsKey(j.id())
        && p.vehicles().stream().anyMatch(v -> v.id().equals(cargo.get(j.id())) && v.failed()))
      return "STRANDED_CARGO";
    List<Vehicle> eligible =
        p.vehicles().stream()
            .filter(v -> !v.failed() && v.skills().containsAll(j.skills()))
            .toList();
    if (eligible.isEmpty()) return "SKILLS_OR_VEHICLE_UNAVAILABLE";
    if (eligible.stream().noneMatch(v -> v.capacityGrams() >= j.grams())) return "CAPACITY";
    if (cargo.containsKey(j.id()))
      return "ONBOARD_UNSCHEDULED: capacity/time/freeze interactions; no global infeasibility proof";
    if (eligible.stream().noneMatch(v -> v.currentLocation() == p.depot()))
      return "DEPOT_CARGO: no eligible depot vehicle";
    return "SEARCH_UNASSIGNED: window/shift/capacity interactions; heuristic failure is not proof of infeasibility";
  }

  private static void add(
      List<Violation> e, String code, String vehicle, String job, String message) {
    e.add(new Violation(code, vehicle, job, message));
  }
}
