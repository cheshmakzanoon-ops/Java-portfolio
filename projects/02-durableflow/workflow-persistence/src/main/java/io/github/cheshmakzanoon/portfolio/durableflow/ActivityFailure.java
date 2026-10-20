package io.github.cheshmakzanoon.portfolio.durableflow;

import io.github.cheshmakzanoon.portfolio.durableflow.Definition.Safety;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure retry/uncertainty policy; database updates apply this decision in the instance transaction.
 */
record ActivityFailure(Disposition disposition, String attemptOutcome, long retryDelayMillis) {
  enum Disposition {
    RETRY,
    FAILED,
    UNKNOWN_OUTCOME
  }

  static ActivityFailure decide(
      UUID instance,
      String task,
      Safety safety,
      int attempt,
      boolean dispatched,
      boolean definitive,
      String reason) {
    String outcome =
        definitive ? "REJECTED" : reason.equals("lease expired") ? "LEASE_EXPIRED" : "UNCERTAIN";
    if (safety == Safety.NON_IDEMPOTENT && dispatched && !definitive)
      return new ActivityFailure(Disposition.UNKNOWN_OUTCOME, outcome, 0);
    if (attempt >= 4) return new ActivityFailure(Disposition.FAILED, outcome, 0);
    long jitter = Math.floorMod(Objects.hash(instance, task, attempt, 42), 1000);
    return new ActivityFailure(Disposition.RETRY, outcome, (1L << attempt) * 100 + jitter);
  }
}
