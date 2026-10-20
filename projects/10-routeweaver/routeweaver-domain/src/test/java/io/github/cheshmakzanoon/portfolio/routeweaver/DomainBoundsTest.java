package io.github.cheshmakzanoon.portfolio.routeweaver;

import static io.github.cheshmakzanoon.portfolio.routeweaver.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DomainBoundsTest {
  @Test
  void previousAssignmentsCannotClaimTheSamePhysicalRoutePosition() {
    Job a = ModelTest.job();
    Job b = new Job("other", a.location(), 1, 1, a.windowStart(), a.windowEnd(), Set.of(), 1);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelTest.p(
                List.of(a, b),
                List.of(ModelTest.vehicle()),
                List.of(),
                Map.of(a.id(), new Previous("v", 0), b.id(), new Previous("v", 0))));
    assertDoesNotThrow(
        () ->
            ModelTest.p(
                List.of(a, b),
                List.of(ModelTest.vehicle()),
                List.of(),
                Map.of(a.id(), new Previous("v", 0), b.id(), new Previous("v", 1))));
  }

  @Test
  void candidateCardinalityCannotBypassTheBoundedRouteEvaluator() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Route("v", Collections.nCopies(MAX_JOBS + 1, "j")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Candidate(
                "hash",
                Collections.nCopies(MAX_VEHICLES + 1, new Route("v", List.of())),
                Map.of()));
    Map<String, String> tooMany = new HashMap<>();
    for (int i = 0; i <= MAX_JOBS; i++) tooMany.put("j" + i, "UNASSIGNED");
    assertThrows(IllegalArgumentException.class, () -> new Candidate("hash", List.of(), tooMany));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Candidate("hash", List.of(), Map.of("j", "x".repeat(2001))));
    assertDoesNotThrow(() -> new Route("v", Collections.nCopies(MAX_JOBS, "j")));
  }

  @Test
  void acceptedInstantsLeaveHeadroomForWorstCaseDirectedRoutes() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Completed("j", "v", Instant.MAX.minusNanos(999999999)));
    assertDoesNotThrow(() -> new Completed("j", "v", MAX_INPUT_INSTANT));
    assertDoesNotThrow(() -> new Completed("j", "v", Instant.MIN));
    Instant routeEnd = MAX_INPUT_INSTANT;
    for (int i = 0; i < MAX_JOBS; i++)
      routeEnd = routeEnd.plusSeconds(10_000_000).plusSeconds(604800);
    assertDoesNotThrow(routeEnd.plusSeconds(10_000_000)::toString);
  }
}
