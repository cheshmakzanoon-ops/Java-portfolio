package io.github.cheshmakzanoon.portfolio.indexforge;

/** Finite preregistered wave2 families; no per-query or per-document strategy lookup. */
public enum RankingStrategy {
  CLASSIC,
  SYNTAX_GATED,
  DECLARATION_TIER,
  PAIRWISE;

  public boolean suppressIdentifierWeights(String raw) {
    return this != CLASSIC && PlainTextQuery.multiword(raw);
  }

  public boolean declarationTier(String raw) {
    return (this == DECLARATION_TIER || this == PAIRWISE) && PlainTextQuery.identifier(raw);
  }
}
