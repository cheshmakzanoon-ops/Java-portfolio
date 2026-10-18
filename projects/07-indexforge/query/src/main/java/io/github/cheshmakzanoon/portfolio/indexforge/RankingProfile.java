package io.github.cheshmakzanoon.portfolio.indexforge;

/** Bounded, preregistered conventional field-weight and term-coverage experiment. */
public enum RankingProfile {
  CURRENT(3, 8, 2, 32, 1.5f, .75f, 0),
  CONTEXTUAL(2, 6, 1, 32, 2, 2, 4),
  SEMANTIC(1, 4, 1, 24, 3, 4, 4),
  NAVIGATIONAL(2, 12, 4, 64, 1.5f, 1, 2),
  CONTENT_FOCUSED(1, 2, .5f, 16, 1, .5f, 4),
  COORDINATED(3, 8, 2, 32, 1.5f, .75f, 8);
  public final float identifiers, symbols, paths, declarations, prose, concepts, coverage;

  RankingProfile(
      float identifiers,
      float symbols,
      float paths,
      float declarations,
      float prose,
      float concepts,
      float coverage) {
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
}
