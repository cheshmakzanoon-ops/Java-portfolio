package io.github.cheshmakzanoon.portfolio.quorumkv;

import java.util.*;

/** Independent interval-order register/CAS search. Timeout responses stay optional operations. */
public final class HistoryChecker {
  public enum Verdict {
    PASS,
    FAIL,
    INCONCLUSIVE
  }

  public enum Operation {
    GET,
    PUT,
    DELETE,
    CAS
  }

  public record Call(
      long invocation,
      long response,
      Operation operation,
      String value,
      String expected,
      String observed,
      String code) {
    public Call {
      if (response < invocation) throw new IllegalArgumentException("INTERVAL");
    }
  }

  private record SearchState(long done, String value) {}

  private final long budget;
  private long visited;
  private boolean exhausted;

  public HistoryChecker(long budget) {
    this.budget = budget;
  }

  public Verdict check(List<Call> history, String initial) {
    if (history.size() > 62) return Verdict.INCONCLUSIVE;
    visited = 0;
    exhausted = false;
    boolean pass = search(history, 0, initial, new HashSet<>());
    return pass ? Verdict.PASS : exhausted ? Verdict.INCONCLUSIVE : Verdict.FAIL;
  }

  private boolean search(List<Call> calls, long done, String state, Set<SearchState> memo) {
    if (++visited > budget) {
      exhausted = true;
      return false;
    }
    if (Long.bitCount(done) == calls.size()) return true;
    SearchState key = new SearchState(done, state);
    if (!memo.add(key)) return false;
    for (int i = 0; i < calls.size(); i++) {
      long bit = 1L << i;
      if ((done & bit) != 0) continue;
      Call c = calls.get(i);
      boolean eligible = true;
      for (int j = 0; j < calls.size(); j++)
        if ((done & (1L << j)) == 0
            && j != i
            && !calls.get(j).code().equals("TIMEOUT")
            && calls.get(j).response() < c.invocation()) {
          eligible = false;
          break;
        }
      if (!eligible) continue;
      boolean unknown = c.code().equals("TIMEOUT");
      if (unknown && search(calls, done | bit, state, memo)) return true;
      String next = state;
      boolean accepts;
      switch (c.operation()) {
        case GET ->
            accepts = unknown || c.code().equals("OK") && Objects.equals(state, c.observed());
        case PUT -> {
          accepts = unknown || c.code().equals("OK");
          next = c.value();
        }
        case DELETE -> {
          accepts = unknown || c.code().equals("OK");
          next = null;
        }
        case CAS -> {
          boolean match = Objects.equals(state, c.expected());
          accepts =
              unknown
                  || (match ? c.code().equals("OK") : c.code().equals("CAS_MISMATCH"))
                      && Objects.equals(state, c.observed());
          if (match) next = c.value();
        }
        default -> throw new AssertionError();
      }
      if (accepts && search(calls, done | bit, next, memo)) return true;
    }
    return false;
  }
}
