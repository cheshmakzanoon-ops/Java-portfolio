package io.github.cheshmakzanoon.portfolio.thepit;

import static io.github.cheshmakzanoon.portfolio.thepit.MarketData.*;
import static io.github.cheshmakzanoon.portfolio.thepit.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;

class MarketDataTest {
  private static Order resting(Side side, long price, long lots) {
    return new Order("a", "o" + price + side, side, price, lots, 1, 0);
  }

  private static View book(Order... orders) {
    return new View(7, Map.of(), List.of(orders), List.of(), Map.of());
  }

  private static Execution fill(long price, long lots) {
    return new Execution(1, "maker", "m", "taker", "t", price, lots);
  }

  @Test
  void emptyBookHasNoQuoteSpreadOrMid() {
    Market market = market(book(), new Tracker().snapshot(), 5);
    assertEquals(7, market.lastAppliedSequence());
    assertNull(market.bid());
    assertNull(market.ask());
    assertNull(market.spreadTicks());
    assertNull(market.midTicks());
    assertEquals(0, market.bidLevels());
    assertEquals("0", market.bidLots());
    assertEquals("0", market.askLots());
    assertTrue(market.bids().isEmpty() && market.asks().isEmpty());
  }

  @Test
  void oneSidedBookQuotesOnlyThatSide() {
    Market bidOnly = market(book(resting(Side.BUY, 100, 3)), new Tracker().snapshot(), 5);
    assertEquals(new Level(100, 3), bidOnly.bid());
    assertNull(bidOnly.ask());
    assertNull(bidOnly.spreadTicks());
    assertNull(bidOnly.midTicks());
    Market askOnly = market(book(resting(Side.SELL, 105, 2)), new Tracker().snapshot(), 5);
    assertNull(askOnly.bid());
    assertEquals(new Level(105, 2), askOnly.ask());
  }

  @Test
  void spreadMidAndAggregatedDepthAreExact() {
    View view =
        book(
            resting(Side.BUY, 100, 3),
            new Order("b", "other", Side.BUY, 100, 4, 2, 0),
            resting(Side.BUY, 99, 10),
            resting(Side.BUY, 98, 1),
            resting(Side.SELL, 103, 5),
            resting(Side.SELL, 102, 2),
            new Order("c", "x", Side.SELL, 102, 1, 3, 0));
    Market market = market(view, new Tracker().snapshot(), 2);
    assertEquals(new Level(100, 7), market.bid());
    assertEquals(new Level(102, 3), market.ask());
    assertEquals(2L, market.spreadTicks());
    assertEquals("101", market.midTicks());
    assertEquals(List.of(new Level(100, 7), new Level(99, 10)), market.bids());
    assertEquals(List.of(new Level(102, 3), new Level(103, 5)), market.asks());
    assertEquals(3, market.bidLevels());
    assertEquals(2, market.askLevels());
    assertEquals("18", market.bidLots());
    assertEquals("8", market.askLots());
    assertEquals(
        "100.5",
        market(
                book(resting(Side.BUY, 100, 1), resting(Side.SELL, 101, 1)),
                new Tracker().snapshot(),
                1)
            .midTicks());
    assertThrows(IllegalArgumentException.class, () -> market(view, new Tracker().snapshot(), 0));
    assertThrows(UnsupportedOperationException.class, () -> market.bids().add(new Level(1, 1)));
  }

  @Test
  void statisticsTrackOpenHighLowLastVolumeAndExactVwap() {
    Tracker tracker = new Tracker();
    assertNull(tracker.snapshot().vwapTicks());
    assertNull(tracker.snapshot().lastTicks());
    tracker.accept(fill(101, 3));
    tracker.accept(fill(102, 1));
    tracker.accept(fill(99, 2));
    Stats stats = tracker.snapshot();
    assertEquals(3, stats.trades());
    assertEquals("6", stats.volumeLots());
    assertEquals("603", stats.notionalTicks());
    assertEquals("100.5000", stats.vwapTicks());
    assertEquals(101L, stats.openTicks());
    assertEquals(102L, stats.highTicks());
    assertEquals(99L, stats.lowTicks());
    assertEquals(99L, stats.lastTicks());
    assertEquals(2L, stats.lastLots());
  }

  @Test
  void vwapRoundsHalfEvenToFourPlaces() {
    Tracker tracker = new Tracker();
    tracker.accept(fill(100, 1));
    tracker.accept(fill(101, 2));
    // 302 / 3 = 100.666666...
    assertEquals("100.6667", tracker.snapshot().vwapTicks());
  }

  @Test
  void accumulatorsAreTotalAtTheLongBoundary() {
    Tracker tracker = new Tracker();
    for (int i = 0; i < 3; i++) tracker.accept(fill(Long.MAX_VALUE, Long.MAX_VALUE));
    Stats stats = tracker.snapshot();
    BigInteger lots = BigInteger.valueOf(Long.MAX_VALUE);
    assertEquals(lots.multiply(BigInteger.valueOf(3)).toString(), stats.volumeLots());
    assertEquals(
        lots.multiply(lots).multiply(BigInteger.valueOf(3)).toString(), stats.notionalTicks());
    assertEquals(Long.MAX_VALUE + ".0000", stats.vwapTicks());
  }

