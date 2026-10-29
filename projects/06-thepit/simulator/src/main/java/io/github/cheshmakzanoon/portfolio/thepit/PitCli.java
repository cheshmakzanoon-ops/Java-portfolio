package io.github.cheshmakzanoon.portfolio.thepit;

import static io.github.cheshmakzanoon.portfolio.thepit.Model.*;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Operator tool for a durable command log. Every inspection command opens the journal read-only, so
 * it is safe to run against the log of a live writer; an append in flight shows up as an incomplete
 * tail rather than as damage. Exit status: 0 success, 1 failed verification, 2 usage or unreadable
 * journal.
 */
public final class PitCli {
  static final String USAGE =
      """
      usage: pit <command> ...
        book    <journal> [--depth N]            order book, statistics and balances at the end
        replay  <journal> [--from SEQ] [--to SEQ] [--book]
                                                 narrate each durable command and its outcome
        verify  <journal>                        replay through the engine and the independent
                                                 oracle and check every invariant
        tour    [journal]                        scripted walkthrough ending in verification
      """;

  /** A mistake in how the tool was invoked, as opposed to a problem with the journal. */
  private static final class UsageException extends RuntimeException {
    UsageException(String message) {
      super(message);
    }
  }

  private final PrintStream out, err;

  public PitCli(PrintStream out, PrintStream err) {
    this.out = out;
    this.err = err;
  }

  public static void main(String[] args) {
    System.exit(new PitCli(System.out, System.err).run(args));
  }

  public int run(String... args) {
    if (args.length == 0) return usage(null);
    try {
      return switch (args[0]) {
        case "book" -> book(path(args), options(args, "--depth"));
        case "replay" -> replay(path(args), options(args, "--from", "--to", "--book"));
        case "verify" -> verify(path(args));
        case "tour" -> tour(args.length > 1 ? Path.of(args[1]) : null);
        case "help", "--help", "-h" -> {
          out.print(USAGE);
          yield 0;
        }
        default -> usage("unknown command: " + args[0]);
      };
    } catch (UsageException invalid) {
      return usage(invalid.getMessage());
    } catch (NoSuchFileException missing) {
      err.println("pit: no such journal: " + missing.getFile());
      return 2;
    } catch (IOException unreadable) {
      err.println("pit: cannot read journal: " + unreadable.getMessage());
      return 2;
    }
  }

  private int usage(String problem) {
    if (problem != null) err.println("pit: " + problem);
    err.print(USAGE);
    return 2;
  }

  private static Path path(String[] args) {
    if (args.length < 2 || args[1].startsWith("--"))
      throw new UsageException(args[0] + " needs a journal path");
    return Path.of(args[1]);
  }

  /** Parses {@code --name value} pairs and bare switches after the journal path. */
  private static Map<String, String> options(String[] args, String... allowed) {
    java.util.List<String> names = java.util.List.of(allowed);
    Map<String, String> parsed = new java.util.HashMap<>();
    for (int i = 2; i < args.length; i++) {
      String name = args[i];
      if (!names.contains(name)) throw new UsageException("unknown option: " + name);
      if (name.equals("--book")) parsed.put(name, "true");
      else {
        if (i + 1 >= args.length) throw new UsageException(name + " needs a value");
        if (parsed.putIfAbsent(name, args[++i]) != null)
          throw new UsageException(name + " given twice");
      }
    }
    return parsed;
  }

  private static long number(
      Map<String, String> options, String name, long fallback, long min, long max) {
    String value = options.get(name);
    if (value == null) return fallback;
    try {
      long parsed = Long.parseLong(value);
      if (parsed < min || parsed > max) throw new NumberFormatException();
      return parsed;
    } catch (NumberFormatException bad) {
      throw new UsageException(name + " must be an integer in " + min + ".." + max);
    }
  }

  private void header(Path path, JournalReader.Scan scan) {
    out.printf(
        "Journal  %s%nRun      %s  (%d records, %d bytes%s)%n",
        path,
        scan.runId() == null ? "(empty)" : scan.runId(),
        scan.entries().size(),
        scan.fileBytes(),
        scan.incompleteTailBytes() == 0
            ? ""
            : "; incomplete final frame of " + scan.incompleteTailBytes() + " bytes ignored");
  }

