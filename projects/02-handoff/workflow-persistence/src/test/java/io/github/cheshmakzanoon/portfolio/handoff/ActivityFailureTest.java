package io.github.cheshmakzanoon.portfolio.handoff;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.handoff.Definition.Safety;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActivityFailureTest {
  private static final UUID INSTANCE = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Test
  void uncertainNonIdempotentDispatchNeverTurnsIntoAnAutomaticRetryOrOrdinaryFailure() {
    for (int attempt : new int[] {1, 4}) {
      var result =
          ActivityFailure.decide(
              INSTANCE, "charge", Safety.NON_IDEMPOTENT, attempt, true, false, "lease expired");
      assertEquals(ActivityFailure.Disposition.UNKNOWN_OUTCOME, result.disposition());
      assertEquals("LEASE_EXPIRED", result.attemptOutcome());
      assertEquals(0, result.retryDelayMillis());
    }
  }

  @Test
  void fixedSeedRetryScheduleRetainsExponentialDelayAndStableJitter() {
    long[] expected = {965, 1196, 1627};
    for (int attempt = 1; attempt <= 3; attempt++) {
      var decision =
          ActivityFailure.decide(
              INSTANCE, "reserve", Safety.IDEMPOTENT_WITH_KEY, attempt, true, true, "refused");
      assertEquals(expected[attempt - 1], decision.retryDelayMillis());
    }
  }

  @Test
  void undispatchedAndDefinitivelyRefusedWorkUseTheSameBoundedAttemptBudget() {
    for (Safety safety : Safety.values()) {
      for (boolean dispatched : new boolean[] {false, true}) {
        var retry =
            ActivityFailure.decide(INSTANCE, "reserve", safety, 1, dispatched, true, "refused");
        assertEquals(ActivityFailure.Disposition.RETRY, retry.disposition());
        assertEquals("REJECTED", retry.attemptOutcome());
        assertTrue(retry.retryDelayMillis() >= 200 && retry.retryDelayMillis() < 1200);
        assertEquals(
            retry,
            ActivityFailure.decide(INSTANCE, "reserve", safety, 1, dispatched, true, "refused"));
        assertEquals(
            ActivityFailure.Disposition.FAILED,
            ActivityFailure.decide(INSTANCE, "reserve", safety, 4, dispatched, true, "refused")
                .disposition());
      }
    }
    assertEquals(
        ActivityFailure.Disposition.RETRY,
        ActivityFailure.decide(
                INSTANCE, "charge", Safety.NON_IDEMPOTENT, 1, false, false, "not dispatched")
            .disposition());
    assertEquals(
        "UNCERTAIN",
        ActivityFailure.decide(INSTANCE, "read", Safety.PURE, 1, true, false, "transport failed")
            .attemptOutcome());
  }
}
