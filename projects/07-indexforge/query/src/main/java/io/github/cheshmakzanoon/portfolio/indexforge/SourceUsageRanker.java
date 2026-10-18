package io.github.cheshmakzanoon.portfolio.indexforge;

import java.util.*;

/** R3's fixed stable subset order. Inputs must come from the authorized leased graph view. */
public final class SourceUsageRanker {
  public static final int CAP = 32;

  public enum Status {
    USABLE_OBSERVED,
    SATURATED_OBSERVED,
    UNKNOWN
  }

  public record Member(String opaqueIdentity, String javaName, int observedCallers, Status status) {
    public Member {
      Objects.requireNonNull(opaqueIdentity);
      Objects.requireNonNull(javaName);
      Objects.requireNonNull(status);
      if (opaqueIdentity.length() > 2048
          || javaName.length() > 256
          || observedCallers < 0
          || observedCallers > CAP
          || status == Status.USABLE_OBSERVED && observedCallers == 0
          || status == Status.SATURATED_OBSERVED && observedCallers != CAP)
        throw new IllegalArgumentException("observed caller evidence");
    }
  }

  public record Inventory(boolean declarationMembershipKnown, List<Member> members) {
    public Inventory {
      members = List.copyOf(members);
      if (members.size() > 128 || !declarationMembershipKnown && !members.isEmpty())
        throw new IllegalArgumentException("declaration inventory quota/partial membership");
    }

    public static Inventory unknown() {
      return new Inventory(false, List.of());
    }
  }

  public List<CandidateRetriever.Candidate> rank(
      List<CandidateRetriever.Candidate> original,
      String query,
      Map<Integer, Inventory> inventories) {
    if (original.size() > 500) throw new IllegalArgumentException("usage candidate quota");
    if (!PlainTextQuery.identifier(query)) return List.copyOf(original);
    Set<Integer> seen = new HashSet<>();
    List<Integer> slots = new ArrayList<>();
    Map<Integer, Integer> counts = new HashMap<>();
    for (int i = 0; i < original.size(); i++) {
      var candidate = original.get(i);
      if (!seen.add(candidate.document()))
        throw new IllegalArgumentException("duplicate usage candidate");
      var inventory = inventories.get(candidate.document());
      // Membership first, for EVERY candidate: saturated evidence in another candidate does
      // not make an unsupported third candidate an incidental mention (A06 review addendum).
      if (inventory == null || !inventory.declarationMembershipKnown())
        return List.copyOf(original);
      int max = -1;
      boolean unknown = false;
      for (var member : inventory.members()) {
        if (!member.javaName().equalsIgnoreCase(query)) continue;
        if (member.status() == Status.UNKNOWN || member.observedCallers() == 0) unknown = true;
        else max = Math.max(max, member.observedCallers());
      }
      if (unknown && max != CAP) return List.copyOf(original);
      if (max >= 0) {
        slots.add(i);
        counts.put(candidate.document(), max);
      }
    }
    if (slots.size() < 2) return List.copyOf(original);
    List<CandidateRetriever.Candidate> subset = new ArrayList<>();
    for (int i : slots) subset.add(original.get(i));
    // List.sort is stable: equal observed counts retain the exact R0 order, not key order.
    subset.sort(
        Comparator.comparingInt((CandidateRetriever.Candidate c) -> counts.get(c.document()))
            .reversed());
    var result = new ArrayList<>(original);
    for (int i = 0; i < slots.size(); i++) {
      var c = subset.get(i);
      result.set(
          slots.get(i),
          new CandidateRetriever.Candidate(
              c.document(),
              c.key(),
              c.score(),
              c.features()
                  + " observedCallerMembers="
                  + counts.get(c.document())
                  + " callerCountCap=32 ordering=observed-indexed-source-usage"));
    }
    return List.copyOf(result);
  }
}
