package io.github.cheshmakzanoon.portfolio.matchcore;

import static io.github.cheshmakzanoon.portfolio.matchcore.Model.*;

import java.util.*;

/** Thread-confined indexed price-time book. All balances include held funds. */
public final class Book {
  private static final class Account {
    long cash, lots, heldCash, heldLots;
  }

  private static final class Level {
    Node first, last;
    long quantity;
  }

  private static final class Node {
    String account, id;
    Side side;
    long price, qty, priority, revision;
    Node prev, next;
    Level level;

    Order order() {
      return new Order(account, id, side, price, qty, priority, revision);
    }
  }

  private final NavigableMap<Long, Level> bids = new TreeMap<>(Comparator.reverseOrder());
  private final NavigableMap<Long, Level> asks = new TreeMap<>();
  private final Map<String, Node> index = new HashMap<>();
  private final Set<String> used = new HashSet<>();
  private final Map<String, Account> accounts = new TreeMap<>();
  private final Map<String, Result> results = new TreeMap<>();
  private final Map<String, Command> commandAuthority = new HashMap<>();
  private final List<Execution> trades = new ArrayList<>();
  private long seq, totalCash, totalLots;
  private final int maxOpen;

  public Book() {
    this(100_000);
  }

  public Book(int maxOpen) {
    if (maxOpen < 1) throw new IllegalArgumentException();
    this.maxOpen = maxOpen;
  }

  public Result lookup(Command c) {
    Result r = results.get(c.key());
    if (r != null) {
      Command original = commandAuthority.get(c.key());
      if (original == null)
        throw new IllegalStateException("historical retry requires original command authority");
      if (!original.equals(c)) return new Result(r.sequence(), "CONFLICT", c.hash(), List.of());
    }
    return r;
  }

  public Result apply(long sequence, Command c) {
    c.validate();
    if (results.size() >= 1_100_000) throw new IllegalStateException("RUN_CAP");
    if (sequence != seq + 1) throw new IllegalArgumentException("sequence gap");
    if (results.containsKey(c.key())) throw new IllegalStateException("duplicate journal identity");
    seq = sequence;
    var fills = new ArrayList<Execution>();
    String code;
    try {
      code = transition(c, fills);
    } catch (ArithmeticException e) {
      code = "OVERFLOW";
    }
    Result r = new Result(seq, code, c.hash(), fills);
    results.put(c.key(), r);
    commandAuthority.put(c.key(), c);
    return r;
  }

