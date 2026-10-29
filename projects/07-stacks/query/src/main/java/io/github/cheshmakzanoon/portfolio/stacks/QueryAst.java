package io.github.cheshmakzanoon.portfolio.stacks;

import java.util.*;

public sealed interface QueryAst permits QueryAst.Term, QueryAst.BooleanNode, QueryAst.Negation {
  record Term(String field, String value, boolean phrase) implements QueryAst {}

  record BooleanNode(boolean or, QueryAst left, QueryAst right) implements QueryAst {}

  record Negation(QueryAst child) implements QueryAst {}

  static QueryAst parse(String text) {
    return new Parser(text).parse();
  }

  /**
   * The same query written with every grouping and operator explicit; it parses to an equal AST.
   */
  static String canonical(QueryAst ast) {
    return ast instanceof BooleanNode || ast instanceof Negation
        ? render(ast, true)
        : render(ast, false);
  }

  private static String render(QueryAst ast, boolean top) {
    if (ast instanceof Term t) {
      String value = t.phrase() ? "\"" + t.value() + "\"" : t.value();
      return t.field().equals("all") ? value : t.field() + ":" + value;
    }
    // NOT binds to the single operand after it, so it needs no group of its own; its operand
    // does when it is a boolean node. Adding no parentheses here keeps the nesting depth, and so
    // the 8-level limit, no worse than the original query's.
    if (ast instanceof Negation n) return "NOT " + render(n.child(), false);
    BooleanNode node = (BooleanNode) ast;
    // Chains of the same operator down the left side are one group: a OR b OR c.
    Deque<QueryAst> operands = new ArrayDeque<>();
    QueryAst cursor = node;
    while (cursor instanceof BooleanNode b && b.or() == node.or()) {
      operands.addFirst(b.right());
      cursor = b.left();
    }
    operands.addFirst(cursor);
    StringJoiner joined = new StringJoiner(node.or() ? " OR " : " AND ");
    for (QueryAst operand : operands) joined.add(render(operand, false));
    return top ? joined.toString() : "(" + joined + ")";
  }

  /** What a query means, or exactly where and why it was refused. Never consults an index. */
  record Explanation(
      boolean valid, String canonical, List<String> notes, String error, int position) {
    public Explanation {
      notes = List.copyOf(notes);
    }
  }

  static Explanation explain(String text) {
    try {
      Parser parser = new Parser(text);
      QueryAst ast = parser.parse();
      return new Explanation(true, canonical(ast), parser.notes(ast), null, -1);
    } catch (QuerySyntaxException invalid) {
      return new Explanation(
          false, null, List.of(), describe(invalid.getMessage()), invalid.position());
    }
  }

  private static String describe(String code) {
    return switch (code) {
      case "query length" -> "the query is empty or longer than 512 characters";
      case "phrase" -> "a quoted phrase is empty or has no closing quote";
      case "unsupported operator" ->
          "wildcard, fuzzy, range and escape characters (* ? ~ { } [ ] \\) are not supported";
      case "query clause quota" -> "more than 64 tokens";
      case "unexpected token" ->
          "unexpected token: terms and operators must alternate, and every group must be closed";
      case "missing operand" ->
          "an operand is missing: expected a term, a quoted phrase, NOT x or a parenthesized group";
      case "field" -> "a quoted phrase cannot name a field";
      case "unsupported field" ->
          "unknown field; use content, symbol, path, repository, language, kind, coordinates or version";
      case "missing field value" -> "a field needs a value after the colon";
      case "field value" -> "an operator or parenthesis cannot be a field value";
      case "unclosed group" -> "a parenthesis is not closed";
      case "query depth" -> "groups and NOT nest more than 8 levels deep";
      default -> code;
    };
  }

  final class Parser {
    private record Token(String text, boolean phrase, int start) {}

    private final List<Token> tokens = new ArrayList<>();
    private final int length;
    private int at;
    private boolean implicitOr;

    Parser(String input) {
      if (input == null || input.isBlank() || input.length() > 512)
        throw new QuerySyntaxException("query length", 0);
      length = input.length();
      int i = 0;
      while (i < input.length()) {
        char c = input.charAt(i);
        if (Character.isWhitespace(c)) {
          i++;
          continue;
        }
        if (c == '(' || c == ')' || c == ':') {
          tokens.add(new Token(String.valueOf(c), false, i));
          i++;
        } else if (c == '"') {
          int end = input.indexOf('"', i + 1);
          if (end < 0 || end == i + 1) throw new QuerySyntaxException("phrase", i);
          tokens.add(new Token(input.substring(i + 1, end), true, i));
          i = end + 1;
        } else {
          int begin = i;
          while (i < input.length()
              && !Character.isWhitespace(input.charAt(i))
              && "():\"".indexOf(input.charAt(i)) < 0) i++;
          String term = input.substring(begin, i);
          if (term.matches(".*[\\*?~{}\\[\\]\\\\].*"))
            throw new QuerySyntaxException("unsupported operator", begin);
          tokens.add(new Token(term, false, begin));
        }
        if (tokens.size() > 64) throw new QuerySyntaxException("query clause quota", i);
      }
    }

