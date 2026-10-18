package io.github.cheshmakzanoon.portfolio.indexforge;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class CodeFeaturesTest {
  @Test
  void genericApiTokenPatternsProduceExplicitlyHeuristicTopics() {
    Map<String, String> fragments =
        Map.of(
            "strict_utf8",
                "StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)",
            "message_digest", "MessageDigest.getInstance(algorithm)",
            "mac", "Mac.getInstance(algorithm); new SecretKeySpec(bytes, algorithm)",
            "atomic_move", "Files.move(from,to,StandardCopyOption.ATOMIC_MOVE)",
            "durable_force", "FileChannel.open(path); channel.force(true)",
            "archive", "new ZipInputStream(input)",
            "index_reader", "DirectoryReader.open(directory)",
            "reader_lifetime",
                "class Holder implements AutoCloseable { DirectoryReader reader; AtomicBoolean state; void close(){state.compareAndSet(false,true);} }",
            "query_filter", "new TermQuery(term); BooleanClause.Occur.FILTER",
            "lexical_scan", "Character.isWhitespace(c); input.substring(0); input.indexOf(c)");
    for (var entry : fragments.entrySet()) {
      assertTrue(CodeFeatures.features(entry.getValue()).contains(entry.getKey()), entry.getKey());
      assertFalse(CodeFeatures.topics(CodeFeatures.features(entry.getValue())).isBlank());
    }
    assertEquals(Set.of("archive"), CodeFeatures.features("new ZipFile(path)"));
    assertEquals(
        Set.of("index_reader"),
        CodeFeatures.features("DirectoryReader reader; new IndexSearcher(reader)"));
    assertTrue(CodeFeatures.vocabulary().contains("malformed"));
  }

  @Test
  void commentsStringsTextBlocksPartialApiMentionsAndShadowedNamesAreNotEvidence() {
    String fragment =
        "StandardCharsets.UTF_8.newDecoder(CodingErrorAction.REPORT); Files.move(a,b,StandardCopyOption.ATOMIC_MOVE);";
    String decoy =
        "//"
            + fragment
            + "\n/*"
            + fragment
            + "*/\nclass Example { String code=\""
            + fragment
            + "\"; String block=\"\"\"\n"
            + fragment
            + "\n\"\"\"; }";
    assertEquals(Set.of(), CodeFeatures.features(decoy));
    assertEquals(Set.of(), CodeFeatures.features("class Files {} " + fragment));
    for (String incomplete :
        List.of(
            "StandardCharsets.UTF_8; CodingErrorAction.REPORT;",
            "StandardCharsets.UTF_8.newDecoder();",
            "Mac.getInstance(x);",
            "Files.move(a,b);",
            "FileChannel.open(path);",
            "DirectoryReader reader;",
            "DirectoryReader reader; AutoCloseable resource;",
            "DirectoryReader reader; AutoCloseable resource; AtomicBoolean state;",
            "BooleanClause.Occur.FILTER;",
            "Character.isWhitespace(c);",
            "Character.isWhitespace(c); input.substring(0);"))
      assertEquals(Set.of(), CodeFeatures.features(incomplete), incomplete);
    assertEquals(Set.of(), CodeFeatures.features("; ".repeat(100001) + fragment));
    assertThrows(
        IllegalArgumentException.class, () -> CodeFeatures.features(" ".repeat(1_048_577)));
  }
}