  private String transition(Command c, List<Execution> fills) {
    if (c.type() == Type.INIT) {
      if (accounts.containsKey(c.accountId())) return "ACCOUNT_EXISTS";
      long cash = Math.addExact(totalCash, c.initialCash()),
          lots = Math.addExact(totalLots, c.initialLots());
      Account a = new Account();
      a.cash = c.initialCash();
      a.lots = c.initialLots();
      accounts.put(c.accountId(), a);
      totalCash = cash;
      totalLots = lots;
      return "INITIALIZED";
    }
    Account a = accounts.get(c.accountId());
    if (a == null) return "UNKNOWN_ACCOUNT";
    Node old = index.get(c.orderKey());
    if (c.type() == Type.CANCEL) {
      if (old == null) return used.contains(c.orderKey()) ? "TERMINAL" : "NOT_FOUND";
      remove(old);
      return "CANCELED";
    }
    if (c.type() == Type.REPLACE && old == null)
      return used.contains(c.orderKey()) ? "TERMINAL" : "NOT_FOUND";
    if (c.type() == Type.NEW && used.contains(c.orderKey())) return "ORDER_ID_USED";
    if (c.type() == Type.REPLACE && c.quantityLots() == 0) {
      remove(old);
      return "CANCELED";
    }
    if (c.type() == Type.REPLACE && c.side() != old.side) return "SIDE_CHANGE";
    if (c.type() == Type.REPLACE && c.timeInForce() != Tif.GTC) return "TIF_CHANGE";
    long notional = Math.multiplyExact(c.priceTicks(), c.quantityLots());
    long reserve = c.side() == Side.BUY ? notional : c.quantityLots();
    long freed =
        old == null ? 0 : (old.side == Side.BUY ? Math.multiplyExact(old.price, old.qty) : old.qty);
    long available = c.side() == Side.BUY ? a.cash - a.heldCash : a.lots - a.heldLots;
    if (reserve > Math.addExact(available, freed)) return "RISK";
    if (c.postOnly() && crosses(c, best(opposite(c.side())))) return "POST_ONLY";
    if (c.timeInForce() == Tif.FOK && !fillable(c)) return "FOK_UNFILLED";
    // Rejection must happen before any fill or reservation change. A full book still admits
    // a GTC aggressor that fills completely or stops at a self-trade without resting.
    if (old == null
        && index.size() >= maxOpen
        && c.timeInForce() == Tif.GTC
        && wouldRest(c)) return "OPEN_ORDER_CAP";
    if (old != null && old.price == c.priceTicks() && c.quantityLots() <= old.qty) {
      long revision = Math.incrementExact(old.revision);
      release(old, Math.subtractExact(old.qty, c.quantityLots()));
      old.qty = c.quantityLots();
      old.revision = revision;
      return "REDUCED";
    }
    long revision = old == null ? 0 : Math.incrementExact(old.revision);
    if (old != null) remove(old);
    used.add(c.orderKey());
    long remaining = c.quantityLots();
    boolean self = false;
    while (remaining > 0) {
      Node maker = best(opposite(c.side()));
      if (!crosses(c, maker)) break;
      if (maker.account.equals(c.accountId())) {
        self = true;
        break;
      }
      long qty = Math.min(remaining, maker.qty), cost = Math.multiplyExact(maker.price, qty);
      if (qty <= 0) throw new IllegalStateException("matching must consume positive quantity");
      Account ma = accounts.get(maker.account);
      Account buyer = c.side() == Side.BUY ? a : ma, seller = c.side() == Side.SELL ? a : ma;
      // Aggregate initialized supply <= Long.MAX_VALUE proves these additions cannot overflow.
      long buyerCash = Math.subtractExact(buyer.cash, cost),
          buyerLots = Math.addExact(buyer.lots, qty),
          sellerCash = Math.addExact(seller.cash, cost),
          sellerLots = Math.subtractExact(seller.lots, qty);
      buyer.cash = buyerCash;
      buyer.lots = buyerLots;
      seller.cash = sellerCash;
      seller.lots = sellerLots;
      release(maker, qty);
      maker.qty = Math.subtractExact(maker.qty, qty);
      remaining = Math.subtractExact(remaining, qty);
      Execution trade =
          new Execution(seq, maker.account, maker.id, c.accountId(), c.orderId(), maker.price, qty);
      trades.add(trade);
      fills.add(trade);
      if (maker.qty == 0) unlink(maker);
    }
    if (remaining > 0 && !self && c.timeInForce() == Tif.GTC) {
      Node n = new Node();
      n.account = c.accountId();
      n.id = c.orderId();
      n.side = c.side();
      n.price = c.priceTicks();
      n.qty = remaining;
      n.priority = seq;
      n.revision = revision;
      if (n.side == Side.BUY)
        a.heldCash = Math.addExact(a.heldCash, Math.multiplyExact(n.price, n.qty));
      else a.heldLots = Math.addExact(a.heldLots, n.qty);
      Level l = side(n.side).computeIfAbsent(n.price, k -> new Level());
      n.level = l;
      l.quantity = Math.addExact(l.quantity, n.qty);
      n.prev = l.last;
      if (l.last != null) l.last.next = n;
      else l.first = n;
      l.last = n;
      index.put(c.orderKey(), n);
    }
    return self
        ? "SELF_TRADE_CANCELED"
        : remaining == 0 ? "FILLED" : c.timeInForce() == Tif.GTC ? "RESTING" : "IOC_CANCELED";
  }

  /**
   * Determine whether a new GTC order would need an additional open-order slot. Walk the same
   * price/FIFO order as execution, but do not touch balances, reservations or journal state.
   */
  private boolean wouldRest(Command c) {
    long left = c.quantityLots();
    for (var level : opposite(c.side()).entrySet()) {
      for (Node maker = level.getValue().first; maker != null; maker = maker.next) {
        if (!crosses(c, maker)) return true;
        if (maker.account.equals(c.accountId())) return false;
        left -= Math.min(left, maker.qty);
        if (left == 0) return false;
      }
    }
    return true;
  }

  private boolean fillable(Command c) {
    long left = c.quantityLots();
    for (var e : opposite(c.side()).entrySet()) {
      for (Node n = e.getValue().first; n != null; n = n.next) {
        if (!crosses(c, n) || n.account.equals(c.accountId())) return false;
        left = Math.subtractExact(left, Math.min(left, n.qty));
        if (left == 0) return true;
      }
    }
    return false;
  }

  private NavigableMap<Long, Level> side(Side s) {
    return s == Side.BUY ? bids : asks;
  }

  private NavigableMap<Long, Level> opposite(Side s) {
    return side(s == Side.BUY ? Side.SELL : Side.BUY);
  }

  private Node best(NavigableMap<Long, Level> side) {
    return side.isEmpty() ? null : side.firstEntry().getValue().first;
  }

  private boolean crosses(Command c, Node n) {
    return n != null
        && (c.side() == Side.BUY ? c.priceTicks() >= n.price : c.priceTicks() <= n.price);
  }

