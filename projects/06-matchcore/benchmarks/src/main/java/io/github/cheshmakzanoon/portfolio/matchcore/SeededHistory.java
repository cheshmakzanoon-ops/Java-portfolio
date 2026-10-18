package io.github.cheshmakzanoon.portfolio.matchcore;

import static io.github.cheshmakzanoon.portfolio.matchcore.Model.*;

import java.util.*;

/** Version 2 workload: shuffled exact block mix and actual currently live account/order targets. */
final class SeededHistory {
  private record Live(String account, String order, Side side, long price, long quantity) {
    String key() {
      return account + ":" + order;
    }
  }

  private final Random random;
  private final Type[] block = new Type[100];
  private final ArrayList<Live> live = new ArrayList<>();
  private final Map<String, Integer> positions = new HashMap<>();
  private int peakOpen;
  private long activeTargets, emptyTargets;

  SeededHistory(long seed) {
    random = new Random(seed);
  }

  Command next(int number) {
    if (number % 100 == 0) {
      for (int i = 0; i < 100; i++)
        block[i] = i < 60 ? Type.NEW : i < 85 ? Type.CANCEL : Type.REPLACE;
      for (int i = 99; i > 0; i--) {
        int j = random.nextInt(i + 1);
        Type t = block[i];
        block[i] = block[j];
        block[j] = t;
      }
    }
    Type type = block[number % 100];
    String account, order;
    Side side;
    if (type != Type.NEW && !live.isEmpty()) {
      Live target = live.get(random.nextInt(live.size()));
      account = target.account();
      order = target.order();
      side = target.side();
      activeTargets++;
    } else {
      account = "a" + random.nextInt(64);
      order = (type == Type.NEW ? "o" : "empty-") + number;
      side = random.nextBoolean() ? Side.BUY : Side.SELL;
      if (type != Type.NEW) emptyTargets++;
    }
    return new Command(
        "c" + number,
        account,
        type,
        order,
        side,
        9900 + random.nextInt(201),
        1 + random.nextInt(10),
        Tif.GTC,
        false,
        0,
        0);
  }

  void observed(Command command, Result result) {
    long filled = 0;
    for (Execution execution : result.executions()) {
      filled = Math.addExact(filled, execution.lots());
      String key = execution.makerAccount() + ":" + execution.makerOrder();
      Integer at = positions.get(key);
      if (at == null) throw new AssertionError("execution maker absent from driver tracker");
      Live maker = live.get(at);
      long remaining = Math.subtractExact(maker.quantity(), execution.lots());
      if (remaining < 0) throw new AssertionError("driver maker remaining negative");
      if (remaining == 0) remove(key);
      else put(new Live(maker.account(), maker.order(), maker.side(), maker.price(), remaining));
    }
    switch (result.code()) {
      case "RESTING" ->
          put(
              new Live(
                  command.accountId(),
                  command.orderId(),
                  command.side(),
                  command.priceTicks(),
                  Math.subtractExact(command.quantityLots(), filled)));
      case "REDUCED" ->
          put(
              new Live(
                  command.accountId(),
                  command.orderId(),
                  command.side(),
                  command.priceTicks(),
                  command.quantityLots()));
      case "FILLED", "CANCELED", "SELF_TRADE_CANCELED", "IOC_CANCELED" ->
          remove(command.orderKey());
      default -> {
        /* A rejected new/replacement/cancellation leaves the tracker unchanged. */
      }
    }
    peakOpen = Math.max(peakOpen, live.size());
    if (live.size() > 100_000) throw new AssertionError("driver open cap exceeded");
  }

  private void put(Live order) {
    if (order.quantity() <= 0) throw new AssertionError("driver nonpositive live quantity");
    Integer at = positions.get(order.key());
    if (at != null) live.set(at, order);
    else {
      positions.put(order.key(), live.size());
      live.add(order);
    }
  }

  private void remove(String key) {
    Integer at = positions.remove(key);
    if (at == null) return;
    Live last = live.removeLast();
    if (at < live.size()) {
      live.set(at, last);
      positions.put(last.key(), at);
    }
  }

  void verify(View state) {
    if (state.orders().size() != live.size())
      throw new AssertionError("driver/book open count differs");
    for (Order order : state.orders()) {
      Integer at = positions.get(order.accountId() + ":" + order.orderId());
      if (at == null) throw new AssertionError("book order missing from driver tracker");
      Live tracked = live.get(at);
      if (tracked.side() != order.side()
          || tracked.price() != order.priceTicks()
          || tracked.quantity() != order.remaining())
        throw new AssertionError("driver/book order differs");
    }
  }

  int peakOpen() {
    return peakOpen;
  }

  long activeTargets() {
    return activeTargets;
  }

  long emptyTargets() {
    return emptyTargets;
  }
}
