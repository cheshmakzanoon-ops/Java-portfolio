package io.github.cheshmakzanoon.portfolio.switchback;

import static io.github.cheshmakzanoon.portfolio.switchback.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ModelTest {
  static final Instant N = Instant.parse("2026-10-17T23:00:00Z");

  static Matrix matrix() {
    return new Matrix(
        "m", List.of(List.of(new Arc(0, 0), new Arc(1, 1)), List.of(new Arc(2, 2), new Arc(0, 0))));
  }

  static Job job() {
    return new Job("j", 1, 1, 1, N, N.plusSeconds(100), Set.of(), 1);
  }

  static Vehicle vehicle() {
    return new Vehicle(
        "v", 2, Set.of(), N, N.plusSeconds(1000), 0, N, Set.of(), 0, List.of(), false);
  }

  static Problem p(
      List<Job> jobs, List<Vehicle> vs, List<Completed> completed, Map<String, Previous> previous) {
    return new Problem(
        "tenant",
        "p",
        1,
        ZoneId.of("America/Halifax"),
        0,
        N,
        matrix(),
        jobs,
        vs,
        completed,
        previous,
        new Objective("o", 1, 1, 1),
        List.of());
  }

  @Test
  void timeZonesAmbiguousNonexistentAndCrossMidnight() {
    ZoneId z = ZoneId.of("America/Halifax");
    assertThrows(
        IllegalArgumentException.class,
        () -> Model.importLocal(LocalDateTime.parse("2026-11-01T01:30:00"), z, null));
    Instant
        a =
            Model.importLocal(
                LocalDateTime.parse("2026-11-01T01:30:00"), z, ZoneOffset.ofHours(-3)),
        b =
            Model.importLocal(
                LocalDateTime.parse("2026-11-01T01:30:00"), z, ZoneOffset.ofHours(-4));
    assertEquals(3600, Duration.between(a, b).getSeconds());
    assertThrows(
        IllegalArgumentException.class,
        () -> Model.importLocal(LocalDateTime.parse("2026-03-08T02:30:00"), z, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> Model.importLocal(LocalDateTime.parse("2026-10-17T12:00:00"), z, ZoneOffset.UTC));
    assertEquals(
        Instant.parse("2026-10-17T15:00:00Z"),
        Model.importLocal(LocalDateTime.parse("2026-10-17T12:00:00"), z, null));
    Vehicle night =
        new Vehicle(
            "night",
            1,
            Set.of(),
            Instant.parse("2026-10-18T01:00:00Z"),
            Instant.parse("2026-10-18T09:00:00Z"),
            0,
            Instant.parse("2026-10-18T01:00:00Z"),
            Set.of(),
            0,
            List.of(),
            false);
    assertEquals(8, Duration.between(night.shiftStart(), night.shiftEnd()).toHours());
  }

  @Test
  void canonicalIdentityAndDeepImmutability() {
    Problem a = p(List.of(job()), List.of(vehicle()), List.of(), Map.of()),
        b = p(List.of(job()), List.of(vehicle()), List.of(), Map.of());
    assertEquals(a.canonicalHash(), b.canonicalHash());
    assertEquals(64, a.canonicalHash().length());
    assertThrows(UnsupportedOperationException.class, () -> a.jobs().clear());
    assertThrows(
        UnsupportedOperationException.class, () -> a.matrix().entries().getFirst().clear());
    Candidate c = Model.candidate(a, List.of(new Route("v", List.of("j"))));
    assertTrue(c.unassigned().isEmpty());
    assertEquals(1, a.jobMap().size());
    assertEquals(2, a.matrix().arc(1, 0).seconds());
  }

  @Test
  void rejectsBoundsMalformedMatrixAndTime() {
    assertThrows(IllegalArgumentException.class, () -> new Arc(-1, 0));
    assertThrows(IllegalArgumentException.class, () -> new Arc(0, 100_000_001));
    assertThrows(IllegalArgumentException.class, () -> new Matrix("m", List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Matrix("m", List.of(List.of(new Arc(0, 0), new Arc(1, 1)))));
    assertThrows(
        IllegalArgumentException.class, () -> new Matrix("m", List.of(List.of(new Arc(1, 0)))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Job("j", 1, 1, 1, N.plusSeconds(1), N, Set.of(), 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Job("j", 1, 1, 1, N, N.plusSeconds(604801), Set.of(), 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Job("j", 1, 1, 1, N.plusNanos(1), N.plusSeconds(100), Set.of(), 1));
    assertThrows(IllegalArgumentException.class, () -> Model.text("<script>"));
    assertThrows(IllegalArgumentException.class, () -> Model.text(null));
    assertThrows(IllegalArgumentException.class, () -> new Score(-1, 0, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> new Previous("v", -1));
  }

  @Test
  void rejectsResidualInconsistencyAndCompletedOverlap() {
    assertThrows(
        IllegalArgumentException.class,
        () -> p(List.of(job(), job()), List.of(vehicle()), List.of(), Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> p(List.of(job()), List.of(vehicle(), vehicle()), List.of(), Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> p(List.of(job()), List.of(vehicle()), List.of(new Completed("j", "v", N)), Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            p(List.of(), List.of(vehicle()), List.of(new Completed("x", "unknown", N)), Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            p(
                List.of(),
                List.of(vehicle()),
                List.of(new Completed("x", "v", N.plusSeconds(1))),
                Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            p(
                List.of(),
                List.of(vehicle()),
                List.of(new Completed("x", "v", N), new Completed("x", "v", N)),
                Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            p(
                List.of(job()),
                List.of(vehicle()),
                List.of(),
                Map.of("missing", new Previous("v", 0))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Vehicle(
                "v",
                2,
                Set.of(),
                N,
                N.plusSeconds(100),
                0,
                N.minusSeconds(1),
                Set.of(),
                0,
                List.of(),
                false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Vehicle(
                "v",
                2,
                Set.of(),
                N,
                N.plusSeconds(100),
                0,
                N,
                Set.of(),
                0,
                List.of("j", "j"),
                false));
    Vehicle wrong =
        new Vehicle(
            "v", 2, Set.of(), N, N.plusSeconds(100), 0, N, Set.of("j"), 2, List.of(), false);
    assertThrows(
        IllegalArgumentException.class,
        () -> p(List.of(job()), List.of(wrong), List.of(), Map.of()));
    Vehicle unknown =
        new Vehicle(
            "v", 2, Set.of(), N, N.plusSeconds(100), 0, N, Set.of("missing"), 1, List.of(), false);
    assertThrows(
        IllegalArgumentException.class,
        () -> p(List.of(job()), List.of(unknown), List.of(), Map.of()));
    Vehicle freeze =
        new Vehicle(
            "v", 2, Set.of(), N, N.plusSeconds(100), 0, N, Set.of(), 0, List.of("missing"), false);
    assertThrows(
        IllegalArgumentException.class,
        () -> p(List.of(job()), List.of(freeze), List.of(), Map.of()));
  }

  @Test
  void lexicographicScoreAndExplicitDispatchability() {
    assertTrue(new Score(0, 100, 999999, 0).compareTo(new Score(1, 0, 0, 0)) < 0);
    assertTrue(new Score(0, 0, 999999, 0).compareTo(new Score(0, 1, 0, 0)) < 0);
    assertTrue(new Score(0, 0, 10, 1).compareTo(new Score(0, 0, 10, 2)) < 0);
    assertEquals(0, new Score(0, 0, 10, 2).compareTo(new Score(0, 0, 11, 1)));
    assertEquals(12, new Score(0, 0, 10, 2).soft());
    assertThrows(IllegalArgumentException.class, () -> new Score(0, -1, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> new Score(0, 0, -1, 0));
    assertThrows(IllegalArgumentException.class, () -> new Score(0, 0, 0, -1));
    assertThrows(ArithmeticException.class, () -> new Score(0, 0, Long.MAX_VALUE, 1));
    Problem p = p(List.of(job()), List.of(vehicle()), List.of(), Map.of());
    Candidate assigned = Model.candidate(p, List.of(new Route("v", List.of("j"))));
    Validation full =
        new Validation(assigned, new Score(0, 0, 1, 0), List.of(), List.of(), Map.of());
    assertTrue(full.valid());
    assertEquals("FULLY_FEASIBLE", full.feasibility());
    Validation partial =
        new Validation(
            Model.candidate(p, List.of(new Route("v", List.of()))),
            new Score(0, 1, 0, 0),
            List.of(),
            List.of(),
            Map.of("j", "SEARCH_UNASSIGNED"));
    assertEquals("PARTIAL_FEASIBLE", partial.feasibility());
    Validation invalid =
        new Validation(
            assigned,
            new Score(1, 0, 0, 0),
            List.of(new Violation("DUPLICATE_JOB", "v", "j", "duplicate")),
            List.of(),
            Map.of());
    assertFalse(invalid.valid());
    assertEquals("HARD_VIOLATIONS", invalid.feasibility());
  }

  @Test
  void residualHistoryIdentityAndCanonicalCacheRemainStable() {
    Problem base = p(List.of(job()), List.of(vehicle()), List.of(), Map.of());
    Problem history =
        p(
            List.of(job()),
            List.of(vehicle()),
            List.of(new Completed("done", "v", N)),
            Map.of("j", new Previous("v", 0)));
    assertNotEquals(base.canonicalHash(), history.canonicalHash());
    String first = base.canonicalHash();
    for (int i = 0; i < 40; i++) {
      Problem next =
          new Problem(
              "tenant",
              "p" + i,
              1,
              ZoneId.of("America/Halifax"),
              0,
              N,
              matrix(),
              List.of(job()),
              List.of(vehicle()),
              List.of(),
              Map.of(),
              new Objective("o", 1, 1, 1),
              List.of("fixture"));
      assertEquals(64, next.canonicalHash().length());
    }
    assertEquals(first, base.canonicalHash());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Problem(
                "tenant",
                "p",
                0,
                ZoneId.of("America/Halifax"),
                0,
                N,
                matrix(),
                List.of(),
                List.of(vehicle()),
                List.of(),
                Map.of(),
                new Objective("o", 1, 1, 1),
                List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Problem(
                "tenant",
                "p",
                1,
                ZoneId.of("America/Halifax"),
                0,
                N.plusSeconds(1),
                matrix(),
                List.of(),
                List.of(vehicle()),
                List.of(),
                Map.of(),
                new Objective("o", 1, 1, 1),
                List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Problem(
                "tenant",
                "p",
                1,
                ZoneId.of("America/Halifax"),
                0,
                N,
                matrix(),
                List.of(),
                List.of(vehicle()),
                List.of(),
                Map.of(),
                new Objective("o", 1, 1, 1),
                List.of("x".repeat(2001))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            p(
                List.of(job()),
                List.of(vehicle()),
                List.of(),
                Map.of("j", new Previous("missing", 0))));
    Vehicle
        a =
            new Vehicle(
                "a", 2, Set.of(), N, N.plusSeconds(100), 0, N, Set.of("j"), 1, List.of("j"), false),
        b =
            new Vehicle(
                "b", 2, Set.of(), N, N.plusSeconds(100), 0, N, Set.of("j"), 1, List.of(), false);
    assertThrows(
        IllegalArgumentException.class,
        () -> p(List.of(job()), List.of(a, b), List.of(), Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Vehicle(
                "v",
                2,
                Set.of(),
                N,
                N.plusSeconds(100),
                0,
                N.plusSeconds(101),
                Set.of(),
                0,
                List.of(),
                false));
    assertThrows(
        IllegalArgumentException.class, () -> new Matrix("m", List.of(List.of(new Arc(0, 1)))));
    assertThrows(IllegalArgumentException.class, () -> Model.text(" "));
    assertThrows(IllegalArgumentException.class, () -> Model.text("a".repeat(201)));
  }

  @Test
  void problemIdsAreAddressableSingleSegments() {
    for (String id : List.of("a/b", "..", "a b", "a?b", "a%2Fb")) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new Problem(
                  "tenant",
                  id,
                  1,
                  ZoneId.of("America/Halifax"),
                  0,
                  N,
                  matrix(),
                  List.of(job()),
                  List.of(vehicle()),
                  List.of(),
                  Map.of(),
                  new Objective("o", 1, 1, 1),
                  List.of()));
    }
    Problem valid =
        new Problem(
            "tenant",
            "p-42_ok.v1",
            1,
            ZoneId.of("America/Halifax"),
            0,
            N,
            matrix(),
            List.of(job()),
            List.of(vehicle()),
            List.of(),
            Map.of(),
            new Objective("o", 1, 1, 1),
            List.of());
    assertEquals("p-42_ok.v1", valid.id());
  }
}