  @Test
  void tapeIsBoundedNewestFirstAndRespectsTheSequenceCutoff() {
    Tape tape = new Tape(3);
    for (long sequence = 1; sequence <= 5; sequence++)
      tape.accept(
          sequence, sequence % 2 == 0 ? Side.BUY : Side.SELL, fill(100 + sequence, sequence));
    assertEquals(
        List.of(
            new Trade(5, 105, 5, Side.SELL),
            new Trade(4, 104, 4, Side.BUY),
            new Trade(3, 103, 3, Side.SELL)),
        tape.recent(10, 5));
    assertEquals(
        List.of(new Trade(4, 104, 4, Side.BUY), new Trade(3, 103, 3, Side.SELL)),
        tape.recent(10, 4));
    assertEquals(List.of(new Trade(5, 105, 5, Side.SELL)), tape.recent(1, 99));
    assertTrue(tape.recent(10, 2).isEmpty());
    assertTrue(new Tape().recent(10, 99).isEmpty());
    assertThrows(IllegalArgumentException.class, () -> new Tape(0));
    assertThrows(UnsupportedOperationException.class, () -> tape.recent(1, 5).clear());
  }

  @Test
  void aggregatedAndIndividualViewsProduceTheSameMarketAndAgreeWithRecomputation() {
    long totalFills = 0, twoSidedSteps = 0;
    for (int seed = 0; seed < 60; seed++) {
      Random r = new Random(seed);
      Book book = new Book(30);
      Tracker tracker = new Tracker();
      long sequence = 0;
      for (int a = 0; a < 5; a++)
        book.apply(
            ++sequence,
            new Command("init", "a" + a, Type.INIT, null, null, 0, 0, null, false, 50_000, 500));
      for (int i = 0; i < 250; i++) {
        Type type = r.nextInt(10) < 6 ? Type.NEW : r.nextBoolean() ? Type.CANCEL : Type.REPLACE;
        Tif tif = Tif.values()[r.nextInt(3)];
        Command command =
            new Command(
                "c" + i,
                "a" + r.nextInt(5),
                type,
                "o" + r.nextInt(40),
                r.nextBoolean() ? Side.BUY : Side.SELL,
                95 + r.nextInt(11),
                type == Type.NEW ? 1 + r.nextInt(20) : r.nextInt(20),
                type == Type.REPLACE ? Tif.GTC : tif,
                type != Type.REPLACE && tif == Tif.GTC && r.nextInt(6) == 0,
                0,
                0);
        Result result = book.apply(++sequence, command);
        result.executions().forEach(tracker::accept);
        totalFills += result.executions().size();

        View full = book.view();
        Market fromFull = market(full, tracker.snapshot(), 100);
        Market fromPublic = market(book.publicView(), tracker.snapshot(), 100);
        assertEquals(fromFull, fromPublic, "seed=" + seed + " step=" + i);

        OptionalLong bestBid =
            full.orders().stream()
                .filter(o -> o.side() == Side.BUY)
                .mapToLong(Order::priceTicks)
                .max();
        OptionalLong bestAsk =
            full.orders().stream()
                .filter(o -> o.side() == Side.SELL)
                .mapToLong(Order::priceTicks)
                .min();
        assertEquals(
            bestBid.isPresent() ? bestBid.getAsLong() : null,
            fromFull.bid() == null ? null : fromFull.bid().priceTicks());
        assertEquals(
            bestAsk.isPresent() ? bestAsk.getAsLong() : null,
            fromFull.ask() == null ? null : fromFull.ask().priceTicks());
        if (fromFull.bid() != null && fromFull.ask() != null) {
          assertTrue(fromFull.spreadTicks() > 0, "a resting book is never crossed");
          twoSidedSteps++;
        }
        long bidSum =
            full.orders().stream()
                .filter(o -> o.side() == Side.BUY)
                .mapToLong(Order::remaining)
                .sum();
        assertEquals(Long.toString(bidSum), fromFull.bidLots());

        Stats stats = fromFull.stats();
        assertEquals(full.executions().size(), stats.trades());
        assertEquals(
            Long.toString(full.executions().stream().mapToLong(Execution::lots).sum()),
            stats.volumeLots());
        if (!full.executions().isEmpty()) {
          Execution last = full.executions().getLast();
          assertEquals(last.priceTicks(), stats.lastTicks());
          assertEquals(full.executions().getFirst().priceTicks(), stats.openTicks());
          assertEquals(
              full.executions().stream().mapToLong(Execution::priceTicks).max().getAsLong(),
              stats.highTicks());
          assertEquals(
              full.executions().stream().mapToLong(Execution::priceTicks).min().getAsLong(),
              stats.lowTicks());
        }
      }
    }
    assertTrue(totalFills > 500, "histories must trade: " + totalFills);
    assertTrue(twoSidedSteps > 1000, "histories must quote both sides: " + twoSidedSteps);
  }
}
