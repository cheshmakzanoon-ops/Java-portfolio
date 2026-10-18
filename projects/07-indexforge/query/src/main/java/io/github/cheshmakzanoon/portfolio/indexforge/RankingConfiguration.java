package io.github.cheshmakzanoon.portfolio.indexforge;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Captures one immutable model snapshot at startup; files are never reread on a search/cache hit.
 */
public record RankingConfiguration(RankingStrategy strategy, LinearRankingModel model) {
  public RankingConfiguration {
    Objects.requireNonNull(strategy);
    if ((strategy == RankingStrategy.PAIRWISE) != (model != null))
      throw new IllegalArgumentException(
          "PAIRWISE requires exactly one model; other strategies require none");
  }

  public static RankingConfiguration classic() {
    return new RankingConfiguration(RankingStrategy.CLASSIC, null);
  }

  public static RankingConfiguration from(String strategy, String modelPath) throws IOException {
    RankingStrategy selected = RankingStrategy.valueOf(strategy);
    boolean supplied = modelPath != null && !modelPath.isBlank();
    if ((selected == RankingStrategy.PAIRWISE) != supplied)
      throw new IllegalArgumentException("ranking model configuration");
    return new RankingConfiguration(
        selected, supplied ? LinearRankingModel.read(Path.of(modelPath)) : null);
  }

  public static RankingConfiguration system() throws IOException {
    return from(
        System.getProperty("indexforge.ranking-strategy", "CLASSIC"),
        System.getProperty("indexforge.ranking-model"));
  }

  public String identity() {
    return strategy
        + "/"
        + SearchFeatureSchema.VERSION
        + "/"
        + RankingCodeIdentity.SHA256
        + (model == null ? "" : "/" + model.identity());
  }

  public String identity(RankingProfile profile) {
    return profile.name() + "/" + profile.parametersIdentity() + "/" + identity();
  }
}
