package io.github.cheshmakzanoon.portfolio.indexforge;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Captures one immutable model snapshot at startup; files are never reread on a search/cache hit.
 */
public record RankingConfiguration(RankingStrategy strategy, LinearRankingModel model,SourceApiCatalog catalog) {
  public RankingConfiguration(RankingStrategy strategy,LinearRankingModel model){this(strategy,model,null);}
  public RankingConfiguration {
    Objects.requireNonNull(strategy);
    if (strategy.learned() != (model != null))
      throw new IllegalArgumentException(
          "learned ranking requires exactly one model; other strategies require none");
    if(model!=null&&model.dimensions()!=strategy.dimensions())throw new IllegalArgumentException("strategy/model schema mismatch");
    if(strategy.structural()!=(catalog!=null))throw new IllegalArgumentException("source strategy requires exactly one pinned API catalog");
  }

  public static RankingConfiguration classic() {
    return new RankingConfiguration(RankingStrategy.CLASSIC, null);
  }

  public static RankingConfiguration from(String strategy, String modelPath) throws IOException {
    return from(strategy,modelPath,System.getProperty("indexforge.api-catalog"));
  }

  public static RankingConfiguration from(String strategy,String modelPath,String catalogPath)throws IOException {
    RankingStrategy selected = RankingStrategy.valueOf(strategy);
    boolean supplied = modelPath != null && !modelPath.isBlank();
    if (selected.learned() != supplied)
      throw new IllegalArgumentException("ranking model configuration");
    boolean hasCatalog=catalogPath!=null&&!catalogPath.isBlank();
    if(selected.structural()!=hasCatalog)throw new IllegalArgumentException("API catalog configuration");
    return new RankingConfiguration(selected,supplied?LinearRankingModel.read(Path.of(modelPath)):null,
        hasCatalog?SourceApiCatalog.read(Path.of(catalogPath)):null);
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
        + (model == null ? "" : "/" + model.identity())
        + (catalog==null?"":"/"+SearchFeatureSchema.version(strategy.dimensions())+"/"+catalog.identity()
            +"/"+SourceFacts.SCHEMA+"/"+SourceSymbolGraph.SCHEMA+"/"+System.getProperty("java.runtime.version"));
  }

  public String identity(RankingProfile profile) {
    return profile.name() + "/" + profile.parametersIdentity() + "/" + identity();
  }
}
