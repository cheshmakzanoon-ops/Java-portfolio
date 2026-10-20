package io.github.cheshmakzanoon.portfolio.threadlens.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public final class ProfileComparator {
  public record Delta(
      String method,
      long baselineSamples,
      long candidateSamples,
      Double baselineProportion,
      Double candidateProportion,
      Double percentagePointDelta) {}

  public record PairedEstimate(
      int pairs,
      double meanPercent,
      double medianPercent,
      double lower95Percent,
      double upper95Percent) {}

  private ProfileComparator() {}

  public static List<Delta> compare(Profile baseline, Profile candidate) {
    TreeSet<String> keys = new TreeSet<>(baseline.methods().keySet());
    keys.addAll(candidate.methods().keySet());
    List<Delta> result = new ArrayList<>();
    for (String key : keys) {
      long b = baseline.methods().getOrDefault(key, new StackAggregator.MethodSamples(0, 0)).self();
      long c =
          candidate.methods().getOrDefault(key, new StackAggregator.MethodSamples(0, 0)).self();
      Double bp = baseline.sampleCount() == 0 ? null : (double) b / baseline.sampleCount();
      Double cp = candidate.sampleCount() == 0 ? null : (double) c / candidate.sampleCount();
      result.add(new Delta(key, b, c, bp, cp, bp == null || cp == null ? null : (cp - bp) * 100));
    }
    return result;
  }

  /** Paired ratios with Student-t uncertainty on log ratios; seven pairs use t(6)=2.447. */
  public static PairedEstimate paired(double[] baseline, double[] candidate) {
    if (baseline.length != candidate.length || baseline.length < 2)
      throw new IllegalArgumentException("at least two pairs");
    double[] log = new double[baseline.length];
    double sum = 0;
    for (int i = 0; i < log.length; i++) {
      if (!(baseline[i] > 0)
          || !(candidate[i] > 0)
          || !Double.isFinite(baseline[i])
          || !Double.isFinite(candidate[i]))
        throw new IllegalArgumentException("finite positive observations");
      log[i] = Math.log(candidate[i]) - Math.log(baseline[i]);
      sum += log[i];
    }
    double mean = sum / log.length, variance = 0;
    for (double x : log) variance += (x - mean) * (x - mean);
    double[] critical = {0, 12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228};
    double t = critical[Math.min(log.length - 1, 10)];
    double half = t * Math.sqrt(variance / (log.length - 1) / log.length);
    java.util.Arrays.sort(log);
    double median =
        log.length % 2 == 0
            ? (log[log.length / 2 - 1] + log[log.length / 2]) / 2
            : log[log.length / 2];
    PairedEstimate result =
        new PairedEstimate(
            log.length,
            Math.expm1(mean) * 100,
            Math.expm1(median) * 100,
            Math.expm1(mean - half) * 100,
            Math.expm1(mean + half) * 100);
    if (!Double.isFinite(result.meanPercent())
        || !Double.isFinite(result.medianPercent())
        || !Double.isFinite(result.lower95Percent())
        || !Double.isFinite(result.upper95Percent()))
      throw new IllegalArgumentException("paired estimate exceeds finite percentage range");
    return result;
  }
}
