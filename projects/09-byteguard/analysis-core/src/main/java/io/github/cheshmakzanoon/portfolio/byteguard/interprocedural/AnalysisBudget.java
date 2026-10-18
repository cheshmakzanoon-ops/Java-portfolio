package io.github.cheshmakzanoon.portfolio.byteguard.interprocedural;

public final class AnalysisBudget {
  public static final class Exhausted extends RuntimeException {
    public Exhausted(String why) {
      super(why);
    }
  }

  public final long maxWork, deadlineMillis;
  public final int dispatchTargets, provenanceDepth;
  private long work;
  private long frameCells;
  private long facts;
  private final long started;
  private final java.util.function.LongSupplier ticker;

  public AnalysisBudget(
      long maxWork, long deadlineMillis, int dispatchTargets, int provenanceDepth) {
    this(maxWork, deadlineMillis, dispatchTargets, provenanceDepth, System::nanoTime);
  }

  public AnalysisBudget(
      long maxWork,
      long deadlineMillis,
      int dispatchTargets,
      int provenanceDepth,
      java.util.function.LongSupplier ticker) {
    this.ticker = ticker;
    this.started = ticker.getAsLong();
    if (maxWork < 1 || deadlineMillis < 1 || dispatchTargets < 1 || provenanceDepth < 0)
      throw new IllegalArgumentException("invalid analysis budget");
    this.maxWork = maxWork;
    this.deadlineMillis = deadlineMillis;
    this.dispatchTargets = dispatchTargets;
    this.provenanceDepth = provenanceDepth;
  }

  public static AnalysisBudget defaults() {
    return new AnalysisBudget(50_000_000, 120_000, 32, 5);
  }

  public void spend() {
    if (++work > maxWork) throw new Exhausted("global work budget exceeded");
    if ((ticker.getAsLong() - started) / 1_000_000 > deadlineMillis)
      throw new Exhausted("analysis deadline exceeded");
  }

  public void reserveFact() {
    spend();
    if (++facts > 2_000_000) throw new Exhausted("retained class-fact budget exceeded (2000000)");
  }

  public void reserveFrame(int cells) {
    frameCells += Math.max(1, cells);
    if (frameCells > 8_000_000)
      throw new Exhausted("frame-cell allocation budget exceeded (8000000)");
  }

  public long work() {
    return work;
  }
}
