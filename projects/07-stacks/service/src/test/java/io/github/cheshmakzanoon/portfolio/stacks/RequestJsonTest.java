package io.github.cheshmakzanoon.portfolio.stacks;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

class RequestJsonTest {
  private static JsonMapper mapper() {
    var builder = JsonMapper.builder();
    new RequestJsonConfiguration().strictRequestJson().customize(builder);
    return builder.build();
  }

  private static final String INGEST =
      "{\"repository\":\"public\",\"revision\":\"v1\",\"idempotencyKey\":\"key\",\"path\":\"src\"}";
  private static final String SEARCH = "{\"text\":\"atomic\",\"pageSize\":10}";

  @Test
  void documentedIngestionDefaultsAndExplicitValuesRemainCompatible() {
    var json = mapper();
    var defaults = json.readValue(INGEST, SearchController.IngestRequest.class);
    assertEquals("public", defaults.repository());
    assertEquals("v1", defaults.revision());
    assertEquals("key", defaults.idempotencyKey());
    assertEquals("src", defaults.path());
    assertFalse(defaults.archive());
    assertFalse(defaults.complete());
    assertEquals(Set.of(), defaults.deletions());
    var nullable =
        json.readValue(
            INGEST.replace("}", ",\"archive\":null,\"complete\":null,\"deletions\":null}"),
            SearchController.IngestRequest.class);
    assertEquals(defaults, nullable);
    var explicit =
        json.readValue(
            INGEST.replace(
                "}", ",\"archive\":false,\"complete\":true,\"deletions\":[\"src/Old.java\"]}"),
            SearchController.IngestRequest.class);
    assertFalse(explicit.archive());
    assertTrue(explicit.complete());
    assertEquals(Set.of("src/Old.java"), explicit.deletions());
  }

  @Test
  void documentedSearchFiltersAndNullCursorRemainExactlyTyped() {
    var json = mapper();
    assertEquals(
        new SearchEngine.Request("atomic", 10, null, Map.of()),
        json.readValue(SEARCH, SearchEngine.Request.class));
    assertEquals(
        new SearchEngine.Request("atomic", 10, null, Map.of("repository", "public")),
        json.readValue(
            SEARCH.replace("}", ",\"cursor\":null,\"filters\":{\"repository\":\"public\"}}"),
            SearchEngine.Request.class));
    assertEquals(
        new SearchController.SuggestRequest("atomic"),
        json.readValue("{\"prefix\":\"atomic\"}", SearchController.SuggestRequest.class));
  }

  @Test
  void duplicateKeysAreRejectedAtEveryObjectDepth() {
    var json = mapper();
    for (String invalid :
        List.of(
            SEARCH.replace("\"text\":\"atomic\"", "\"text\":\"first\",\"text\":\"atomic\""),
            SEARCH.replace("\"pageSize\":10", "\"pageSize\":10,\"pageSize\":1"),
            SEARCH.replace(
                "}", ",\"filters\":{\"repository\":\"denied\",\"repository\":\"public\"}}")))
      assertThrows(
          JacksonException.class, () -> json.readValue(invalid, SearchEngine.Request.class));
    for (String field : List.of("repository", "revision", "idempotencyKey", "path")) {
      String invalid = INGEST.replace("{", "{\"" + field + "\":\"shadow\",");
      assertThrows(
          JacksonException.class,
          () -> json.readValue(invalid, SearchController.IngestRequest.class));
    }
  }

  @Test
  void multipleDocumentsAndUnknownFieldsDoNotSilentlyChangeTheRequestMeaning() {
    var json = mapper();
    for (String trailing : List.of(" {}", " []", " true", " null", " 7", " \"second\"")) {
      assertThrows(
          JacksonException.class,
          () -> json.readValue(INGEST + trailing, SearchController.IngestRequest.class));
      assertThrows(
          JacksonException.class,
          () -> json.readValue(SEARCH + trailing, SearchEngine.Request.class));
    }
    for (String unknown : List.of("compelete", "tenant", "admin", "repositoryOverride")) {
      String invalid = INGEST.replace("}", ",\"" + unknown + "\":true}");
      assertThrows(
          JacksonException.class,
          () -> json.readValue(invalid, SearchController.IngestRequest.class));
    }
    assertThrows(
        JacksonException.class,
        () -> json.readValue(SEARCH.replace("}", ",\"offset\":500}"), SearchEngine.Request.class));
  }

