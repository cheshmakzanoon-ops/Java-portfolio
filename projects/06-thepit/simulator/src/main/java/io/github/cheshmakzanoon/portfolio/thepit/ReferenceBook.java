package io.github.cheshmakzanoon.portfolio.thepit;

import static io.github.cheshmakzanoon.portfolio.thepit.Model.*;

import java.math.BigInteger;
import java.util.*;

/** Intentionally slow oracle: a flat immutable-order list, recomputed holds, BigInteger supply. */
public final class ReferenceBook {
  private final List<Order> open = new ArrayList<>();
  private final Map<String, long[]> balances = new TreeMap<>();
  private final Set<String> identities = new HashSet<>();
  private final Map<String, Result> outcomes = new TreeMap<>();
  private final Map<String, Command> originalCommands = new HashMap<>();
  private final List<Execution> executions = new ArrayList<>();
  private long sequence;
  private final int cap;

  public ReferenceBook() {
    this(100_000);
  }

  public ReferenceBook(int cap) {
    this.cap = cap;
  }

  public Result apply(Command c) {
    c.validate();
    Result prior = outcomes.get(c.key());
    if (prior != null)
      return originalCommands.get(c.key()).equals(c)
          ? prior
          : new Result(prior.sequence(), "CONFLICT", c.hash(), List.of());
    sequence++;
    List<Execution> newTrades = new ArrayList<>();
    String code;
    try {
      code = execute(c, newTrades);
    } catch (ArithmeticException e) {
      code = "OVERFLOW";
    }
    Result r = new Result(sequence, code, c.hash(), newTrades);
    outcomes.put(c.key(), r);
    originalCommands.put(c.key(), c);
    return r;
  }

