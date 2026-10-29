package io.github.cheshmakzanoon.portfolio.stacks;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class QueryExplainTest {
  private static String canonical(String query) {
    QueryAst.Explanation e = QueryAst.explain(query);
    assertTrue(e.valid(), query + " -> " + e.error());
    assertNull(e.error());
    assertEquals(-1, e.position());
    return e.canonical();
  }

  private static QuerySyntaxException refused(String query) {
    QuerySyntaxException failure =
        assertThrows(QuerySyntaxException.class, () -> QueryAst.parse(query), query);
    QueryAst.Explanation e = QueryAst.explain(query);
    assertFalse(e.valid());
    assertNull(e.canonical());
    assertEquals(List.of(), e.notes());
    assertEquals(failure.position(), e.position());
    return failure;
  }

  @Test
  void canonicalFormMakesPrecedenceAndImplicitOperatorsExplicit() {
    assertEquals("foo", canonical("foo"));
    assertEquals("a OR b", canonical("a b"));
    assertEquals("a OR (b AND c)", canonical("a b AND c"));
    assertEquals("(a AND b) OR c", canonical("a AND b c"));
    assertEquals("a OR b OR c", canonical("a OR b OR c"));
    assertEquals("a AND b AND c", canonical("a AND b AND c"));
    assertEquals("a OR (b OR c)", canonical("a OR (b OR c)"), "an explicit right group is kept");
    assertEquals("NOT a", canonical("NOT a"));
    assertEquals("a AND NOT b", canonical("a NOT b"), "NOT after an operand means AND NOT");
    assertEquals("a OR NOT b", canonical("a OR NOT b"));
    assertEquals("NOT NOT a", canonical("NOT NOT a"));
    assertEquals("NOT a AND b", canonical("NOT a AND b"));
    assertEquals("(a OR b) AND c", canonical("(a b) AND c"));
    assertEquals("content:\"hello world\"", canonical("content:\"hello world\""));
    assertEquals("\"hello world\" OR x", canonical("\"hello world\" x"));
    assertEquals(
        "path:src/Main.java AND symbol:Foo", canonical("path:src/Main.java AND symbol:Foo"));
    assertEquals("NOT (a OR b)", canonical("NOT (a b)"));
  }

  @Test
  void theCanonicalQueryAlwaysParsesBackToTheSameTree() {
    Random random = new Random(2026);
    String[] words = {"alpha", "beta", "gamma", "Delta", "x1", "foo.bar", "AND_NOT", "andy"};
    String[] fields = {
      "content", "symbol", "path", "repository", "language", "kind", "coordinates", "version"
    };
    for (int round = 0; round < 3000; round++) {
      String query = generate(random, words, fields, 0);
      QueryAst original = QueryAst.parse(query);
      String canonical = QueryAst.canonical(original);
      assertEquals(original, QueryAst.parse(canonical), query + " => " + canonical);
      assertEquals(
          canonical,
          QueryAst.canonical(QueryAst.parse(canonical)),
          "canonical form is a fixed point");
    }
  }

  private static String generate(Random random, String[] words, String[] fields, int depth) {
    StringBuilder out = new StringBuilder();
    int operands = 1 + random.nextInt(3);
    for (int i = 0; i < operands; i++) {
      if (i > 0) out.append(new String[] {" ", " AND ", " OR "}[random.nextInt(3)]);
      if (random.nextInt(5) == 0) out.append("NOT ");
      int kind = random.nextInt(6);
      if (kind == 0 && depth < 2)
        out.append('(').append(generate(random, words, fields, depth + 1)).append(')');
      else if (kind == 1)
        out.append('"')
            .append(words[random.nextInt(words.length)])
            .append(' ')
            .append(words[random.nextInt(words.length)])
            .append('"');
      else if (kind == 2)
        out.append(fields[random.nextInt(fields.length)])
            .append(':')
            .append(words[random.nextInt(words.length)]);
      else out.append(words[random.nextInt(words.length)]);
    }
    return out.toString();
  }

  @Test
  void everyKindOfRefusalReportsItsCodeAndPosition() {
    Object[][] cases = {
      {"", "query length", 0},
      {"   ", "query length", 0},
      {"x".repeat(513), "query length", 0},
      {"\"abc", "phrase", 0},
      {"a \"\" b", "phrase", 2},
      {"foo*", "unsupported operator", 0},
      {"a b~", "unsupported operator", 2},
      {"x[1]", "unsupported operator", 0},
      {String.join(" ", Collections.nCopies(65, "a")), "query clause quota", 129},
      {"a AND", "missing operand", 5},
      {"AND a", "missing operand", 0},
      {"a OR", "missing operand", 4},
      {"NOT", "missing operand", 3},
      {"(", "missing operand", 1},
      {"a )", "unexpected token", 2},
      {"(a", "unclosed group", 2},
      {"((a) b", "unclosed group", 6},
      {"foo:bar", "unsupported field", 0},
      {"a AND foo:bar", "unsupported field", 6},
      {"path:", "missing field value", 5},
      {"path:AND", "field value", 5},
      {"path:(", "field value", 5},
      {"\"a b\":x", "field", 0},
      {"(".repeat(10) + "a" + ")".repeat(10), "query depth", 9},
    };
    for (Object[] c : cases) {
      QuerySyntaxException failure = refused((String) c[0]);
      assertEquals(c[1], failure.getMessage(), (String) c[0]);
      assertEquals(c[2], failure.position(), (String) c[0]);
    }
    QueryAst.Explanation nothing = QueryAst.explain(null);
    assertFalse(nothing.valid());
    assertEquals(0, nothing.position());
    assertThrows(
        IllegalArgumentException.class,
        () -> QueryAst.parse(null),
        "callers that catch IllegalArgumentException keep working");
  }

  @Test
  void everyRefusalHasAHumanSentenceNotJustACode() {
    for (String query :
        new String[] {
          "",
          "\"x",
          "a*",
          "a AND",
          "a )",
          "(a",
          "foo:b",
          "path:",
          "path:AND",
          "\"a b\":x",
          "((((((((((a))))))))))"
        }) {
      QueryAst.Explanation e = QueryAst.explain(query);
      assertFalse(e.valid(), query);
      assertTrue(e.error().length() > 15 && e.error().contains(" "), e.error());
    }
    assertEquals(
        "unknown field; use content, symbol, path, repository, language, kind, coordinates or version",
        QueryAst.explain("foo:bar").error());
    assertTrue(
        QueryAst.explain(String.join(" ", Collections.nCopies(65, "a"))).error().contains("64"));
  }

  @Test
  void notesStateOnlyBehaviourTheQueryActuallyHas() {
    assertEquals(List.of(), QueryAst.explain("a AND b").notes());
    assertEquals(List.of(), QueryAst.explain("a OR b").notes());
    assertEquals(List.of(), QueryAst.explain("content:\"a b\"").notes());

    List<String> implicit = QueryAst.explain("a b").notes();
    assertEquals(1, implicit.size());
    assertTrue(
        implicit.getFirst().startsWith("Terms written next to each other are combined with OR"));
    assertTrue(
        QueryAst.explain("(a b) AND c").notes().getFirst().contains("OR"),
        "implicit OR inside a group");

    List<String> lowercase = QueryAst.explain("a and b").notes();
    assertEquals(2, lowercase.size(), lowercase.toString());
    assertTrue(
        lowercase
            .get(1)
            .startsWith("Operators are uppercase: and is searched as an ordinary word"));
    assertTrue(
        QueryAst.explain("x or y not z").notes().stream().anyMatch(n -> n.contains("not, or")),
        "sorted, deduplicated");
    assertEquals(
        List.of(),
        QueryAst.explain("\"fish and chips\"").notes(),
        "inside a phrase it is just text");
    assertEquals(
        List.of(),
        QueryAst.explain("andy ORacle").notes().stream()
            .filter(n -> n.startsWith("Operators"))
            .toList());

    assertTrue(
        QueryAst.explain("path:src/A.java")
            .notes()
            .getFirst()
            .startsWith("path match the whole stored value exactly (case-sensitive)"));
    assertTrue(
        QueryAst.explain("language:java AND kind:class")
            .notes()
            .getFirst()
            .startsWith("kind, language match the whole stored value"));
    assertTrue(
        QueryAst.explain("symbol:Foo")
            .notes()
            .getFirst()
            .startsWith("symbol matches one identifier exactly, ignoring case"));
    assertEquals(List.of(), QueryAst.explain("content:foo").notes());

    assertTrue(
        QueryAst.explain("a OR NOT b")
            .notes()
            .getFirst()
            .startsWith("NOT inside an OR matches every document"));
    assertTrue(
        QueryAst.explain("NOT b OR a")
            .notes()
            .getFirst()
            .startsWith("NOT inside an OR matches every document"));
    assertEquals(List.of(), QueryAst.explain("a AND NOT b").notes());
    assertEquals(List.of(), QueryAst.explain("NOT b").notes());
  }

  @Test
  void parsingBehaviourIsUnchangedForAcceptedQueries() {
    assertEquals(
        new QueryAst.BooleanNode(
            true,
            new QueryAst.Term("all", "a", false),
            new QueryAst.BooleanNode(
                false, new QueryAst.Term("all", "b", false), new QueryAst.Term("all", "c", false))),
        QueryAst.parse("a b AND c"));
    assertEquals(new QueryAst.Term("path", "x y", true), QueryAst.parse("path:\"x y\""));
    assertEquals(
        new QueryAst.Negation(new QueryAst.Term("all", "a", false)), QueryAst.parse("NOT a"));
  }

  @Test
  void theTextReportShowsTheCaretUnderTheOffendingPosition() {
    String ok = QueryExplainer.render("a b AND c", QueryAst.explain("a b AND c"));
    assertEquals(
        "query:     a b AND c\nparsed as: a OR (b AND c)\nnote:      Terms written next to each other are combined with OR; write AND to require all of them.\n",
        ok);
    String bad = QueryExplainer.render("a )", QueryAst.explain("a )"));
    assertEquals(
        "query: a )\n         ^ position 2\nerror: unexpected token: terms and operators must alternate, and every group must be closed\n",
        bad);
    String end = QueryExplainer.render("(a", QueryAst.explain("(a"));
    assertTrue(end.contains("query: (a\n         ^ position 2\n"), end);
    String blank = QueryExplainer.render("", QueryAst.explain(""));
    assertTrue(blank.startsWith("query: \n       ^ position 0\n"), blank);
    assertTrue(QueryExplainer.render(null, QueryAst.explain(null)).startsWith("query: \n"));
  }
}
