package io.github.cheshmakzanoon.portfolio.stacks;

/** Plain-text report of {@link QueryAst#explain}: what a query means, or where it went wrong. */
public final class QueryExplainer {
  private QueryExplainer() {}

  public static String render(String query, QueryAst.Explanation explanation) {
    StringBuilder out = new StringBuilder();
    if (explanation.valid()) {
      out.append("query:     ").append(query).append('\n');
      out.append("parsed as: ").append(explanation.canonical()).append('\n');
      for (String note : explanation.notes()) out.append("note:      ").append(note).append('\n');
      return out.toString();
    }
    out.append("query: ").append(query == null ? "" : query).append('\n');
    int caret = Math.max(0, Math.min(explanation.position(), query == null ? 0 : query.length()));
    out.append("       ")
        .append(" ".repeat(caret))
        .append("^ position ")
        .append(explanation.position())
        .append('\n');
    out.append("error: ").append(explanation.error()).append('\n');
    return out.toString();
  }
}
