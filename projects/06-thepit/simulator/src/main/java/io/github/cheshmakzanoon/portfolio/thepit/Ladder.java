package io.github.cheshmakzanoon.portfolio.thepit;

import static io.github.cheshmakzanoon.portfolio.thepit.Model.*;

import java.util.List;
import java.util.Map;

/** Terminal presentation. It reads published values and never feeds back into matching. */
public final class Ladder {
  private static final Map<String, String> EXPLANATIONS =
      Map.ofEntries(
          Map.entry("INITIALIZED", "account opened with its synthetic balances"),
          Map.entry("ACCOUNT_EXISTS", "rejected: the account was already initialized"),
          Map.entry("UNKNOWN_ACCOUNT", "rejected: the account was never initialized"),
          Map.entry("RESTING", "rests in the book behind earlier orders at its price"),
          Map.entry("FILLED", "completely filled"),
          Map.entry("IOC_CANCELED", "unfilled remainder canceled (immediate-or-cancel)"),
          Map.entry("FOK_UNFILLED", "rejected: the whole quantity was not available"),
          Map.entry("POST_ONLY", "rejected: a post-only order may not cross the book"),
          Map.entry("SELF_TRADE_CANCELED", "remainder canceled instead of trading with own order"),
          Map.entry("RISK", "rejected: not enough unreserved cash or lots"),
          Map.entry("OVERFLOW", "rejected: checked arithmetic would overflow"),
          Map.entry("OPEN_ORDER_CAP", "rejected: the book is at its open-order capacity"),
          Map.entry("ORDER_ID_USED", "rejected: the order identity was already used"),
          Map.entry("NOT_FOUND", "rejected: no such order"),
          Map.entry("TERMINAL", "rejected: the order already completed or was canceled"),
          Map.entry("CANCELED", "order removed and its reservation released"),
          Map.entry("REDUCED", "quantity reduced in place; queue priority kept"),
          Map.entry("SIDE_CHANGE", "rejected: a replacement cannot change side"),
          Map.entry("TIF_CHANGE", "rejected: a replacement cannot change time-in-force"),
          Map.entry("CONFLICT", "rejected: that command ID was already used for another payload"));

  private Ladder() {}

  /** Integer ticks of 0.01 quote units as a decimal. */
  public static String price(long ticks) {
    if (ticks < 0) throw new IllegalArgumentException("negative ticks");
    long cents = ticks % 100;
    return ticks / 100 + "." + (cents < 10 ? "0" : "") + cents;
  }

  /** A volume-weighted average given in ticks, shown in quote units; {@code "-"} before a fill. */
  public static String vwap(String ticks) {
    return ticks == null
        ? "-"
        : new java.math.BigDecimal(ticks)
            .movePointLeft(2)
            .setScale(4, java.math.RoundingMode.HALF_EVEN)
            .toPlainString();
  }

  /** A mid given in (possibly fractional) ticks, shown in quote units. */
  public static String mid(String ticks) {
    return new java.math.BigDecimal(ticks).movePointLeft(2).toPlainString();
  }

  public static String explain(String code) {
    return EXPLANATIONS.getOrDefault(code, "outcome " + code);
  }

  public static String describe(Command c) {
    return switch (c.type()) {
      case INIT -> "INIT cash " + price(c.initialCash()) + ", " + c.initialLots() + " lots";
      case NEW ->
          "NEW "
              + c.side()
              + " "
              + c.quantityLots()
              + " @ "
              + price(c.priceTicks())
              + " "
              + c.timeInForce()
              + (c.postOnly() ? " post-only" : "")
              + " ["
              + c.orderId()
              + "]";
      case REPLACE ->
          "REPLACE ["
              + c.orderId()
              + "] -> remaining "
              + c.quantityLots()
              + " @ "
              + price(c.priceTicks());
      case CANCEL -> "CANCEL [" + c.orderId() + "]";
    };
  }

  public static String describe(Execution e) {
    return e.lots()
        + " lots @ "
        + price(e.priceTicks())
        + "  maker "
        + e.makerAccount()
        + "["
        + e.makerOrder()
        + "]  taker "
        + e.takerAccount()
        + "["
        + e.takerOrder()
        + "]";
  }

  /** One line: {@code 101.00 x 5 / 102.00 x 2 spread 1.00}. */
  public static String topOfBook(MarketData.Market market) {
    String bid =
        market.bid() == null
            ? "no bid"
            : price(market.bid().priceTicks()) + " x " + market.bid().lots();
    String ask =
        market.ask() == null
            ? "no ask"
            : price(market.ask().priceTicks()) + " x " + market.ask().lots();
    return bid
        + " / "
        + ask
        + (market.spreadTicks() == null ? "" : "  spread " + price(market.spreadTicks()));
  }

  /** Asks above, bids below, cumulative quantity counted outward from the best price. */
  public static String render(MarketData.Market market) {
    StringBuilder out = new StringBuilder();
    out.append(String.format("  %-5s %12s %10s %12s%n", "", "price", "lots", "cumulative"));
    appendSide(out, "ASK", market.asks(), true);
    String last =
        market.stats().lastTicks() == null ? "" : "  last " + price(market.stats().lastTicks());
    String spread =
        market.spreadTicks() == null
            ? (market.bid() == null && market.ask() == null ? "empty book" : "one-sided book")
            : "spread " + price(market.spreadTicks()) + "  mid " + mid(market.midTicks());
    out.append("  ------ ")
        .append(spread)
        .append(last)
        .append(" ------")
        .append(System.lineSeparator());
    appendSide(out, "BID", market.bids(), false);
    return out.toString();
  }

  private static void appendSide(
      StringBuilder out, String label, List<MarketData.Level> levels, boolean reversed) {
    long[] cumulative = new long[levels.size()];
    long running = 0;
    for (int i = 0; i < levels.size(); i++) {
      running = Math.addExact(running, levels.get(i).lots());
      cumulative[i] = running;
    }
    for (int n = 0; n < levels.size(); n++) {
      int i = reversed ? levels.size() - 1 - n : n;
      MarketData.Level level = levels.get(i);
      out.append(
          String.format(
              "  %-5s %12s %10d %12d%n",
              label, price(level.priceTicks()), level.lots(), cumulative[i]));
    }
    if (levels.isEmpty()) out.append(String.format("  %-5s %12s%n", label, "(empty)"));
  }
}
