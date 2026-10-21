package io.github.cheshmakzanoon.portfolio.stacks;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class SourceUsageRankerTest {
  private static CandidateRetriever.Candidate hit(int document, String key, float score) {
    return new CandidateRetriever.Candidate(document, key, score, "base");
  }

  private static SourceUsageRanker.Inventory member(String identity, String name, int n) {
    return new SourceUsageRanker.Inventory(
        true,
        List.of(
            new SourceUsageRanker.Member(
                identity,
                name,
                n,
                n == 32
                    ? SourceUsageRanker.Status.SATURATED_OBSERVED
                    : n == 0
                        ? SourceUsageRanker.Status.UNKNOWN
                        : SourceUsageRanker.Status.USABLE_OBSERVED)));
  }

  private static List<Integer> ids(List<CandidateRetriever.Candidate> hits) {
    return hits.stream().map(CandidateRetriever.Candidate::document).toList();
  }

  @Test
  void onlyExactQueriedMemberSlotsMoveWithStableCountTies() {
    var original =
        List.of(hit(1, "z", 9), hit(2, "mention", 8), hit(3, "a", 7), hit(4, "same-count", 6));
    var inventories =
        Map.of(
            1,
            member("ownerA#bounded(int)", "bounded", 1),
            2,
            member("unrelated#read()", "read", 32),
            3,
            member("ownerB#bounded(Stream)", "bounded", 2),
            4,
            member("ownerC#bounded(long)", "bounded", 2));
    var result = new SourceUsageRanker().rank(original, "bounded", inventories);
    assertEquals(List.of(3, 2, 4, 1), ids(result));
    assertEquals(7, result.getFirst().score());
    assertEquals(original.get(1), result.get(1));
    assertTrue(result.getFirst().features().contains("observedCallerMembers=2"));
    assertTrue(result.getFirst().features().contains("observed-indexed-source-usage"));
    assertEquals(original, new SourceUsageRanker().rank(original, "read AND bounded", inventories));
  }

  @Test
  void unsupportedThirdCandidateBlocksTwoEligiblePrivateHelpers() {
    var original =
        List.of(
            hit(1, "first-private", 9), hit(2, "second-private", 8), hit(3, "unknown-third", 7));
    var inventories =
        Map.of(
            1,
            member("a#privateHelper()", "bounded", 1),
            2,
            member("b#privateHelper()", "bounded", 5),
            3,
            SourceUsageRanker.Inventory.unknown());
    assertEquals(original, new SourceUsageRanker().rank(original, "bounded", inventories));
    assertEquals(
        original,
        new SourceUsageRanker()
            .rank(original, "bounded", Map.of(1, inventories.get(1), 2, inventories.get(2))));
  }

  @Test
  void saturationNeverCertifiesAnotherCandidatesUnknownMembership() {
    var original =
        List.of(hit(1, "ordinary", 9), hit(2, "saturated", 8), hit(3, "maybe-declares", 7));
    var inventories =
        Map.of(
            1,
            member("a#x()", "bytes", 1),
            2,
            member("b#x()", "bytes", 32),
            3,
            SourceUsageRanker.Inventory.unknown());
    assertEquals(original, new SourceUsageRanker().rank(original, "bytes", inventories));
  }

  @Test
  void unknownMatchingOverloadAndZeroObservedUsageDoNotBecomeZeroPopularity() {
    var original = List.of(hit(1, "several-overloads", 9), hit(2, "known", 8));
    var uncertain =
        new SourceUsageRanker.Inventory(
            true,
            List.of(
                member("a#put(int)", "put", 2).members().getFirst(),
                member("a#put(String)", "put", 0).members().getFirst()));
    assertEquals(
        original,
        new SourceUsageRanker()
            .rank(original, "put", Map.of(1, uncertain, 2, member("b#put()", "put", 5))));
    var saturated =
        new SourceUsageRanker.Inventory(
            true,
            List.of(
                member("a#put(int)", "put", 32).members().getFirst(),
                member("a#put(String)", "put", 0).members().getFirst()));
    assertEquals(
        List.of(1, 2),
        ids(
            new SourceUsageRanker()
                .rank(
                    List.of(original.get(1), original.get(0)),
                    "put",
                    Map.of(1, saturated, 2, member("b#put()", "put", 5)))));
  }

  @Test
  void maxMemberEvidenceNeverSumsSameNameOverloadsAndRenamingIsNotAPrior() {
    var original = List.of(hit(1, "short", 9), hit(2, "long", 8));
    var many =
        new SourceUsageRanker.Inventory(
            true,
            List.of(
                member("a#put(int)", "put", 2).members().getFirst(),
                member("nested#put(String)", "put", 2).members().getFirst()));
    assertEquals(
        List.of(2, 1),
        ids(
            new SourceUsageRanker()
                .rank(original, "PUT", Map.of(1, many, 2, member("b#put()", "put", 3)))));
    var renamed = List.of(hit(1, "different/tenant/path", 9), hit(2, "other/project/path", 8));
    assertEquals(
        List.of(2, 1),
        ids(
            new SourceUsageRanker()
                .rank(
                    renamed,
                    "renamed",
                    Map.of(
                        1, member("opaque1", "renamed", 2), 2, member("opaque2", "renamed", 3)))));
  }
}
