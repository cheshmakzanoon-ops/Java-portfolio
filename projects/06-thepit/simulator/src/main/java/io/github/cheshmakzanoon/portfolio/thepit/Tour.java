package io.github.cheshmakzanoon.portfolio.thepit;

import static io.github.cheshmakzanoon.portfolio.thepit.Model.*;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * A narrated walkthrough on a real durable journal: price-time priority, execution at the maker's
 * price, queue priority under replacement, durable rejections, idempotent retry, restart from the
 * journal alone, and independent verification. Unlike {@link Demo} it asserts nothing itself; the
 * closing audit is the check.
 */
final class Tour {
  private static final String RUN = "tour";

  private final PrintStream out;
  private final Path journal;
  private Gateway gateway;

  private Tour(PrintStream out, Path journal) {
    this.out = out;
    this.journal = journal;
  }

  /** Returns whether the closing independent audit passed. */
  static boolean run(PrintStream out, Path journal) throws Exception {
    Tour tour = new Tour(out, journal);
    tour.gateway = new Gateway(journal, RUN, 64, 1000);
    try {
      tour.script();
    } finally {
      tour.gateway.close();
    }
    return tour.audit();
  }

  private void step(String title, String why) {
    out.printf("%n== %s%n   %s%n", title, why);
  }

  private Result send(Command c) throws Exception {
    Result r = gateway.submit(c.accountId(), c).get(5, TimeUnit.SECONDS);
    out.printf(
        "  #%d %-6s %s%n        -> %s: %s%n",
        r.sequence(), c.accountId(), Ladder.describe(c), r.code(), Ladder.explain(r.code()));
    for (Execution fill : r.executions()) out.println("        fill " + Ladder.describe(fill));
    return r;
  }

  private void show() {
    out.println();
    out.print(Ladder.render(gateway.market(5)));
  }

  private void script() throws Exception {
    out.println(
        "The Pit: a single-instrument exchange simulator. Prices are shown in quote units;");
    out.println("internally everything is an integer number of 0.01 ticks and whole lots.");
    out.println("Journal: " + journal);

    step(
        "1. Fund four synthetic accounts",
        "Balances are integers. Nothing can be created or borrowed later.");
    send(Demo.init("alice", 200_000, 0));
    send(Demo.init("bob", 0, 10));
    send(Demo.init("carol", 0, 10));
    send(Demo.init("dave", 0, 10));

    step(
        "2. Two sellers queue at one price",
        "Bob is first in line at 101.00; Carol joins behind him.");
    send(Demo.order("b1", "bob", "b", Side.SELL, 10100, 5, Tif.GTC));
    send(Demo.order("c1", "carol", "c", Side.SELL, 10100, 3, Tif.GTC));
    show();

    step(
        "3. A marketable buy sweeps price-time priority",
        "Alice bids 102.00 but trades at 101.00, the resting maker's price. Bob's earlier order fills before Carol's is touched.");
    Command sweep = Demo.order("a1", "alice", "a1", Side.BUY, 10200, 6, Tif.GTC);
    Result sweepResult = send(sweep);
    show();

    step(
        "4. Reducing keeps your place; increasing sends you to the back",
        "Carol shrinks her order (REDUCED, same place), Dave queues behind her, then Carol grows hers and loses priority to Dave.");
    send(Demo.replace("c2", "carol", "c", Side.SELL, 10100, 1));
    send(Demo.order("d1", "dave", "d", Side.SELL, 10100, 1, Tif.GTC));
    send(Demo.replace("c3", "carol", "c", Side.SELL, 10100, 2));
    out.println("   Alice buys 2: the first fill must go to Dave, who is now ahead of Carol.");
    send(Demo.order("a2", "alice", "a2", Side.BUY, 10100, 2, Tif.GTC));
    show();

    step(
        "5. Rejections are decisions too",
        "Each is journaled with its own sequence, so a replay reproduces it exactly.");
    send(Demo.order("a3", "alice", "a3", Side.BUY, 10100, 5, Tif.FOK));
    send(new Command("a4", "alice", Type.NEW, "a4", Side.BUY, 10100, 1, Tif.GTC, true, 0, 0));
    send(Demo.order("b2", "bob", "b2", Side.SELL, 9900, 99, Tif.GTC));

    step(
        "6. Retries are idempotent",
        "A client that never saw a reply resends the same command ID. The original outcome returns and nothing trades twice.");
    long before = gateway.market(1).stats().trades();
    Result again = gateway.submit("alice", sweep).get(5, TimeUnit.SECONDS);
    out.printf(
        "  resent a1 -> %s with sequence %d (the original); fills so far: %d before, %d after%n",
        again.code(), again.sequence(), before, gateway.market(1).stats().trades());
    Command conflicting = Demo.order("a1", "alice", "a1", Side.BUY, 10200, 7, Tif.GTC);
    Result conflict = gateway.submit("alice", conflicting).get(5, TimeUnit.SECONDS);
    out.printf(
        "  same ID, different payload -> %s: %s%n",
        conflict.code(), Ladder.explain(conflict.code()));
    if (!again.equals(sweepResult)) throw new IllegalStateException("retry changed the outcome");

    step(
        "7. Restart from the journal alone",
        "The process stops and a new one rebuilds every balance, order and queue position by replaying forced records.");
    String digest = gateway.fullView().digest();
    gateway.close();
    gateway = new Gateway(journal, RUN, 64, 1000);
    String recovered = gateway.fullView().digest();
    out.printf(
        "  state digest before restart: %s%n  state digest after restart:  %s%n  identical: %s%n",
        digest, recovered, digest.equals(recovered));
    if (!digest.equals(recovered)) throw new IllegalStateException("restart changed the state");
    show();
  }

  private boolean audit() throws Exception {
    step(
        "8. Independent verification",
        "The journal is replayed through the engine and through a deliberately slow flat-list matcher that shares none of its indexes.");
    JournalAudit.Report report = JournalAudit.verify(journal);
    out.printf(
        "  %d records, %d full-state comparisons, cash %d ticks and %d lots conserved%n  digest %s%n",
        report.records(),
        report.stateChecks(),
        report.cashTotal(),
        report.lotTotal(),
        report.digest());
    report.problems().forEach(problem -> out.println("  PROBLEM " + problem));
    out.println(report.passed() ? "PASS" : "FAIL");
    return report.passed();
  }
}
