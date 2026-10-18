package io.github.cheshmakzanoon.portfolio.matchcore;

import static io.github.cheshmakzanoon.portfolio.matchcore.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BookTest {
  private long seq;
  private final Book b = new Book(3);

  private Result apply(Command c) {
    return b.apply(++seq, c);
  }

  private Command init(String a, long cash, long lots) {
    return new Command("init", a, Type.INIT, null, null, 0, 0, null, false, cash, lots);
  }

  private Command order(String id, String a, Side s, long p, long q, Tif tif, boolean post) {
    return new Command(id, a, Type.NEW, id, s, p, q, tif, post, 0, 0);
  }

  @Test
  void exactRiskAndFokSelfTrade() {
    apply(init("a", 1000, 20));
    apply(init("b", 1000, 20));
    apply(order("ask", "b", Side.SELL, 100, 2, Tif.GTC, false));
    assertEquals("FOK_UNFILLED", apply(order("fok", "a", Side.BUY, 110, 3, Tif.FOK, false)).code());
    assertEquals(0, b.view().executions().size());
    assertEquals(1000, b.view().accounts().get("a").cash());
    assertEquals("POST_ONLY", apply(order("post", "a", Side.BUY, 110, 1, Tif.GTC, true)).code());
    assertEquals("RISK", apply(order("risk", "a", Side.BUY, 110, 10, Tif.GTC, false)).code());
    assertEquals(
        "OVERFLOW",
        apply(order("overflow", "a", Side.BUY, Long.MAX_VALUE, 2, Tif.GTC, false)).code());
    assertEquals("FILLED", apply(order("buy", "a", Side.BUY, 110, 2, Tif.FOK, false)).code());
    assertEquals(800, b.view().accounts().get("a").cash());
    assertEquals(22, b.view().accounts().get("a").lots());
    apply(order("own", "a", Side.SELL, 100, 1, Tif.GTC, false));
    assertEquals(
        "FOK_UNFILLED", apply(order("selfFok", "a", Side.BUY, 100, 1, Tif.FOK, false)).code());
    assertEquals(
        "SELF_TRADE_CANCELED", apply(order("self", "a", Side.BUY, 100, 1, Tif.GTC, false)).code());
    assertEquals(1, b.view().orders().size());
    assertEquals(0, b.view().accounts().get("a").reservedCash());
  }

  @Test
  void reductionsKeepFifoReleaseHoldsAndExposeExactAggregatedDepth() {
    Book book = new Book(10);
    long sequence = 0;
    for (String account : java.util.List.of("a", "b", "c"))
      book.apply(++sequence, init(account, 1000, 10));
    book.apply(++sequence, order("first", "a", Side.BUY, 100, 4, Tif.GTC, false));
    book.apply(++sequence, order("second", "b", Side.BUY, 100, 4, Tif.GTC, false));
    long priority =
        book.view().orders().stream()
            .filter(o -> o.accountId().equals("a"))
            .findFirst()
            .orElseThrow()
            .priority();
    assertEquals(
        "REDUCED",
        book.apply(
                ++sequence,
                new Command(
                    "reduce", "a", Type.REPLACE, "first", Side.BUY, 100, 2, Tif.GTC, false, 0, 0))
            .code());
    assertEquals(
        "REDUCED",
        book.apply(
                ++sequence,
                new Command(
                    "same", "a", Type.REPLACE, "first", Side.BUY, 100, 2, Tif.GTC, false, 0, 0))
            .code());
    var retained =
        book.view().orders().stream()
            .filter(o -> o.accountId().equals("a"))
            .findFirst()
            .orElseThrow();
    assertEquals(priority, retained.priority());
    assertEquals(2, retained.revision());
    assertEquals(200, book.view().accounts().get("a").reservedCash());
    book.apply(++sequence, order("ask", "c", Side.SELL, 101, 1, Tif.GTC, false));
    var depth = book.publicView();
    assertEquals(
        6,
        depth.orders().stream()
            .filter(o -> o.side() == Side.BUY)
            .findFirst()
            .orElseThrow()
            .remaining());
    assertEquals(
        1,
        depth.orders().stream()
            .filter(o -> o.side() == Side.SELL)
            .findFirst()
            .orElseThrow()
            .remaining());
    assertEquals(3, depth.accounts().size());
    var fill = book.apply(++sequence, order("sell", "c", Side.SELL, 100, 3, Tif.IOC, false));
    assertEquals(
        java.util.List.of("a", "b"),
        fill.executions().stream().map(Execution::makerAccount).toList());
    assertEquals(
        java.util.List.of(2L, 1L), fill.executions().stream().map(Execution::lots).toList());
    assertEquals(
        "CANCELED",
        book.apply(
                ++sequence,
                new Command(
                    "cancel-zero",
                    "b",
                    Type.REPLACE,
                    "second",
                    Side.BUY,
                    100,
                    0,
                    Tif.GTC,
                    false,
                    0,
                    0))
            .code());
    assertEquals(0, book.view().accounts().get("b").reservedCash());
    assertTrue(book.view().orders().stream().noneMatch(o -> o.accountId().equals("b")));
    assertEquals(
        "TERMINAL",
        book.apply(
                ++sequence,
                new Command(
                    "cancel-again", "b", Type.CANCEL, "second", null, 0, 0, null, false, 0, 0))
            .code());
    var stable = book.view();
    long next = sequence + 1;
    assertThrows(
        IllegalArgumentException.class,
        () -> book.apply(next, order("invalid", "a", Side.BUY, 0, 1, Tif.GTC, false)));
    assertEquals(stable, book.view());
  }

  @Test
  void numericAndIdentityValidation() {
    assertEquals(10100, Model.decimalPrice("101.00"));
    assertThrows(ArithmeticException.class, () -> Model.decimalPrice("1.001"));
    assertThrows(
        IllegalArgumentException.class,
        () -> order("bad", "a", Side.BUY, 0, 1, Tif.GTC, false).validate());
    assertThrows(IllegalArgumentException.class, () -> new Book(0));
    apply(init("a", Long.MAX_VALUE, 0));
    assertEquals("OVERFLOW", apply(init("b", 1, 0)).code());
    assertFalse(b.view().accounts().containsKey("b"));
    Command c = order("x", "a", Side.BUY, 1, 1, Tif.GTC, false);
    Result r = apply(c);
    assertEquals(r, b.lookup(c));
    assertEquals("CONFLICT", b.lookup(order("x", "a", Side.BUY, 2, 1, Tif.GTC, false)).code());
    assertThrows(IllegalArgumentException.class, () -> b.apply(500, c));
    assertThrows(IllegalStateException.class, () -> b.apply(seq + 1, c));
  }
}
