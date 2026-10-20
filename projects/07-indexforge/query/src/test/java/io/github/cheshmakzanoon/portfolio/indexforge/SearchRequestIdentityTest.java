package io.github.cheshmakzanoon.portfolio.indexforge;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class SearchRequestIdentityTest {
  @Test
  void v2GoldenFrameRetainsUtf16LengthsAndSortedFilterTuples() throws Exception {
    var first = new LinkedHashMap<String, String>();
    first.put("repository", "public");
    first.put("language", "java");
    var reverse = new LinkedHashMap<String, String>();
    reverse.put("language", "java");
    reverse.put("repository", "public");
    // SHA256 of an independently encoded big-endian v2 frame, including the surrogate pair.
    String expected = "f881c2449a52c4e35681af32aa108cb57d3ece519f884327d6cf871b5e132327";
    for (var filters : List.of(first, reverse))
      assertEquals(
          expected,
          SearchRequestIdentity.digest(
              new SearchEngine.Request("reader𝛂", 7, null, filters),
              QueryPlanner.Mode.SOURCE_AWARE,
              "v2"));
  }
}
