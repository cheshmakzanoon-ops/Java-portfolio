package io.github.cheshmakzanoon.portfolio.indexforge;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SnippetBuilderTest {
  @Test
  void caseExpansionNeverTurnsNormalizedOffsetsIntoInvalidSourceOffsets() {
    String source = "İ".repeat(1000) + "needle";
    String snippet = SnippetBuilder.snippet(source, "NEEDLE");
    assertTrue(snippet.endsWith("needle"));
    assertTrue(source.endsWith(snippet));
    assertTrue(snippet.length() <= 240);
    assertFalse(snippet.contains("i̇"), "return original source, not its case-normalized copy");
  }

  @Test
  void startAndEndBoundariesPreserveCompleteSupplementaryCharacters() {
    String letter = new String(Character.toChars(0x10400));
    String atStart = "x".repeat(60) + letter + "x".repeat(59) + "needle";
    String snippet = SnippetBuilder.snippet(atStart, "needle");
    assertTrue(snippet.startsWith(letter));
    assertTrue(snippet.endsWith("needle"));
    String atEnd = "x".repeat(239) + letter + "tail";
    assertEquals("x".repeat(239), SnippetBuilder.snippet(atEnd, "missing"));
    assertEquals("", SnippetBuilder.snippet("", "missing"));
    assertEquals("plain text", SnippetBuilder.snippet("plain text", ""));
  }

  @Test
  void queryMetacharactersAreMatchedLiterallyAndReturnedAsText() {
    String prefix = "prefix ".repeat(50);
    String source = prefix + "<script>ab[c].*\\E</script> suffix";
    String snippet = SnippetBuilder.snippet(source, "ab[c].*\\E");
    assertTrue(snippet.contains("<script>ab[c].*\\E</script>"));
    assertTrue(source.endsWith(snippet));
    assertTrue(snippet.length() <= 240);
  }
}
