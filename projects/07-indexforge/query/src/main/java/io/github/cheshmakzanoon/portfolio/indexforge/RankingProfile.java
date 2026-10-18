package io.github.cheshmakzanoon.portfolio.indexforge;

/** Bounded, preregistered conventional field-weight and term-coverage experiment. */
public enum RankingProfile {
  CURRENT(3, 8, 2, 32, 1.5f, .75f, 0),
  CONTEXTUAL(2, 6, 1, 32, 2, 2, 4),
  SEMANTIC(1, 4, 1, 24, 3, 4, 4),
  NAVIGATIONAL(2, 12, 4, 64, 1.5f, 1, 2),
  CONTENT_FOCUSED(1, 2, .5f, 16, 1, .5f, 4),
  COORDINATED(3, 8, 2, 32, 1.5f, .75f, 8),
  BM25_DEFAULT(1.2f, .75f),
  BM25_NO_LENGTH(1.2f, 0),
  BM25_LOW_LENGTH(1.2f, .25f),
  BM25_FULL_LENGTH(1.2f, 1),
  BM25_LOW_SATURATION(.6f, .75f),
  BM25_HIGH_SATURATION(2, .75f),
  BM25_BOOLEAN(0, .75f),
  CODE_NONE(0),
  CODE_LOW(1),
  CODE_MEDIUM(4),
  CODE_HIGH(12);
  public final float identifiers, symbols, paths, declarations, prose, concepts, coverage;
  public final float k1, b;
  public final float codeFeatures;

  RankingProfile(float codeFeatures) {
    this(2, 6, 1, 32, 2, 2, 0, 2, .75f, codeFeatures);
  }

  RankingProfile(float k1, float b) {
    this(2, 6, 1, 32, 2, 2, 0, k1, b);
  }

  RankingProfile(
      float identifiers,
      float symbols,
      float paths,
      float declarations,
      float prose,
      float concepts,
      float coverage) {
    this(identifiers, symbols, paths, declarations, prose, concepts, coverage, 1.2f, .75f);
  }

  RankingProfile(
      float identifiers,
      float symbols,
      float paths,
      float declarations,
      float prose,
      float concepts,
      float coverage,
      float k1,
      float b) {
    this(identifiers, symbols, paths, declarations, prose, concepts, coverage, k1, b, 0);
  }

  RankingProfile(
      float identifiers,
      float symbols,
      float paths,
      float declarations,
      float prose,
      float concepts,
      float coverage,
      float k1,
      float b,
      float codeFeatures) {
    this.codeFeatures = codeFeatures;
    this.k1 = k1;
    this.b = b;
    this.identifiers = identifiers;
    this.symbols = symbols;
    this.paths = paths;
    this.declarations = declarations;
    this.prose = prose;
    this.concepts = concepts;
    this.coverage = coverage;
  }

  public static RankingProfile current() {
    return valueOf(System.getProperty("indexforge.ranking-profile", "CURRENT"));
  }

  /** Actual finite numeric configuration, independent of the enum label alone. */
  public String parametersIdentity() {
    StringBuilder values = new StringBuilder("ranking-profile-v1/").append(name());
    for (float value :
        new float[] {
          identifiers, symbols, paths, declarations, prose, concepts, coverage, k1, b, codeFeatures
        }) values.append('/').append(Float.toHexString(value));
    return Digests.sha256(values.toString());
  }
}
