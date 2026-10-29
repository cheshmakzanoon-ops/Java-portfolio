package io.github.cheshmakzanoon.portfolio.thepit;

import static io.github.cheshmakzanoon.portfolio.thepit.Model.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;

/**
 * Public market data derived from immutable sequence-tagged state and recorded executions.
 *
 * <p>Nothing here reads {@link Book} internals, changes a persisted representation, or exposes an
 * account or order identity. Every accumulator is total: market data is a derivation and must never
 * be able to fail the single writer that feeds it.
 */
public final class MarketData {
  /** Number of most recent fills the public tape retains. */
  public static final int TAPE_CAPACITY = 1024;

  private MarketData() {}

  /** Aggregate resting quantity at one price. */
  public record Level(long priceTicks, long lots) {}

  /**
   * Cumulative execution statistics for the current run. Counters that can exceed a signed long
   * (traded lots and notional recycle the same finite supply) are exact decimal strings.
   */
  public record Stats(
      long trades,
      String volumeLots,
      String notionalTicks,
      String vwapTicks,
      Long openTicks,
      Long highTicks,
      Long lowTicks,
      Long lastTicks,
      Long lastLots) {}

  /** Top of book, aggregate depth and run statistics, all as of one applied sequence. */
  public record Market(
      long lastAppliedSequence,
      Level bid,
      Level ask,
      Long spreadTicks,
      String midTicks,
      int bidLevels,
      int askLevels,
      String bidLots,
      String askLots,
      List<Level> bids,
      List<Level> asks,
      Stats stats) {
    public Market {
      bids = List.copyOf(bids);
      asks = List.copyOf(asks);
    }
  }

  /** One anonymous fill; the taker's side is the aggressor. */
  public record Trade(long sequence, long priceTicks, long lots, Side takerSide) {}

  /** Single-writer accumulator. Its immutable snapshots may be published to other threads. */
  public static final class Tracker {
    private long trades;
    private BigInteger volume = BigInteger.ZERO, notional = BigInteger.ZERO;
    private Long open, high, low, last, lastLots;

    public void accept(Execution execution) {
      trades++;
      BigInteger lots = BigInteger.valueOf(execution.lots());
      volume = volume.add(lots);
      notional = notional.add(BigInteger.valueOf(execution.priceTicks()).multiply(lots));
      long price = execution.priceTicks();
      if (open == null) open = price;
      if (high == null || price > high) high = price;
      if (low == null || price < low) low = price;
      last = price;
      lastLots = execution.lots();
    }

    public Stats snapshot() {
      String vwap =
          volume.signum() == 0
              ? null
              : new BigDecimal(notional)
                  .divide(new BigDecimal(volume), 4, RoundingMode.HALF_EVEN)
                  .toPlainString();
      return new Stats(
          trades, volume.toString(), notional.toString(), vwap, open, high, low, last, lastLots);
    }
  }

  /** Bounded newest-last ring of anonymous fills; safe for one writer and many readers. */
  public static final class Tape {
    private final Trade[] ring;
    private long count;

    public Tape() {
      this(TAPE_CAPACITY);
    }

    public Tape(int capacity) {
      if (capacity < 1) throw new IllegalArgumentException("tape capacity");
      ring = new Trade[capacity];
    }

    public synchronized void accept(long sequence, Side takerSide, Execution execution) {
      ring[(int) (count % ring.length)] =
          new Trade(sequence, execution.priceTicks(), execution.lots(), takerSide);
      count++;
    }

    /** Newest first, at most {@code limit} fills with sequence not above {@code maximum}. */
    public synchronized List<Trade> recent(int limit, long maximum) {
      List<Trade> out = new ArrayList<>(Math.min(limit, ring.length));
      long oldest = Math.max(0, count - ring.length);
      for (long index = count - 1; index >= oldest && out.size() < limit; index--) {
        Trade trade = ring[(int) (index % ring.length)];
        if (trade.sequence() <= maximum) out.add(trade);
      }
      return Collections.unmodifiableList(out);
    }
  }

  /**
   * Derives the market from a book view. Individual orders and pre-aggregated depth entries are
   * both accepted; quantities at one price are summed. Quote, depth and totals describe the same
   * sequence as the supplied view.
   */
  public static Market market(View view, Stats stats, int depth) {
    if (depth < 1) throw new IllegalArgumentException("depth");
    TreeMap<Long, Long> bids = new TreeMap<>(Collections.reverseOrder()), asks = new TreeMap<>();
    BigInteger bidLots = BigInteger.ZERO, askLots = BigInteger.ZERO;
    for (Order order : view.orders()) {
      if (order.side() == Side.BUY) {
        bids.merge(order.priceTicks(), order.remaining(), Long::sum);
        bidLots = bidLots.add(BigInteger.valueOf(order.remaining()));
      } else {
        asks.merge(order.priceTicks(), order.remaining(), Long::sum);
        askLots = askLots.add(BigInteger.valueOf(order.remaining()));
      }
    }
    Level bid = bids.isEmpty() ? null : level(bids.firstEntry());
    Level ask = asks.isEmpty() ? null : level(asks.firstEntry());
    Long spread = null;
    String mid = null;
    if (bid != null && ask != null) {
      BigInteger low = BigInteger.valueOf(bid.priceTicks()),
          high = BigInteger.valueOf(ask.priceTicks());
      spread = Math.subtractExact(ask.priceTicks(), bid.priceTicks());
      mid =
          new BigDecimal(low.add(high))
              .divide(BigDecimal.TWO, 1, RoundingMode.UNNECESSARY)
              .stripTrailingZeros()
              .toPlainString();
    }
    return new Market(
        view.sequence(),
        bid,
        ask,
        spread,
        mid,
        bids.size(),
        asks.size(),
        bidLots.toString(),
        askLots.toString(),
        top(bids, depth),
        top(asks, depth),
        stats);
  }

  private static Level level(java.util.Map.Entry<Long, Long> entry) {
    return new Level(entry.getKey(), entry.getValue());
  }

  private static List<Level> top(TreeMap<Long, Long> side, int depth) {
    List<Level> out = new ArrayList<>(Math.min(depth, side.size()));
    for (var entry : side.entrySet()) {
      if (out.size() == depth) break;
      out.add(level(entry));
    }
    return out;
  }
}