  private int book(Path path, Map<String, String> options) throws IOException {
    int depth = (int) number(options, "--depth", 8, 1, 100);
    JournalReader.Scan scan = JournalReader.inspect(path);
    Book book = new Book();
    MarketData.Tracker tracker = new MarketData.Tracker();
    for (Journal.Entry entry : scan.entries()) {
      try {
        book.apply(entry.sequence(), entry.command()).executions().forEach(tracker::accept);
      } catch (RuntimeException failure) {
        err.println(
            "pit: replay failed at sequence " + entry.sequence() + ": " + failure.getMessage());
        return 2;
      }
    }
    header(path, scan);
    View view = book.view();
    MarketData.Market market = MarketData.market(book.publicView(), tracker.snapshot(), depth);
    out.printf("As of    sequence %d%n%n", view.sequence());
    out.print(Ladder.render(market));
    MarketData.Stats stats = market.stats();
    out.printf(
        "%nRun statistics: %d fills, %s lots traded, vwap %s%n",
        stats.trades(), stats.volumeLots(), Ladder.vwap(stats.vwapTicks()));
    if (stats.trades() > 0)
      out.printf(
          "                 open %s  high %s  low %s  last %s%n",
          Ladder.price(stats.openTicks()),
          Ladder.price(stats.highTicks()),
          Ladder.price(stats.lowTicks()),
          Ladder.price(stats.lastTicks()));
    out.printf(
        "%n  %-12s %14s %8s %14s %14s%n",
        "account", "cash", "lots", "reserved cash", "reserved lots");
    new java.util.TreeMap<>(view.accounts())
        .forEach(
            (name, b) ->
                out.printf(
                    "  %-12s %14s %8d %14s %14d%n",
                    name,
                    Ladder.price(b.cash()),
                    b.lots(),
                    Ladder.price(b.reservedCash()),
                    b.reservedLots()));
    out.printf("%nOpen orders: %d%n", view.orders().size());
    view.orders().stream()
        .sorted(java.util.Comparator.comparingLong(Order::priority))
        .limit(25)
        .forEach(
            o ->
                out.printf(
                    "  %-12s %-10s %-4s %6d @ %-10s queue position %d%n",
                    o.accountId(),
                    o.orderId(),
                    o.side(),
                    o.remaining(),
                    Ladder.price(o.priceTicks()),
                    o.priority()));
    if (view.orders().size() > 25) out.printf("  ... and %d more%n", view.orders().size() - 25);
    return 0;
  }

  private int replay(Path path, Map<String, String> options) throws IOException {
    long from = number(options, "--from", 1, 1, Long.MAX_VALUE),
        to = number(options, "--to", Long.MAX_VALUE, 1, Long.MAX_VALUE);
    if (from > to) throw new UsageException("--from must not exceed --to");
    boolean showBook = options.containsKey("--book");
    JournalReader.Scan scan = JournalReader.inspect(path);
    header(path, scan);
    out.println();
    Book book = new Book();
    MarketData.Tracker tracker = new MarketData.Tracker();
    for (Journal.Entry entry : scan.entries()) {
      Command c = entry.command();
      Result result;
      try {
        result = book.apply(entry.sequence(), c);
      } catch (RuntimeException failure) {
        err.println(
            "pit: replay failed at sequence " + entry.sequence() + ": " + failure.getMessage());
        return 2;
      }
      result.executions().forEach(tracker::accept);
      if (entry.sequence() < from || entry.sequence() > to) continue;
      out.printf("#%-5d %-10s %s%n", entry.sequence(), c.accountId(), Ladder.describe(c));
      out.printf("       -> %s: %s%n", result.code(), Ladder.explain(result.code()));
      result.executions().forEach(fill -> out.println("          fill " + Ladder.describe(fill)));
      if (showBook)
        out.println(
            "          book "
                + Ladder.topOfBook(MarketData.market(book.publicView(), tracker.snapshot(), 1)));
    }
    return 0;
  }

  private int verify(Path path) throws IOException {
    JournalReader.Scan scan = JournalReader.inspect(path);
    JournalAudit.Report report = JournalAudit.verify(scan);
    header(path, scan);
    out.printf("Replay   %d commands applied by the matching engine%n", report.records());
    out.printf(
        "Oracle   results compared with the independent flat-list matcher; %d complete-state comparisons%n",
        report.stateChecks());
    out.printf(
        "Ledger   %d cash ticks and %d lots, reservations equal resting orders, book uncrossed%n",
        report.cashTotal(), report.lotTotal());
    out.printf("Digest   %s%n", report.digest());
    if (report.passed()) {
      out.println("PASS");
      return 0;
    }
    report.problems().forEach(problem -> err.println("FAIL     " + problem));
    out.println("FAIL");
    return 1;
  }

  private int tour(Path journal) throws IOException {
    Path target =
        journal != null
            ? journal
            : Files.createTempDirectory("thepit-tour").resolve("commands.log");
    try {
      return Tour.run(out, target) ? 0 : 1;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      err.println("pit: tour interrupted");
      return 2;
    } catch (Exception failure) {
      err.println("pit: tour failed: " + failure);
      return 2;
    }
  }
}
