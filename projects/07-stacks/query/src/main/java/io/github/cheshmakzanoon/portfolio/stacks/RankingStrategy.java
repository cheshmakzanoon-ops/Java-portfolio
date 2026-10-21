package io.github.cheshmakzanoon.portfolio.stacks;

/** Finite preregistered wave2 families; no per-query or per-document strategy lookup. */
public enum RankingStrategy {
  CLASSIC,
  SYNTAX_GATED,
  DECLARATION_TIER,
  PAIRWISE,
  SOURCE_MEMBERS,
  SOURCE_RELATIONS,
  SOURCE_USAGE;

  public boolean learned() {
    return this == PAIRWISE || structural();
  }

  public boolean structural() {
    return this == SOURCE_MEMBERS || this == SOURCE_RELATIONS || this == SOURCE_USAGE;
  }

  public int dimensions() {
    return this == SOURCE_MEMBERS ? 12 : structural() ? 16 : 10;
  }

  public boolean suppressIdentifierWeights(String raw) {
    return this != CLASSIC && PlainTextQuery.multiword(raw);
  }

  public boolean declarationTier(String raw) {
    return (this == DECLARATION_TIER || this == PAIRWISE) && PlainTextQuery.identifier(raw);
  }
}
