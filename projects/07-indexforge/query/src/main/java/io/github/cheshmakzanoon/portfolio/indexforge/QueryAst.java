package io.github.cheshmakzanoon.portfolio.indexforge;

import java.util.*;

public sealed interface QueryAst permits QueryAst.Term, QueryAst.BooleanNode, QueryAst.Negation {
  record Term(String field, String value, boolean phrase) implements QueryAst {}

  record BooleanNode(boolean or, QueryAst left, QueryAst right) implements QueryAst {}

  record Negation(QueryAst child) implements QueryAst {}

  static QueryAst parse(String text) {
    return new Parser(text).parse();
  }

  final class Parser {
    private record Token(String text, boolean phrase) {}

    private final List<Token> tokens = new ArrayList<>();
    private int at;

    Parser(String input) {
      if (input == null || input.isBlank() || input.length() > 512)
        throw new IllegalArgumentException("query length");
      int i = 0;
      while (i < input.length()) {
        char c = input.charAt(i);
        if (Character.isWhitespace(c)) {
          i++;
          continue;
        }
        if (c == '(' || c == ')' || c == ':') {
          tokens.add(new Token(String.valueOf(c), false));
          i++;
        } else if (c == '"') {
          int end = input.indexOf('"', i + 1);
          if (end < 0 || end == i + 1) throw new IllegalArgumentException("phrase");
          tokens.add(new Token(input.substring(i + 1, end), true));
          i = end + 1;
        } else {
          int begin = i;
          while (i < input.length()
              && !Character.isWhitespace(input.charAt(i))
              && "():\"".indexOf(input.charAt(i)) < 0) i++;
          String term = input.substring(begin, i);
          if (term.matches(".*[\\*?~{}\\[\\]\\\\].*"))
            throw new IllegalArgumentException("unsupported operator");
          tokens.add(new Token(term, false));
        }
        if (tokens.size() > 64) throw new IllegalArgumentException("query clause quota");
      }
    }

    QueryAst parse() {
      QueryAst n = or(0);
      if (at != tokens.size()) throw new IllegalArgumentException("unexpected token");
      return n;
    }

    QueryAst or(int depth) {
      QueryAst n = and(depth);
      while (at < tokens.size() && !peek(")")) {
        accept("OR");
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
      if (depth > 8) throw new IllegalArgumentException("query depth");
      if (accept("NOT")) return new Negation(unary(depth + 1));
      if (accept("(")) {
        QueryAst n = or(depth + 1);
        require(")");
        return n;
      }
      if (at >= tokens.size() || peek(")") || peek("AND") || peek("OR") || peek(":"))
        throw new IllegalArgumentException("missing operand");
      Token t = tokens.get(at++);
      String field = "all";
      if (accept(":")) {
        if (t.phrase) throw new IllegalArgumentException("field");
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
            .contains(field)) throw new IllegalArgumentException("unsupported field");
        if (at >= tokens.size()) throw new IllegalArgumentException("missing field value");
        t = tokens.get(at++);
        if (Set.of("(", ")", ":", "AND", "OR", "NOT").contains(t.text))
          throw new IllegalArgumentException("field value");
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
      if (!accept(t)) throw new IllegalArgumentException("unclosed group");
    }
  }
}
