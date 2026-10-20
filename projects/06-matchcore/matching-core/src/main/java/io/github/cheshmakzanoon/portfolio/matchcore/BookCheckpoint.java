package io.github.cheshmakzanoon.portfolio.matchcore;

import static io.github.cheshmakzanoon.portfolio.matchcore.Model.*;

import java.util.*;

/** Structural state invariants for optional checkpoints; journal ancestry is checked separately. */
final class BookCheckpoint {
  private static final Set<String> CODES =
      Set.of(
          "INITIALIZED",
          "ACCOUNT_EXISTS",
          "UNKNOWN_ACCOUNT",
          "TERMINAL",
          "NOT_FOUND",
          "CANCELED",
          "SIDE_CHANGE",
          "TIF_CHANGE",
          "ORDER_ID_USED",
          "RISK",
          "POST_ONLY",
          "FOK_UNFILLED",
          "OPEN_ORDER_CAP",
          "REDUCED",
          "SELF_TRADE_CANCELED",
          "FILLED",
          "RESTING",
          "IOC_CANCELED",
          "OVERFLOW");

  private BookCheckpoint() {}

  static void validate(Checkpoint checkpoint, int maximumOpen) {
    View view = Objects.requireNonNull(checkpoint.view());
    require(view.sequence() >= 0 && view.sequence() <= 1_100_000, "sequence");
    require(view.results().size() == view.sequence(), "result count");
    require(view.orders().size() <= maximumOpen, "open order cap");
    require(checkpoint.usedOrderIds().size() <= view.sequence(), "terminal identity count");
    long cash = 0, lots = 0;
    for (var entry : view.accounts().entrySet()) {
      Balance balance = entry.getValue();
      require(identifier(entry.getKey()), "account identity");
      require(
          balance.cash() >= 0
              && balance.lots() >= 0
              && balance.reservedCash() >= 0
              && balance.reservedCash() <= balance.cash()
              && balance.reservedLots() >= 0
              && balance.reservedLots() <= balance.lots(),
          "balance");
      cash = Math.addExact(cash, balance.cash());
      lots = Math.addExact(lots, balance.lots());
    }
    Set<Long> sequences = new HashSet<>();
    Set<String> initialized = new HashSet<>();
    for (var entry : view.results().entrySet()) {
      String account = account(entry.getKey());
      Result result = entry.getValue();
      require(
          result.sequence() > 0
              && result.sequence() <= view.sequence()
              && sequences.add(result.sequence()),
          "result sequence");
      require(
          result.commandHash().matches("[0-9a-f]{64}") && CODES.contains(result.code()),
          "result identity");
      if (result.code().equals("INITIALIZED"))
        require(initialized.add(account), "duplicate initialization");
      for (Execution execution : result.executions())
        require(execution.sequence() == result.sequence(), "execution result sequence");
    }
    require(initialized.equals(view.accounts().keySet()), "initialized accounts");
    for (String key : checkpoint.usedOrderIds())
      require(view.accounts().containsKey(account(key)), "terminal account");
    Map<String, long[]> reservations = new HashMap<>();
    Set<String> orders = new HashSet<>();
    Set<Long> priorities = new HashSet<>();
    Long bid = null, ask = null;
    for (Order order : view.orders()) {
      String key = order.accountId() + ":" + order.orderId();
      require(
          identifier(order.accountId())
              && identifier(order.orderId())
              && view.accounts().containsKey(order.accountId())
              && orders.add(key)
              && checkpoint.usedOrderIds().contains(key),
          "open order identity");
      require(
          order.side() != null
              && order.priceTicks() > 0
              && order.remaining() > 0
              && order.priority() > 0
              && order.priority() <= view.sequence()
              && priorities.add(order.priority())
              && order.revision() >= 0
              && order.revision() < view.sequence(),
          "open order values");
      long notional = Math.multiplyExact(order.priceTicks(), order.remaining());
      long[] held = reservations.computeIfAbsent(order.accountId(), ignored -> new long[2]);
      int index = order.side() == Side.BUY ? 0 : 1;
      held[index] = Math.addExact(held[index], index == 0 ? notional : order.remaining());
      if (order.side() == Side.BUY)
        bid = bid == null ? order.priceTicks() : Math.max(bid, order.priceTicks());
      else ask = ask == null ? order.priceTicks() : Math.min(ask, order.priceTicks());
    }
    require(bid == null || ask == null || bid < ask, "crossed book");
    for (var entry : view.accounts().entrySet()) {
      long[] held = reservations.getOrDefault(entry.getKey(), new long[2]);
      require(
          held[0] == entry.getValue().reservedCash() && held[1] == entry.getValue().reservedLots(),
          "reservation totals");
    }
    List<Execution> executions =
        view.results().values().stream()
            .sorted(Comparator.comparingLong(Result::sequence))
            .flatMap(result -> result.executions().stream())
            .toList();
    require(executions.equals(view.executions()), "execution history");
    for (Execution execution : executions) {
      require(
          identifier(execution.makerAccount())
              && identifier(execution.makerOrder())
              && identifier(execution.takerAccount())
              && identifier(execution.takerOrder())
              && !execution.makerAccount().equals(execution.takerAccount())
              && view.accounts().containsKey(execution.makerAccount())
              && view.accounts().containsKey(execution.takerAccount())
              && checkpoint
                  .usedOrderIds()
                  .contains(execution.makerAccount() + ":" + execution.makerOrder())
              && checkpoint
                  .usedOrderIds()
                  .contains(execution.takerAccount() + ":" + execution.takerOrder())
              && execution.priceTicks() > 0
              && execution.lots() > 0,
          "execution values");
      Math.multiplyExact(execution.priceTicks(), execution.lots());
    }
  }

  private static String account(String key) {
    String[] parts = key.split(":", -1);
    require(parts.length == 2 && identifier(parts[0]) && identifier(parts[1]), "compound identity");
    return parts[0];
  }

  private static void require(boolean condition, String detail) {
    if (!condition) throw new IllegalArgumentException("invalid checkpoint: " + detail);
  }
}
