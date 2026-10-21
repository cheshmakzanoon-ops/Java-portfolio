package io.github.cheshmakzanoon.portfolio.indexforge;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class QueryAstQuotedOperandTest {
  private static final List<String> FIELDS =
      List.of(
          "content", "symbol", "path", "repository", "language", "kind", "coordinates", "version");
  private static final List<String> RESERVED = List.of("AND", "OR", "NOT", "(", ")", ":");

  @Test
  void quotedReservedWordsAndPunctuationAreFieldValuesNotOperators() {
    for (String field : FIELDS) {
      for (String literal : RESERVED) {
        String input = field + ":\"" + literal + "\"";
        assertEquals(
            new QueryAst.Term(field, literal, true),
            QueryAst.parse(input),
            () -> "literal operand should be valid: " + input);
      }
    }
  }

  @Test
  void quotedOperandsPreserveBooleanPrecedenceAndExplicitFieldIdentity() {
    QueryAst left = new QueryAst.Term("content", "AND", true);
    QueryAst right = new QueryAst.Term("path", "OR", true);
    assertEquals(
        new QueryAst.BooleanNode(false, left, right),
        QueryAst.parse("content:\"AND\" AND path:\"OR\""));
    assertEquals(
        new QueryAst.BooleanNode(true, left, right),
        QueryAst.parse("content:\"AND\" OR path:\"OR\""));
    assertEquals(
        new QueryAst.BooleanNode(false, left, new QueryAst.Negation(right)),
        QueryAst.parse("content:\"AND\" AND NOT path:\"OR\""));
    assertEquals(
        new QueryAst.Term("symbol", "AND OR NOT", true), QueryAst.parse("symbol:\"AND OR NOT\""));
    assertEquals(new QueryAst.Term("all", "AND", true), QueryAst.parse("\"AND\""));
  }

  @Test
  void bareSyntaxTokensRemainIllegalAfterLiteralFix() {
    for (String field : FIELDS) {
      for (String token : RESERVED) {
        String input = field + ":" + token;
        assertThrows(
            IllegalArgumentException.class,
            () -> QueryAst.parse(input),
            () -> "bare operator was accepted: " + input);
      }
    }
    for (String malformed :
        List.of(
            "content:",
            "path:AND",
            "content:NOT",
            "content:OR",
            "content:)",
            "content::",
            "path:\"\"")) {
      assertThrows(IllegalArgumentException.class, () -> QueryAst.parse(malformed));
    }
  }

  @Test
  void deterministicLiteralCorpusHasNoAccidentalOperatorInterpretation() {
    Random random = new Random(78541);
    String alphabet = "ANDORNOT:()a_b";
    for (int trial = 0; trial < 3000; trial++) {
      int count = 1 + random.nextInt(10);
      StringBuilder literal = new StringBuilder();
      for (int i = 0; i < count; i++)
        literal.append(alphabet.charAt(random.nextInt(alphabet.length())));
      String word = literal.toString();
      String input = "content:\"" + word + "\"";
      assertEquals(
          new QueryAst.Term("content", word, true),
          QueryAst.parse(input),
          () -> "literal tokenization failed for " + input);
    }
  }

  @Test
  void inputAndLexicalQuotasStillRejectAbusiveQueries() {
    String longInput = "content:\"" + "a".repeat(505) + "\"";
    assertThrows(IllegalArgumentException.class, () -> QueryAst.parse(longInput));
    assertThrows(IllegalArgumentException.class, () -> QueryAst.parse("\"unterminated"));
    assertThrows(IllegalArgumentException.class, () -> QueryAst.parse("content:\"\""));
    assertThrows(
        IllegalArgumentException.class,
        () -> QueryAst.parse("content:\"AND\"" + " OR x".repeat(65)));
    assertThrows(
        IllegalArgumentException.class,
        () -> QueryAst.parse("((((( (( (( content:\"OR\" )) )) )) )))"));
  }
}