  private String execute(Command c, List<Execution> trades) {
    if (c.type() == Type.INIT) {
      if (balances.containsKey(c.accountId())) return "ACCOUNT_EXISTS";
      BigInteger cash = BigInteger.valueOf(c.initialCash()),
          lots = BigInteger.valueOf(c.initialLots());
      for (long[] b : balances.values()) {
        cash = cash.add(BigInteger.valueOf(b[0]));
        lots = lots.add(BigInteger.valueOf(b[1]));
      }
      cash.longValueExact();
      lots.longValueExact();
      balances.put(c.accountId(), new long[] {c.initialCash(), c.initialLots()});
      return "INITIALIZED";
    }
    if (!balances.containsKey(c.accountId())) return "UNKNOWN_ACCOUNT";
    Order old =
        open.stream()
            .filter(o -> o.accountId().equals(c.accountId()) && o.orderId().equals(c.orderId()))
            .findFirst()
            .orElse(null);
    if (c.type() == Type.CANCEL || (c.type() == Type.REPLACE && c.quantityLots() == 0)) {
      if (old == null) return identities.contains(c.orderKey()) ? "TERMINAL" : "NOT_FOUND";
      open.remove(old);
      return "CANCELED";
    }
    if (c.type() == Type.REPLACE && old == null)
      return identities.contains(c.orderKey()) ? "TERMINAL" : "NOT_FOUND";
    if (c.type() == Type.NEW && identities.contains(c.orderKey())) return "ORDER_ID_USED";
    if (old != null && old.side() != c.side()) return "SIDE_CHANGE";
    if (c.type() == Type.REPLACE && c.timeInForce() != Tif.GTC) return "TIF_CHANGE";
    long notional =
        BigInteger.valueOf(c.priceTicks())
            .multiply(BigInteger.valueOf(c.quantityLots()))
            .longValueExact();
    long required = c.side() == Side.BUY ? notional : c.quantityLots();
    Balance b = viewBalances().get(c.accountId());
    long available =
        c.side() == Side.BUY ? b.cash() - b.reservedCash() : b.lots() - b.reservedLots();
    if (old != null)
      available =
          Math.addExact(
              available,
              old.side() == Side.BUY
                  ? Math.multiplyExact(old.priceTicks(), old.remaining())
                  : old.remaining());
    if (required > available) return "RISK";
    List<Order> candidates =
        open.stream()
            .filter(o -> o.side() != c.side())
            .sorted(
                Comparator.<Order>comparingLong(
                        o -> c.side() == Side.BUY ? o.priceTicks() : -o.priceTicks())
                    .thenComparingLong(Order::priority))
            .toList();
    if (c.postOnly() && !candidates.isEmpty() && crosses(c, candidates.getFirst()))
      return "POST_ONLY";
    if (c.timeInForce() == Tif.FOK) {
      BigInteger fill = BigInteger.ZERO;
      for (Order o : candidates) {
        if (!crosses(c, o) || o.accountId().equals(c.accountId())) break;
        fill = fill.add(BigInteger.valueOf(o.remaining()));
        if (fill.compareTo(BigInteger.valueOf(c.quantityLots())) >= 0) break;
      }
      if (fill.compareTo(BigInteger.valueOf(c.quantityLots())) < 0) return "FOK_UNFILLED";
    }
    if (old == null
        && open.size() >= cap
        && c.timeInForce() == Tif.GTC
        && wouldExceedCapacity(c, candidates)) return "OPEN_ORDER_CAP";
    if (old != null && old.priceTicks() == c.priceTicks() && c.quantityLots() <= old.remaining()) {
      open.set(
          open.indexOf(old),
          new Order(
              old.accountId(),
              old.orderId(),
              old.side(),
              old.priceTicks(),
              c.quantityLots(),
              old.priority(),
              old.revision() + 1));
      return "REDUCED";
    }
    if (old != null) open.remove(old);
    identities.add(c.orderKey());
    long remaining = c.quantityLots();
    boolean self = false;
    for (Order maker : candidates) {
      if (remaining == 0 || !crosses(c, maker)) break;
      if (maker.accountId().equals(c.accountId())) {
        self = true;
        break;
      }
      long amount = Math.min(remaining, maker.remaining()),
          cost =
              BigInteger.valueOf(maker.priceTicks())
                  .multiply(BigInteger.valueOf(amount))
                  .longValueExact();
      long[] buyer = balances.get(c.side() == Side.BUY ? c.accountId() : maker.accountId()),
          seller = balances.get(c.side() == Side.SELL ? c.accountId() : maker.accountId());
      buyer[0] -= cost;
      buyer[1] += amount;
      seller[0] += cost;
      seller[1] -= amount;
      open.remove(maker);
      if (amount < maker.remaining())
        open.add(
            new Order(
                maker.accountId(),
                maker.orderId(),
                maker.side(),
                maker.priceTicks(),
                maker.remaining() - amount,
                maker.priority(),
                maker.revision()));
      remaining -= amount;
      Execution e =
          new Execution(
              sequence,
              maker.accountId(),
              maker.orderId(),
              c.accountId(),
              c.orderId(),
              maker.priceTicks(),
              amount);
      trades.add(e);
      executions.add(e);
    }
    if (remaining > 0 && !self && c.timeInForce() == Tif.GTC)
      open.add(
          new Order(
              c.accountId(),
              c.orderId(),
              c.side(),
              c.priceTicks(),
              remaining,
              sequence,
              old == null ? 0 : old.revision() + 1));
    return self
        ? "SELF_TRADE_CANCELED"
        : remaining == 0 ? "FILLED" : c.timeInForce() == Tif.GTC ? "RESTING" : "IOC_CANCELED";
  }

  // Independent flat-list oracle: count fully matched resting orders before adding a
  // possible new remainder. Do not rely on the production engine's indexed price levels.
  private boolean wouldExceedCapacity(Command c, List<Order> candidates) {
    long needed = c.quantityLots();
    int removed = 0;
    for (Order maker : candidates) {
      if (!crosses(c, maker)) break;
      if (maker.accountId().equals(c.accountId())) return false;
      if (maker.remaining() <= needed) removed++;
      needed -= Math.min(needed, maker.remaining());
      if (needed == 0) return false;
    }
    return open.size() - removed >= cap;
  }

  private boolean crosses(Command c, Order o) {
    return c.side() == Side.BUY
        ? c.priceTicks() >= o.priceTicks()
        : c.priceTicks() <= o.priceTicks();
  }

  private Map<String, Balance> viewBalances() {
    Map<String, Balance> b = new TreeMap<>();
    balances.forEach(
        (k, v) -> {
          long cash = 0, lots = 0;
          for (Order o : open)
            if (o.accountId().equals(k)) {
              if (o.side() == Side.BUY)
                cash = Math.addExact(cash, Math.multiplyExact(o.priceTicks(), o.remaining()));
              else lots = Math.addExact(lots, o.remaining());
            }
          b.put(k, new Balance(v[0], v[1], cash, lots));
        });
    return b;
  }

  public View view() {
    return new View(
        sequence,
        viewBalances(),
        open.stream()
            .sorted(Comparator.comparing(Order::accountId).thenComparing(Order::orderId))
            .toList(),
        executions,
        outcomes);
  }
}
