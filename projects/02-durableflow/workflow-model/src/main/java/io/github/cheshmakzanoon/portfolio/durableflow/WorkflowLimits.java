package io.github.cheshmakzanoon.portfolio.durableflow;

/** Shared serialization budgets for the persisted input and compatible worker-claim protocol. */
public final class WorkflowLimits {
  public static final int EXECUTION_INPUT_BYTES = 4096;
  public static final int RESULT_BYTES = 4096;
  public static final int TASK_INPUT_BYTES = 256 * 1024;
  // The raw provider body prefixes task JSON with one compiled ASCII activity and ':'.
  public static final int PROVIDER_REQUEST_BYTES =
      TASK_INPUT_BYTES
          + Definition.ACTIVITIES.stream().mapToInt(String::length).max().orElseThrow()
          + 1;
  public static final int PUBLIC_REQUEST_BYTES = 8192;
  // Claim.parameters contains serialized JSON. Echoing it can double its UTF-8 size
  // through quote/backslash escaping. 16 KiB covers the 4 KiB result, bounded claim
  // identifiers, failure reason, and JSON framing with ample fixed headroom.
  public static final int WORKER_REQUEST_BYTES = 2 * TASK_INPUT_BYTES + 16 * 1024;

  private WorkflowLimits() {}
}
