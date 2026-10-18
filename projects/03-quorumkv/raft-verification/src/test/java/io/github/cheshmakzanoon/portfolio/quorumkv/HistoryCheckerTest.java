package io.github.cheshmakzanoon.portfolio.quorumkv;

import static io.github.cheshmakzanoon.portfolio.quorumkv.HistoryChecker.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class HistoryCheckerTest {
  @Test
  void rejectsStaleReadAndIncludesUnknownMutation() {
    var checker = new HistoryChecker(10000);
    assertEquals(
        Verdict.FAIL,
        checker.check(
            List.of(
                new Call(0, 1, Operation.PUT, "a", null, null, "OK"),
                new Call(2, 3, Operation.GET, null, null, null, "OK")),
            null));
    assertEquals(
        Verdict.PASS,
        checker.check(
            List.of(
                new Call(0, 1, Operation.PUT, "a", null, null, "TIMEOUT"),
                new Call(2, 3, Operation.GET, null, null, "a", "OK")),
            null));
    assertEquals(
        Verdict.FAIL,
        checker.check(List.of(new Call(0, 1, Operation.CAS, "b", "a", null, "OK")), null));
    assertEquals(
        Verdict.INCONCLUSIVE,
        new HistoryChecker(0)
            .check(List.of(new Call(0, 1, Operation.GET, null, null, null, "OK")), null));
  }

  @Test
  void timedOutOperationMayLinearizeAfterItsTimeout() {
    var history =
        List.of(
            new Call(0, 1, Operation.PUT, "a", null, null, "TIMEOUT"),
            new Call(2, 3, Operation.GET, null, null, null, "OK"),
            new Call(4, 5, Operation.GET, null, null, "a", "OK"));
    assertEquals(Verdict.PASS, new HistoryChecker(1000).check(history, null));
  }

  @Test
  void nullAndLiteralNullHaveDifferentMemoStates() {
    var history =
        List.of(
            new Call(0, 2, Operation.PUT, "null", null, null, "OK"),
            new Call(0, 2, Operation.DELETE, null, null, null, "OK"),
            new Call(3, 4, Operation.GET, null, null, "null", "OK"));
    assertEquals(Verdict.PASS, new HistoryChecker(1000).check(history, null));
  }
}
