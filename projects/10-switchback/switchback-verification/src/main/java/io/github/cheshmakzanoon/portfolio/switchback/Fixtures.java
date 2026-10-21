package io.github.cheshmakzanoon.portfolio.switchback;

import static io.github.cheshmakzanoon.portfolio.switchback.Model.*;

import java.time.*;
import java.util.*;

/** Synthetic Atlantic-area coordinates and directed travel. Never real road or traffic data. */
public final class Fixtures {
  private Fixtures() {}

  public static final Instant START = Instant.parse("2026-10-17T11:00:00Z");

  public static Problem problem(int count, int vehicleCount, long seed, String difficulty) {
    if (count < 0
        || count > Model.MAX_JOBS
        || vehicleCount < 1
        || vehicleCount > Model.MAX_VEHICLES)
      throw new IllegalArgumentException("fixture size outside supported domain bounds");
    if (!Set.of("easy", "tight", "overconstrained").contains(difficulty))
      throw new IllegalArgumentException("unknown fixture difficulty");
    Random random = new Random(seed);
    List<List<Arc>> matrix = new ArrayList<>();
    int[] x = new int[count + 1], y = new int[count + 1];
    for (int i = 1; i <= count; i++) {
      x[i] = random.nextInt(100);
      y[i] = random.nextInt(100);
    }
    for (int i = 0; i <= count; i++) {
      List<Arc> row = new ArrayList<>();
      for (int j = 0; j <= count; j++) {
        int distance =
            (Math.abs(x[i] - x[j]) + Math.abs(y[i] - y[j])) * 100 + (i == j ? 0 : 17 * (j % 5));
        row.add(new Arc(i == j ? 0 : distance / 15 + 20 + (i % 7) * 3, distance));
      }
      matrix.add(row);
    }
    List<Job> jobs = new ArrayList<>();
    for (int i = 1; i <= count; i++) {
      long end =
          "overconstrained".equals(difficulty) ? 1800 : "tight".equals(difficulty) ? 18000 : 40000;
      jobs.add(
          new Job(
              String.format("J%03d", i),
              i,
              1000,
              30,
              START,
              START.plusSeconds(end),
              Set.of("normal"),
              1 + i % 5));
    }
    List<Vehicle> vehicles = new ArrayList<>();
    for (int i = 0; i < vehicleCount; i++)
      vehicles.add(
          new Vehicle(
              "V" + i,
              Math.max(1000, count * 1500 / Math.max(1, vehicleCount - 1)),
              Set.of("normal", "urgent"),
              START,
              START.plusSeconds(43200),
              0,
              START,
              Set.of(),
              0,
              List.of(),
              false));
    return new Problem(
        "demo",
        "switchback-" + count + "-" + seed,
        1,
        ZoneId.of("America/Halifax"),
        0,
        START,
        new Matrix("synthetic-directed-v1", matrix),
        jobs,
        vehicles,
        List.of(),
        Map.of(),
        new Objective("meters-seconds-disruption-v1", 1, 1, 1000),
        List.of("SYNTHETIC_LOCATIONS_AND_TRAVEL"));
  }

  public static Problem improvable() {
    Problem p = problem(100, 5, 4209, "easy");
    List<List<Arc>> matrix = new ArrayList<>();
    for (int i = 0; i <= 100; i++) {
      List<Arc> row = new ArrayList<>();
      for (int j = 0; j <= 100; j++)
        row.add(i == j ? new Arc(0, 0) : new Arc(1, j == i + 1 || i == 100 && j == 0 ? 1 : 1000));
      matrix.add(row);
    }
    List<Job> jobs =
        p.jobs().stream()
            .map(
                j ->
                    new Job(
                        j.id(),
                        j.location(),
                        j.grams(),
                        j.serviceSeconds(),
                        j.windowStart(),
                        j.windowEnd(),
                        j.skills(),
                        1))
            .toList();
    List<Vehicle> vehicles =
        p.vehicles().stream()
            .map(
                v ->
                    new Vehicle(
                        v.id(),
                        100000,
                        v.skills(),
                        v.shiftStart(),
                        v.shiftEnd(),
                        v.currentLocation(),
                        v.availableAt(),
                        v.onboard(),
                        0,
                        v.frozenPrefix(),
                        false))
            .toList();
    return new Problem(
        p.tenant(),
        p.id(),
        p.version(),
        p.serviceZone(),
        p.depot(),
        p.planningInstant(),
        new Matrix("preregistered-directed-cost-trap-v1", matrix),
        jobs,
        vehicles,
        p.completed(),
        p.previous(),
        p.objective(),
        List.of("SYNTHETIC_IMPROVABLE_SECONDS_VERSUS_METERS_TRAP"));
  }

  public static Problem demo() {
    Problem p = problem(60, 6, 42, "easy");
    List<Vehicle> vehicles = new ArrayList<>(p.vehicles());
    for (int i = 0; i < 5; i++) {
      Vehicle regular = vehicles.get(i);
      vehicles.set(
          i,
          new Vehicle(
              regular.id(),
              regular.capacityGrams(),
              Set.of("normal"),
              regular.shiftStart(),
              regular.shiftEnd(),
              0,
              regular.availableAt(),
              Set.of(),
              0,
              List.of(),
              false));
    }
    Vehicle v = vehicles.get(5);
    vehicles.set(
        5,
        new Vehicle(
            v.id(),
            v.capacityGrams(),
            Set.of("urgent"),
            v.shiftStart(),
            v.shiftEnd(),
            0,
            v.availableAt(),
            Set.of(),
            0,
            List.of(),
            false));
    return new Problem(
        p.tenant(),
        p.id(),
        p.version(),
        p.serviceZone(),
        p.depot(),
        p.planningInstant(),
        p.matrix(),
        p.jobs(),
        vehicles,
        p.completed(),
        p.previous(),
        p.objective(),
        p.audit());
  }
}
