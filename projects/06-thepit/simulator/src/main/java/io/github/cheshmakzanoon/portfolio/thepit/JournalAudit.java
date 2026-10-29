package io.github.cheshmakzanoon.portfolio.thepit;

import static io.github.cheshmakzanoon.portfolio.thepit.Model.*;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Replays a durable command log through the production {@link Book} and the structurally
 * independent {@link ReferenceBook}, then checks the conservation and reservation invariants of the
 * resulting state. Reading is strictly read-only.
 */
public final class JournalAudit {
  /** Full-state comparisons are bounded so a very large journal stays practical to audit. */
  static final int MAX_STATE_CHECKS = 2000;

  private JournalAudit() {}

  /**
   * @param stateChecks number of complete production-versus-oracle state comparisons performed
   * @param problems empty when the journal passed every check
   */
  public record Report(
      String runId,
      int records,
      long fileBytes,
      long incompleteTailBytes,
      int stateChecks,
      long cashTotal,
      long lotTotal,
      String digest,
      List<String> problems) {
    public Report {
      problems = List.copyOf(problems);
    }

    public boolean passed() {
      return problems.isEmpty();
    }
  }

  public static Report verify(Path journal) throws IOException {
    return verify(JournalReader.inspect(journal));
  }

  public static Report verify(JournalReader.Scan scan) {
    List<String> problems = new ArrayList<>();
    Book book = new Book();
    ReferenceBook reference = new ReferenceBook();
    int records = scan.entries().size();
    int stride = Math.max(1, (records + MAX_STATE_CHECKS - 1) / MAX_STATE_CHECKS);
    int checks = 0;
    BigInteger cash = BigInteger.ZERO, lots = BigInteger.ZERO;
    for (Journal.Entry entry : scan.entries()) {
      String where = "sequence " + entry.sequence() + ": ";
      Command command = entry.command();
      Result actual, expected;
      try {
        actual = book.apply(entry.sequence(), command);
      } catch (RuntimeException failure) {
        problems.add(
            where + "matching engine refused a durable command (" + failure.getMessage() + ")");
        break;
      }
      try {
        expected = reference.apply(command);
      } catch (RuntimeException failure) {
        problems.add(
            where + "independent oracle refused a durable command (" + failure.getMessage() + ")");
        break;
      }
      if (!actual.equals(expected)) {
        problems.add(where + "result differs: engine " + actual + " versus oracle " + expected);
        break;
      }
      if (actual.code().equals("INITIALIZED")) {
        cash = cash.add(BigInteger.valueOf(command.initialCash()));
        lots = lots.add(BigInteger.valueOf(command.initialLots()));
      }
      if (entry.sequence() % stride == 0 || entry.sequence() == records) {
        checks++;
        View view = book.view();
        if (!view.equals(reference.view())) {
          problems.add(where + "complete state differs between engine and oracle");
          break;
        }
        for (String problem : invariantProblems(view, cash, lots)) problems.add(where + problem);
        if (!problems.isEmpty()) break;
      }
    }
    String digest = book.view().digest();
    return new Report(
        scan.runId(),
        records,
        scan.fileBytes(),
        scan.incompleteTailBytes(),
        checks,
        cash.longValue(),
        lots.longValue(),
        digest,
        problems);
  }

  /** Conservation, exact reservations and an uncrossed book; empty when the state is sound. */
  static List<String> invariantProblems(
      View view, BigInteger expectedCash, BigInteger expectedLots) {
    List<String> problems = new ArrayList<>();
    BigInteger cash = BigInteger.ZERO, lots = BigInteger.ZERO;
    Map<String, BigInteger[]> held = new HashMap<>();
    for (Order order : view.orders()) {
      if (order.remaining() <= 0 || order.priceTicks() <= 0)
        problems.add(
            "order " + order.accountId() + ":" + order.orderId() + " has a non-positive field");
      BigInteger[] pair =
          held.computeIfAbsent(
              order.accountId(), k -> new BigInteger[] {BigInteger.ZERO, BigInteger.ZERO});
      if (order.side() == Side.BUY)
        pair[0] =
            pair[0].add(
                BigInteger.valueOf(order.priceTicks())
                    .multiply(BigInteger.valueOf(order.remaining())));
      else pair[1] = pair[1].add(BigInteger.valueOf(order.remaining()));
    }
    for (var account : view.accounts().entrySet()) {
      Balance balance = account.getValue();
      String name = account.getKey();
      cash = cash.add(BigInteger.valueOf(balance.cash()));
      lots = lots.add(BigInteger.valueOf(balance.lots()));
      if (balance.cash() < 0 || balance.lots() < 0)
        problems.add("account " + name + " has a negative balance");
      if (balance.reservedCash() > balance.cash() || balance.reservedLots() > balance.lots())
        problems.add("account " + name + " reserves more than it owns");
      BigInteger[] expected =
          held.getOrDefault(name, new BigInteger[] {BigInteger.ZERO, BigInteger.ZERO});
      if (!expected[0].equals(BigInteger.valueOf(balance.reservedCash()))
          || !expected[1].equals(BigInteger.valueOf(balance.reservedLots())))
        problems.add("account " + name + " reservations do not equal its resting orders");
    }
    if (!cash.equals(expectedCash))
      problems.add("cash is not conserved: " + cash + " != " + expectedCash);
    if (!lots.equals(expectedLots))
      problems.add("lots are not conserved: " + lots + " != " + expectedLots);
    long bestBid =
        view.orders().stream()
            .filter(o -> o.side() == Side.BUY)
            .mapToLong(Order::priceTicks)
            .max()
            .orElse(Long.MIN_VALUE);
    long bestAsk =
        view.orders().stream()
            .filter(o -> o.side() == Side.SELL)
            .mapToLong(Order::priceTicks)
            .min()
            .orElse(Long.MAX_VALUE);
    if (bestBid >= bestAsk) problems.add("the resting book is crossed");
    for (Execution fill : view.executions())
      if (fill.lots() <= 0
          || fill.priceTicks() <= 0
          || fill.makerAccount().equals(fill.takerAccount()))
        problems.add("execution at sequence " + fill.sequence() + " is malformed");
    return problems;
  }
}