    QueryAst parse() {
      QueryAst n = or(0);
      if (at != tokens.size()) throw new QuerySyntaxException("unexpected token", position());
      return n;
    }

    QueryAst or(int depth) {
      QueryAst n = and(depth);
      while (at < tokens.size() && !peek(")")) {
        if (!accept("OR")) implicitOr = true;
        n = new BooleanNode(true, n, and(depth));
      }
      return n;
    }

    QueryAst and(int depth) {
      QueryAst n = unary(depth);
      while (peek("AND") || peek("NOT")) {
        accept("AND");
        n = new BooleanNode(false, n, unary(depth));
      }
      return n;
    }

    QueryAst unary(int depth) {
      if (depth > 8) throw new QuerySyntaxException("query depth", position());
      if (accept("NOT")) return new Negation(unary(depth + 1));
      if (accept("(")) {
        QueryAst n = or(depth + 1);
        require(")");
        return n;
      }
      if (at >= tokens.size() || peek(")") || peek("AND") || peek("OR") || peek(":"))
        throw new QuerySyntaxException("missing operand", position());
      Token t = tokens.get(at++);
      String field = "all";
      if (accept(":")) {
        if (t.phrase) throw new QuerySyntaxException("field", t.start);
        field = t.text;
        if (!Set.of(
                "content",
                "symbol",
                "path",
                "repository",
                "language",
                "kind",
                "coordinates",
                "version")
            .contains(field)) throw new QuerySyntaxException("unsupported field", t.start);
        if (at >= tokens.size()) throw new QuerySyntaxException("missing field value", position());
        t = tokens.get(at++);
        if (!t.phrase && Set.of("(", ")", ":", "AND", "OR", "NOT").contains(t.text))
          throw new QuerySyntaxException("field value", t.start);
      }
      return new Term(field, t.text, t.phrase);
    }

    boolean peek(String t) {
      return at < tokens.size() && !tokens.get(at).phrase && tokens.get(at).text.equals(t);
    }

    boolean accept(String t) {
      if (peek(t)) {
        at++;
        return true;
      }
      return false;
    }

    void require(String t) {
      if (!accept(t)) throw new QuerySyntaxException("unclosed group", position());
    }

    /** Where parsing is now: the next token's offset, or the query's length at the end. */
    private int position() {
      return at < tokens.size() ? tokens.get(at).start : length;
    }

    /**
     * Behaviour a reader of the query is likely to get wrong; each note is a fact about the parser.
     */
    List<String> notes(QueryAst ast) {
      List<String> notes = new ArrayList<>();
      if (implicitOr)
        notes.add(
            "Terms written next to each other are combined with OR; write AND to require all of them.");
      Set<String> lowercase = new TreeSet<>();
      for (Token token : tokens)
        if (!token.phrase
            && Set.of("and", "or", "not").contains(token.text.toLowerCase(Locale.ROOT))
            && !token.text.equals(token.text.toUpperCase(Locale.ROOT))) lowercase.add(token.text);
      if (!lowercase.isEmpty())
        notes.add(
            "Operators are uppercase: "
                + String.join(", ", lowercase)
                + " is searched as an ordinary word, not as an operator.");
      Set<String> exact = new TreeSet<>();
      boolean symbol = false, notUnderOr = false;
      Deque<QueryAst> pending = new ArrayDeque<>(List.of(ast));
      while (!pending.isEmpty()) {
        QueryAst node = pending.removeFirst();
        if (node instanceof Term t) {
          if (Set.of("path", "repository", "language", "kind", "coordinates", "version")
              .contains(t.field())) exact.add(t.field());
          if (t.field().equals("symbol")) symbol = true;
        } else if (node instanceof Negation n) pending.add(n.child());
        else if (node instanceof BooleanNode b) {
          if (b.or() && (b.left() instanceof Negation || b.right() instanceof Negation))
            notUnderOr = true;
          pending.add(b.left());
          pending.add(b.right());
        }
      }
      if (!exact.isEmpty())
        notes.add(
            String.join(", ", exact)
                + " match the whole stored value exactly (case-sensitive); they are not searched word by word or as substrings.");
      if (symbol)
        notes.add(
            "symbol matches one identifier exactly, ignoring case; it does not split camelCase.");
      if (notUnderOr)
        notes.add(
            "NOT inside an OR matches every document that lacks the negated clause, so the OR matches nearly everything.");
      return notes;
    }
  }
}
