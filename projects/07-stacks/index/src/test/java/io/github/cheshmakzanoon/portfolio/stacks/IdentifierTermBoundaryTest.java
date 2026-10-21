package io.github.cheshmakzanoon.portfolio.stacks;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.lucene.analysis.tokenattributes.*;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.*;
import org.junit.jupiter.api.Test;

class IdentifierTermBoundaryTest {
  @Test
  void immenseDeclarationsDoNotTurnBoundedSourceDocumentsIntoIndexFailures() throws Exception {
    for (String name : List.of("x".repeat(32767), "界".repeat(10923), "İ".repeat(10923))) {
      String content = "class " + name + " { int searchableMarker; }";
      var entry =
          new SnapshotManifest.Entry(
              "Source.java", Digests.sha256(content), "java", "source", List.of(), "", "");
      var document = DocumentMapper.map(new SourceDocument("tenant", "repo", "r1", entry, content));
      assertFalse(
          Arrays.asList(document.getValues("declaration")).contains(name.toLowerCase(Locale.ROOT)));
      try (var directory = new ByteBuffersDirectory();
          var analyzer = new CodeIdentifierAnalyzer()) {
        try (var writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
          writer.addDocument(document);
          writer.commit();
        }
        try (var reader = DirectoryReader.open(directory)) {
          assertEquals(
              1,
              new IndexSearcher(reader)
                  .count(new TermQuery(new Term("content", "searchablemarker"))));
          assertEquals(content, reader.storedFields().document(0).get("content"));
        }
      }
    }
  }

  @Test
  void asciiCjkSupplementaryAndLowercaseExpansionHonorExactUtf8LimitWithoutPrefixTerms() {
    for (String accepted :
        List.of("x".repeat(32766), "界".repeat(10922), "İ".repeat(10922), "𐐀".repeat(8191))) {
      var tokens = CodeIdentifierAnalyzer.parts(accepted);
      assertEquals(1, tokens.size());
      assertTrue(tokens.getFirst().term().getBytes(StandardCharsets.UTF_8).length <= 32766);
      assertEquals(accepted.toLowerCase(Locale.ROOT), tokens.getFirst().term());
      assertEquals(0, tokens.getFirst().start());
      assertEquals(accepted.length(), tokens.getFirst().end());
    }
    for (String rejected :
        List.of("x".repeat(32767), "界".repeat(10923), "İ".repeat(10923), "𐐀".repeat(8192))) {
      assertTrue(rejected.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8).length > 32766);
      assertTrue(
          CodeIdentifierAnalyzer.parts(rejected).isEmpty(),
          "oversized whole terms are omitted, not cut into a prefix");
    }
    var components = CodeIdentifierAnalyzer.parts("x".repeat(32767) + "Suffix next");
    assertEquals(
        List.of("suffix", "next"),
        components.stream().map(CodeIdentifierAnalyzer.Part::term).toList());
    assertEquals(32767, components.getFirst().start());
    assertEquals(32773, components.getFirst().end());
    assertEquals(1, components.getFirst().position());
    assertEquals(2, components.get(1).position());
  }

  @Test
  void tokenizerPreservesSkippedWordGapsAndSourceOffsetsAcrossActualIndexAndQueryAnalysis()
      throws Exception {
    String input = "before " + "x".repeat(65537) + " after";
    try (var analyzer = new CodeIdentifierAnalyzer();
        var stream = analyzer.tokenStream("content", input)) {
      var term = stream.addAttribute(CharTermAttribute.class);
      var offset = stream.addAttribute(OffsetAttribute.class);
      var increment = stream.addAttribute(PositionIncrementAttribute.class);
      stream.reset();
      assertTrue(stream.incrementToken());
      assertEquals("before", term.toString());
      assertEquals(1, increment.getPositionIncrement());
      assertEquals(0, offset.startOffset());
      assertEquals(6, offset.endOffset());
      assertTrue(stream.incrementToken());
      assertEquals("after", term.toString());
      assertEquals(2, increment.getPositionIncrement());
      assertEquals(input.length() - 5, offset.startOffset());
      assertEquals(input.length(), offset.endOffset());
      assertFalse(stream.incrementToken());
      stream.end();
      assertEquals(input.length(), offset.endOffset());
    }
    try (var directory = new ByteBuffersDirectory();
        var analyzer = new CodeIdentifierAnalyzer()) {
      try (var writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
        var doc = new Document();
        doc.add(new TextField("content", input, Field.Store.YES));
        writer.addDocument(doc);
        var accepted = new Document();
        accepted.add(new TextField("content", "界".repeat(10922), Field.Store.NO));
        writer.addDocument(accepted);
        writer.commit();
      }
      try (var reader = DirectoryReader.open(directory)) {
        var searcher = new IndexSearcher(reader);
        assertEquals(1, searcher.count(new TermQuery(new Term("content", "before"))));
        assertEquals(1, searcher.count(new TermQuery(new Term("content", "after"))));
        assertEquals(1, searcher.count(new TermQuery(new Term("content", "界".repeat(10922)))));
        assertEquals(
            0,
            searcher.count(new PhraseQuery("content", "before", "after")),
            "omission must not join words across an immense token");
        assertEquals(input, reader.storedFields().document(0).get("content"));
      }
    }
  }
}