  @Test
  void ingestionIdentifiersCannotBeCoercedFromNumbersOrBooleans() {
    var json = mapper();
    for (String scalar : List.of("7", "7.1", "true", "false")) {
      for (var field :
          Map.of(
                  "repository", "public",
                  "revision", "v1",
                  "idempotencyKey", "key",
                  "path", "src")
              .entrySet()) {
        String invalid =
            INGEST.replace(
                "\"" + field.getKey() + "\":\"" + field.getValue() + "\"",
                "\"" + field.getKey() + "\":" + scalar);
        assertThrows(
            JacksonException.class,
            () -> json.readValue(invalid, SearchController.IngestRequest.class));
      }
      assertThrows(
          JacksonException.class,
          () ->
              json.readValue("{\"prefix\":" + scalar + "}", SearchController.SuggestRequest.class));
    }
  }

  @Test
  void snapshotFlagsRequireBooleansAndCannotSilentlyBecomeTrueFromScalarInputs() {
    var json = mapper();
    for (String field : List.of("archive", "complete")) {
      for (String value : List.of("1", "0", "1.0", "\"true\"", "\"false\"", "\"\"", "[]", "{}")) {
        String invalid = INGEST.replace("}", ",\"" + field + "\":" + value + "}");
        assertThrows(
            JacksonException.class,
            () -> json.readValue(invalid, SearchController.IngestRequest.class),
            invalid);
      }
    }
  }

  @Test
  void pageSizeCannotBeRoundedTruncatedParsedFromAStringOrDefaultedFromNull() {
    var json = mapper();
    for (String value :
        List.of("1.1", "1.0", "\"10\"", "\"\"", "true", "null", "[]", "{}", "2147483648")) {
      String invalid = SEARCH.replace("10", value);
      assertThrows(
          JacksonException.class, () -> json.readValue(invalid, SearchEngine.Request.class));
    }
    for (int boundary : List.of(1, 100))
      assertEquals(
          boundary,
          json.readValue(
                  SEARCH.replace("10", Integer.toString(boundary)), SearchEngine.Request.class)
              .pageSize());
  }

  @Test
  void deletionPathsAreValidatedBeforeSourceAcquisitionAndCannotBeMutatedAfterAdmission() {
    HashSet<String> supplied = new HashSet<>(Set.of("src/Old.java"));
    var request =
        new SearchController.IngestRequest("public", "v1", "key", "src", false, false, supplied);
    supplied.add("src/Late.java");
    assertEquals(Set.of("src/Old.java"), request.deletions());
    assertThrows(UnsupportedOperationException.class, () -> request.deletions().clear());
    for (String path :
        List.of("../private", "/absolute", "src/../Old.java", "src//Old.java", "./Old.java", "")) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new SearchController.IngestRequest(
                  "public", "v1", "key", "src", false, false, Set.of(path)));
    }
    supplied.clear();
    supplied.add(null);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SearchController.IngestRequest(
                "public", "v1", "key", "src", false, false, supplied));
  }

  @Test
  void deletionListsRejectScalarStringsNumericMembersAndTraversal() {
    var json = mapper();
    for (String value :
        List.of("\"src/Old.java\"", "[7]", "[true]", "[null]", "[\"../private\"]")) {
      String invalid = INGEST.replace("}", ",\"deletions\":" + value + "}");
      assertThrows(
          JacksonException.class,
          () -> json.readValue(invalid, SearchController.IngestRequest.class));
    }
  }
}
