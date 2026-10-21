package io.github.cheshmakzanoon.portfolio.stacks;

import java.io.*;
import java.util.*;
import org.apache.lucene.search.*;

public final class CandidateRetriever {
  public record Candidate(int document, String key, float score, String features) {
    public Candidate(int document, String key, float score) {
      this(document, key, score, "");
    }
  }

  public List<Candidate> retrieve(GenerationLease lease, Query authorized) throws IOException {
    TopDocs hits =
        lease
            .searcher()
            .search(
                authorized,
                500,
                new Sort(SortField.FIELD_SCORE, new SortField("key_sort", SortField.Type.STRING)),
                true);
    List<Candidate> out = new ArrayList<>();
    for (ScoreDoc h : hits.scoreDocs)
      out.add(
          new Candidate(
              h.doc,
              lease.reader().storedFields().document(h.doc, Set.of("key")).get("key"),
              h.score));
    return out;
  }
}