  private void release(Node n, long qty) {
    Account a = accounts.get(n.account);
    n.level.quantity = Math.subtractExact(n.level.quantity, qty);
    if (n.side == Side.BUY)
      a.heldCash = Math.subtractExact(a.heldCash, Math.multiplyExact(n.price, qty));
    else a.heldLots = Math.subtractExact(a.heldLots, qty);
  }

  private void remove(Node n) {
    release(n, n.qty);
    unlink(n);
  }

  private void unlink(Node n) {
    Level l = n.level;
    if (n.prev == null) l.first = n.next;
    else n.prev.next = n.next;
    if (n.next == null) l.last = n.prev;
    else n.next.prev = n.prev;
    if (l.first == null) side(n.side).remove(n.price);
    index.remove(n.account + ":" + n.id);
  }

  public Checkpoint checkpoint() {
    return new Checkpoint(view(), used);
  }

  /** Restore business state; historical retry lookup requires the authority overload. */
  public void restore(Checkpoint checkpoint) {
    restore(checkpoint, List.of());
  }

  /** Original commands must be the complete checkpoint prefix, in sequence order. */
  public void restore(Checkpoint checkpoint, List<Command> authority) {
    if (seq != 0 || !accounts.isEmpty())
      throw new IllegalStateException("restore requires empty book");
    Book restored = new Book(maxOpen);
    try {
      BookCheckpoint.validate(checkpoint, maxOpen);
      restored.restoreValidated(checkpoint);
      Objects.requireNonNull(authority, "command authority");
      if (!authority.isEmpty()) {
        if (authority.size() != restored.seq)
          throw new IllegalArgumentException("command authority count");
        for (int index = 0; index < authority.size(); index++) {
          Command original = authority.get(index);
          original.validate();
          Result result = restored.results.get(original.key());
          if (result == null
              || result.sequence() != index + 1L
              || !result.commandHash().equals(original.hash())
              || restored.commandAuthority.putIfAbsent(original.key(), original) != null)
            throw new IllegalArgumentException("command authority identity");
        }
      }
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("invalid checkpoint state", invalid);
    }
    accounts.putAll(restored.accounts);
    bids.putAll(restored.bids);
    asks.putAll(restored.asks);
    index.putAll(restored.index);
    used.addAll(restored.used);
    results.putAll(restored.results);
    commandAuthority.putAll(restored.commandAuthority);
    trades.addAll(restored.trades);
    seq = restored.seq;
    totalCash = restored.totalCash;
    totalLots = restored.totalLots;
  }

  private void restoreValidated(Checkpoint checkpoint) {
    View v = checkpoint.view();
    v.accounts()
        .forEach(
            (name, b) -> {
              Account a = new Account();
              a.cash = b.cash();
              a.lots = b.lots();
              a.heldCash = b.reservedCash();
              a.heldLots = b.reservedLots();
              accounts.put(name, a);
              totalCash = Math.addExact(totalCash, a.cash);
              totalLots = Math.addExact(totalLots, a.lots);
            });
    for (Order o : v.orders().stream().sorted(Comparator.comparingLong(Order::priority)).toList()) {
      Node n = new Node();
      n.account = o.accountId();
      n.id = o.orderId();
      n.side = o.side();
      n.price = o.priceTicks();
      n.qty = o.remaining();
      n.priority = o.priority();
      n.revision = o.revision();
      Level l = side(n.side).computeIfAbsent(n.price, k -> new Level());
      n.level = l;
      l.quantity = Math.addExact(l.quantity, n.qty);
      n.prev = l.last;
      if (l.last != null) l.last.next = n;
      else l.first = n;
      l.last = n;
      index.put(n.account + ":" + n.id, n);
    }
    seq = v.sequence();
    used.addAll(checkpoint.usedOrderIds());
    results.putAll(v.results());
    trades.addAll(v.executions());
  }

  public View publicView() {
    var balances = new TreeMap<String, Balance>();
    accounts.forEach(
        (k, a) -> balances.put(k, new Balance(a.cash, a.lots, a.heldCash, a.heldLots)));
    var depth = new ArrayList<Order>();
    bids.forEach(
        (price, level) -> depth.add(new Order("", "depth", Side.BUY, price, level.quantity, 0, 0)));
    asks.forEach(
        (price, level) ->
            depth.add(new Order("", "depth", Side.SELL, price, level.quantity, 0, 0)));
    return new View(seq, balances, depth, List.of(), Map.of());
  }

  public View view() {
    var balances = new TreeMap<String, Balance>();
    accounts.forEach(
        (k, a) -> balances.put(k, new Balance(a.cash, a.lots, a.heldCash, a.heldLots)));
    var orders =
        index.values().stream()
            .map(Node::order)
            .sorted(Comparator.comparing(Order::accountId).thenComparing(Order::orderId))
            .toList();
    return new View(seq, balances, orders, trades, results);
  }
}
