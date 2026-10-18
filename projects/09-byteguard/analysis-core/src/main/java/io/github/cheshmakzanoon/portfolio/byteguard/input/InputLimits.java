package io.github.cheshmakzanoon.portfolio.byteguard.input;

public record InputLimits(
    int entries, long expandedBytes, int classBytes, int expansionRatio, long deadlineMillis) {
  public static InputLimits defaults() {
    return new InputLimits(25_000, 200L * 1024 * 1024, 8 * 1024 * 1024, 100, 120_000);
  }

  public InputLimits {
    if (entries < 1
        || expandedBytes < 1
        || classBytes < 1
        || expansionRatio < 1
        || deadlineMillis < 1) throw new IllegalArgumentException("positive input limits required");
  }
}
