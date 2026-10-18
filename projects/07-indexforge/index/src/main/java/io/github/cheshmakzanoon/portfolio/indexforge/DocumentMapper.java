package io.github.cheshmakzanoon.portfolio.indexforge;

import java.util.*;
import org.apache.lucene.document.*;

public final class DocumentMapper {
  private static final FieldType PROSE;

  static {
    PROSE = new FieldType(TextField.TYPE_NOT_STORED);
    PROSE.setStoreTermVectors(true);
    PROSE.freeze();
  }

  private DocumentMapper() {}

  public static Document map(SourceDocument s) {
    return map(s, null);
  }

  public static Document map(SourceDocument s, SourceFacts.Unit facts) {
    Document d = new Document();
    exact(d, "key", s.key());
    exact(d, "tenant", s.tenant());
    exact(d, "repository", s.repository());
    exact(d, "path", s.entry().path());
    exact(d, "sha256", s.entry().sha256());
    exact(d, "revision", s.revision());
    exact(d, "language", s.entry().language());
    exact(d, "kind", s.entry().kind());
    exact(d, "coordinates", s.entry().coordinates());
    exact(d, "version", s.entry().version());
    if (facts != null) {
      exact(d, "source_schema", SourceFacts.SCHEMA);
      exact(d, "source_inventory", Boolean.toString(facts.declarationInventoryKnown()));
      try {
        d.add(new StoredField("source_facts", SourceWire.encode(facts)));
      } catch (java.io.IOException invalid) {
        throw new java.io.UncheckedIOException("source summary encoding", invalid);
      }
    }
    if (s.entry().language().equals("java")) {
      d.add(
          new TextField(
              "code_features",
              CodeFeatures.topics(CodeFeatures.features(s.content())),
              Field.Store.NO));
      for (String declaration : DeclarationScanner.names(s.content()))
        exact(d, "declaration", declaration.toLowerCase(Locale.ROOT));
    }
    d.add(new TextField("content", s.content(), Field.Store.YES));
    d.add(new Field("prose", s.content(), PROSE));
    d.add(new TextField("identifiers", String.join(" ", s.entry().symbols()), Field.Store.NO));
    d.add(new TextField("path_tokens", s.entry().path(), Field.Store.NO));
    for (String symbol : s.entry().symbols()) {
      exact(d, "symbol", symbol.toLowerCase(Locale.ROOT));
      d.add(new StoredField("symbol_original", symbol));
    }
    d.add(new SortedDocValuesField("key_sort", new org.apache.lucene.util.BytesRef(s.key())));
    d.add(new StoredField("schema", SnapshotManifest.SCHEMA));
    return d;
  }

  private static void exact(Document d, String field, String value) {
    d.add(new StringField(field, value, Field.Store.YES));
  }

  public static SourceDocument source(Document d) {
    List<String> symbols = Arrays.asList(d.getValues("symbol_original"));
    return new SourceDocument(
        d.get("tenant"),
        d.get("repository"),
        d.get("revision"),
        new SnapshotManifest.Entry(
            d.get("path"),
            d.get("sha256"),
            d.get("language"),
            d.get("kind"),
            symbols,
            d.get("coordinates"),
            d.get("version")),
        d.get("content"));
  }
}
