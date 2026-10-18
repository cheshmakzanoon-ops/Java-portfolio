package io.github.cheshmakzanoon.portfolio.indexforge;

import java.util.*;

/** Equal total observation weight per eligible development query; never fits validation data. */
public record DevelopmentScaler(double[] means, double[] scales) {
  public DevelopmentScaler {
    means = SearchFeatureSchema.checked(means);
    if (scales == null || scales.length != SearchFeatureSchema.SIZE)
      throw new IllegalArgumentException("scaler dimension");
    scales = scales.clone();
    for (double scale : scales)
      if (!Double.isFinite(scale) || scale < 1e-6 || scale > SearchFeatureSchema.LOG_MAX + 1)
        throw new IllegalArgumentException("scaler finite bounds");
  }

  @Override
  public double[] means() {
    return means.clone();
  }

  @Override
  public double[] scales() {
    return scales.clone();
  }

  public double[] apply(double[] features) {
    double[] values = SearchFeatureSchema.checked(features);
    for (int i = 0; i < values.length; i++)
      values[i] = Math.clamp((values[i] - means[i]) / scales[i], -8.0, 8.0);
    return values;
  }

  public static DevelopmentScaler fit(List<PairwiseData.Query> queries) {
    PairwiseData.developmentOnly(queries);
    var eligible =
        queries.stream()
            .filter(q -> PlainTextQuery.multiword(q.text()) && !q.rows().isEmpty())
            .toList();
    if (eligible.isEmpty())
      throw new IllegalArgumentException("no eligible development observations");
    double[] means = new double[SearchFeatureSchema.SIZE],
        scales = new double[SearchFeatureSchema.SIZE];
    for (var query : eligible)
      for (var row : query.rows()) {
        double[] values = row.features();
        for (int i = 0; i < values.length; i++)
          means[i] += values[i] / query.rows().size() / eligible.size();
      }
    for (int i = 0; i < means.length; i++)
      means[i] =
          Math.clamp(
              means[i],
              0.0,
              i < 6
                  ? SearchFeatureSchema.LOG_MAX
                  : 1.0); // Roundoff cannot move a mean outside its input range.
    for (var query : eligible)
      for (var row : query.rows()) {
        double[] values = row.features();
        for (int i = 0; i < values.length; i++) {
          double delta = values[i] - means[i];
          scales[i] += delta * delta / query.rows().size() / eligible.size();
        }
      }
    for (int i = 0; i < scales.length; i++) scales[i] = Math.max(1e-6, Math.sqrt(scales[i]));
    return new DevelopmentScaler(means, scales);
  }
}
