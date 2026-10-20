package io.github.cheshmakzanoon.portfolio.matchcore;

import static io.github.cheshmakzanoon.portfolio.matchcore.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class BookCheckpointTest {
  private Book fixture() {
    Book book = new Book(10);
    book.apply(1, new Command("init", "buyer", Type.INIT, null, null, 0, 0, null, false, 1000, 0));
    book.apply(2, new Command("init", "seller", Type.INIT, null, null, 0, 0, null, false, 0, 10));
    book.apply(
        3, new Command("sell", "seller", Type.NEW, "s", Side.SELL, 10, 5, Tif.GTC, false, 0, 0));
    book.apply(
        4, new Command("buy", "buyer", Type.NEW, "b", Side.BUY, 10, 2, Tif.GTC, false, 0, 0));
    return book;
  }

  private Checkpoint checkpoint(
      Map<String, Balance> balances,
      List<Order> orders,
      List<Execution> executions,
      Map<String, Result> results,
      Set<String> used,
      long sequence) {
    return new Checkpoint(new View(sequence, balances, orders, executions, results), used);
  }

  private void rejectsAtomically(Checkpoint invalid) {
    Book target = new Book(10);
    View empty = target.view();
    assertThrows(IllegalArgumentException.class, () -> target.restore(invalid));
    assertEquals(empty, target.view(), "failed restore changed live state");
    target.restore(fixture().checkpoint());
    assertEquals(fixture().view(), target.view(), "failed restore prevented a later valid restore");
  }

  @Test
  void invalidBalancesReservationsAndAggregateSupplyCannotPartiallyRestore() {
    Checkpoint valid = fixture().checkpoint();
    View v = valid.view();
    for (Balance balance :
        List.of(
            new Balance(-1, 2, 0, 0),
            new Balance(1, -1, 0, 0),
            new Balance(980, 2, -1, 0),
            new Balance(980, 2, 981, 0),
            new Balance(980, 2, 0, -1),
            new Balance(980, 2, 0, 3),
            new Balance(980, 2, 1, 0),
            new Balance(Long.MAX_VALUE, 2, 0, 0))) {
      Map<String, Balance> balances = new HashMap<>(v.accounts());
      balances.put("buyer", balance);
      rejectsAtomically(
          checkpoint(
              balances,
              v.orders(),
              v.executions(),
              v.results(),
              valid.usedOrderIds(),
              v.sequence()));
    }
    Map<String, Balance> renamed = new HashMap<>(v.accounts());
    renamed.put("bad identity", renamed.remove("buyer"));
    rejectsAtomically(
        checkpoint(
            renamed, v.orders(), v.executions(), v.results(), valid.usedOrderIds(), v.sequence()));
    Map<String, Balance> excessiveLots = new HashMap<>(v.accounts());
    excessiveLots.put("buyer", new Balance(980, Long.MAX_VALUE, 0, 0));
    rejectsAtomically(
        checkpoint(
            excessiveLots,
            v.orders(),
            v.executions(),
            v.results(),
            valid.usedOrderIds(),
            v.sequence()));
  }

  @Test
  void invalidOrdersCannotForgeIndexesPriorityOrHeldFunds() {
    Checkpoint valid = fixture().checkpoint();
    View v = valid.view();
    for (Order order :
        List.of(
            new Order("unknown", "s", Side.SELL, 10, 3, 3, 0),
            new Order("seller", "bad id", Side.SELL, 10, 3, 3, 0),
            new Order("seller", "s", null, 10, 3, 3, 0),
            new Order("seller", "s", Side.SELL, 0, 3, 3, 0),
            new Order("seller", "s", Side.SELL, 10, 0, 3, 0),
            new Order("seller", "s", Side.SELL, 10, 3, 0, 0),
            new Order("seller", "s", Side.SELL, 10, 3, 5, 0),
            new Order("seller", "s", Side.SELL, 10, 3, 3, -1),
            new Order("seller", "s", Side.SELL, 10, 3, 3, 4),
            new Order("seller", "s", Side.SELL, Long.MAX_VALUE, 3, 3, 0),
            new Order("seller", "s", Side.SELL, 10, 2, 3, 0)))
      rejectsAtomically(
          checkpoint(
              v.accounts(), List.of(order), v.executions(), v.results(), valid.usedOrderIds(), 4));
    rejectsAtomically(
        checkpoint(
            v.accounts(),
            List.of(v.orders().getFirst(), v.orders().getFirst()),
            v.executions(),
            v.results(),
            valid.usedOrderIds(),
            4));
    rejectsAtomically(
        checkpoint(v.accounts(), v.orders(), v.executions(), v.results(), Set.of("buyer:b"), 4));
    rejectsAtomically(
        checkpoint(
            v.accounts(),
            v.orders(),
            v.executions(),
            v.results(),
            Set.of("unknown:o", "seller:s", "buyer:b"),
            4));
    rejectsAtomically(
        checkpoint(v.accounts(), v.orders(), v.executions(), v.results(), Set.of("malformed"), 4));
  }

  @Test
  void invalidResultAndExecutionHistoriesAreRejected() {
    Checkpoint valid = fixture().checkpoint();
    View v = valid.view();
    for (Result result :
        List.of(
            new Result(0, "INITIALIZED", "0".repeat(64), List.of()),
            new Result(5, "INITIALIZED", "0".repeat(64), List.of()),
            new Result(2, "INITIALIZED", "0".repeat(64), List.of()),
            new Result(1, "CONFLICT", "0".repeat(64), List.of()),
            new Result(1, "INITIALIZED", "invalid", List.of()))) {
      Map<String, Result> results = new HashMap<>(v.results());
      results.put("buyer:init", result);
      rejectsAtomically(
          checkpoint(v.accounts(), v.orders(), v.executions(), results, valid.usedOrderIds(), 4));
    }
    Map<String, Result> missing = new HashMap<>(v.results());
    missing.remove("buyer:init");
    rejectsAtomically(
        checkpoint(v.accounts(), v.orders(), v.executions(), missing, valid.usedOrderIds(), 4));
    for (Execution execution :
        List.of(
            new Execution(3, "seller", "s", "buyer", "b", 10, 2),
            new Execution(4, "seller", "s", "seller", "s", 10, 2),
            new Execution(4, "unknown", "s", "buyer", "b", 10, 2),
            new Execution(4, "seller", "bad id", "buyer", "b", 10, 2),
            new Execution(4, "seller", "s", "buyer", "unused", 10, 2),
            new Execution(4, "seller", "s", "buyer", "b", 0, 2),
            new Execution(4, "seller", "s", "buyer", "b", 10, 0),
            new Execution(4, "seller", "s", "buyer", "b", Long.MAX_VALUE, 2))) {
      Map<String, Result> results = new HashMap<>(v.results());
      Result original = results.get("buyer:buy");
      results.put(
          "buyer:buy", new Result(4, original.code(), original.commandHash(), List.of(execution)));
      rejectsAtomically(
          checkpoint(
              v.accounts(), v.orders(), List.of(execution), results, valid.usedOrderIds(), 4));
    }
    rejectsAtomically(
        checkpoint(v.accounts(), v.orders(), List.of(), v.results(), valid.usedOrderIds(), 4));
    rejectsAtomically(
        checkpoint(
            v.accounts(), v.orders(), v.executions(), v.results(), valid.usedOrderIds(), -1));
  }

  @Test
  void validRoundTripPreservesExactFutureMatchingAndTerminalIdentity() {
    Book original = fixture();
    Book restored = new Book(10);
    restored.restore(original.checkpoint());
    assertEquals(original.view(), restored.view());
    for (Command command :
        List.of(
            new Command("again", "buyer", Type.NEW, "b", Side.BUY, 10, 1, Tif.GTC, false, 0, 0),
            new Command(
                "more", "buyer", Type.NEW, "next", Side.BUY, 10, 3, Tif.FOK, false, 0, 0))) {
      long sequence = original.view().sequence() + 1;
      assertEquals(original.apply(sequence, command), restored.apply(sequence, command));
      assertEquals(original.view(), restored.view());
    }
    Book empty = new Book();
    empty.restore(new Book().checkpoint());
    assertEquals(0, empty.view().sequence());
  }

  @Test
  void exactRetryAuthoritySurvivesRestoreWithoutChangingV1Hashes() {
    Command original =
        new Command("init", "alice", Type.INIT, null, null, 0, 0, null, false, 1000, 0);
    Command collision =
        new Command("init", "alice", Type.INIT, "null", null, 0, 0, null, false, 1000, 0);
    assertNotEquals(original, collision);
    assertEquals(original.hash(), collision.hash(), "persisted v1 hash stays unchanged");
    Book live = new Book();
    Result result = live.apply(1, original);
    assertEquals(result, live.lookup(original));
    assertEquals("CONFLICT", live.lookup(collision).code());
    Book stateOnly = new Book();
    stateOnly.restore(live.checkpoint());
    assertEquals(live.view(), stateOnly.view());
    assertThrows(IllegalStateException.class, () -> stateOnly.lookup(original));
    assertThrows(IllegalStateException.class, () -> stateOnly.lookup(collision));
    Book restored = new Book();
    restored.restore(live.checkpoint(), List.of(original));
    assertEquals(result, restored.lookup(original));
    assertEquals("CONFLICT", restored.lookup(collision).code());
    Book invalid = new Book();
    Command wrong = new Command("init", "alice", Type.INIT, null, null, 0, 0, null, false, 2000, 0);
    assertThrows(
        IllegalArgumentException.class, () -> invalid.restore(live.checkpoint(), List.of(wrong)));
    assertEquals(0, invalid.view().sequence());
    invalid.restore(live.checkpoint(), List.of(original));
    assertEquals(result, invalid.lookup(original));
    for (String unused : List.of("\ud800", "\ud801")) {
      Command first =
          new Command("unicode", "alice", Type.INIT, unused, null, 0, 0, null, false, 1, 1);
      Command changed =
          new Command("unicode", "alice", Type.INIT, "?", null, 0, 0, null, false, 1, 1);
      assertEquals(first.hash(), changed.hash());
      Book unicode = new Book();
      unicode.apply(1, first);
      assertEquals("CONFLICT", unicode.lookup(changed).code());
    }
  }
}
