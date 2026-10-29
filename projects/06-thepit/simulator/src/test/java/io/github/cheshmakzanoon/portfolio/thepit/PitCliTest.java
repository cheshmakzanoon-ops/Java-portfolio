package io.github.cheshmakzanoon.portfolio.thepit;

import static io.github.cheshmakzanoon.portfolio.thepit.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PitCliTest {
  /** Digest recorded for the asserted demo in the project's committed evidence. */
  private static final String DEMO_DIGEST =
      "4e226aa11dbed09f79d746c5df50ac857e30804a2e43d3ccf39893d656f959e3";

  @TempDir Path dir;

  private record Run(int status, String out, String err) {}

  private static Run cli(String... args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
    int status =
        new PitCli(
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8))
            .run(args);
    return new Run(
        status,
        out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"),
        err.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
  }

  private Path demoJournal() throws Exception {
    Path path = dir.resolve("demo").resolve("commands.log");
    Demo.run(path);
    return path;
  }

  @Test
  void pricesAreExactDecimalsOfTicks() {
    assertEquals("0.00", Ladder.price(0));
    assertEquals("0.05", Ladder.price(5));
    assertEquals("0.10", Ladder.price(10));
    assertEquals("101.00", Ladder.price(10100));
    assertEquals("92233720368547758.07", Ladder.price(Long.MAX_VALUE));
    assertThrows(IllegalArgumentException.class, () -> Ladder.price(-1));
    assertEquals("100.50", Ladder.mid("10050"));
    assertEquals("100.505", Ladder.mid("10050.5"));
    assertEquals("-", Ladder.vwap(null));
    assertEquals("101.2500", Ladder.vwap("10125.0000"));
  }

  @Test
  void ladderPutsAsksAboveBidsWithCumulativeQuantityFromTheBestPrice() {
    View view =
        new View(
            9,
            Map.of(),
            List.of(
                new Order("", "depth", Side.SELL, 10200, 2, 0, 0),
                new Order("", "depth", Side.SELL, 10100, 5, 0, 0),
                new Order("", "depth", Side.BUY, 10000, 4, 0, 0),
                new Order("", "depth", Side.BUY, 9950, 2, 0, 0)),
            List.of(),
            Map.of());
    MarketData.Tracker tracker = new MarketData.Tracker();
    tracker.accept(new Execution(3, "m", "o", "t", "p", 10100, 1));
    String ladder = Ladder.render(MarketData.market(view, tracker.snapshot(), 5));
    assertEquals(
        """
                       price       lots   cumulative
          ASK         102.00          2            7
          ASK         101.00          5            5
          ------ spread 1.00  mid 100.50  last 101.00 ------
          BID         100.00          4            4
          BID          99.50          2            6
        """
            .replace("\r", ""),
        ladder.replace("\r", ""));
    assertEquals(
        "100.00 x 4 / 101.00 x 5  spread 1.00",
        Ladder.topOfBook(MarketData.market(view, tracker.snapshot(), 1)));
    String empty =
        Ladder.render(
            MarketData.market(
                new View(0, Map.of(), List.of(), List.of(), Map.of()),
                new MarketData.Tracker().snapshot(),
                5));
    assertTrue(empty.contains("empty book") && empty.contains("(empty)"), empty);
  }

  @Test
  void demoJournalVerifiesAgainstTheOracleAndMatchesRecordedEvidence() throws Exception {
    Path journal = demoJournal();
    Run run = cli("verify", journal.toString());
    assertEquals(0, run.status(), run.err());
    assertTrue(run.out().contains("12 commands applied"), run.out());
    assertTrue(run.out().contains("Digest   " + DEMO_DIGEST), run.out());
    assertTrue(run.out().endsWith("PASS\n"), run.out());
    assertEquals("", run.err());
  }

  @Test
  void bookAndReplayNarrateTheDurableHistory() throws Exception {
    Path journal = demoJournal();
    Run book = cli("book", journal.toString(), "--depth", "3");
    assertEquals(0, book.status(), book.err());
    assertTrue(book.out().contains("As of    sequence 12"), book.out());
    assertTrue(book.out().contains("Open orders: 0"), book.out());
    assertTrue(book.out().contains("4 fills, 8 lots traded, vwap 101.0000"), book.out());
    // Alice ends with 19,200 ticks (192.00) and 8 lots, as the asserted demo requires.
    assertTrue(book.out().matches("(?s).*alice\\s+192\\.00\\s+8\\s+0\\.00\\s+0\\n.*"), book.out());

    Run replay = cli("replay", journal.toString(), "--from", "7", "--to", "9", "--book");
    assertEquals(0, replay.status(), replay.err());
    assertTrue(replay.out().contains("#7     alice"), replay.out());
    assertFalse(replay.out().contains("#6 "), "range excludes earlier records");
    assertFalse(replay.out().contains("#10"), "range excludes later records");
    assertTrue(
        replay.out().contains("fill 5 lots @ 101.00  maker bob[b]  taker alice[a1]"), replay.out());
    assertTrue(
        replay.out().contains("REDUCED: quantity reduced in place; queue priority kept"),
        replay.out());
    assertTrue(replay.out().contains("book no bid / 101.00 x"), replay.out());
  }

  @Test
  void inspectionNeverModifiesAJournalEvenWithAnInFlightAppend() throws Exception {
    // A scratch journal supplies the genuine third frame whose first bytes a live writer has
    // written but not yet completed.
    Path scratch = dir.resolve("scratch.log");
    try (Journal full = new Journal(scratch, "live")) {
      full.appendAndForce(1, Demo.init("alice", 1000, 0));
      full.appendAndForce(2, Demo.init("bob", 0, 5));
      full.appendAndForce(3, Demo.init("carol", 0, 5));
    }
    byte[] third = Files.readAllBytes(scratch);
    Path journal = dir.resolve("live.log");
    try (Journal writer = new Journal(journal, "live")) {
      writer.appendAndForce(1, Demo.init("alice", 1000, 0));
      writer.appendAndForce(2, Demo.init("bob", 0, 5));
      byte[] complete = Files.readAllBytes(journal);
      byte[] partial = Arrays.copyOf(complete, complete.length + 30);
      System.arraycopy(third, complete.length, partial, complete.length, 30);
      Files.write(journal, partial);
      Run run = cli("verify", journal.toString());
      assertEquals(0, run.status(), run.err());
      assertTrue(run.out().contains("incomplete final frame of 30 bytes ignored"), run.out());
      assertTrue(run.out().contains("2 commands applied"), run.out());
      assertArrayEquals(partial, Files.readAllBytes(journal), "inspection must not truncate");
    }
  }

  @Test
  void usageMistakesAndUnreadableJournalsExitWithStatusTwo() throws Exception {
    Path journal = demoJournal();
    String path = journal.toString();
    for (String[] invalid :
        new String[][] {
          {},
          {"nonsense"},
          {"book"},
          {"book", "--depth", "3"},
          {"book", path, "--depth", "0"},
          {"book", path, "--depth", "101"},
          {"book", path, "--depth", "x"},
          {"book", path, "--depth"},
          {"book", path, "--depth", "1", "--depth", "2"},
          {"book", path, "--bogus"},
          {"replay", path, "--from", "9", "--to", "3"},
          {"replay", path, "--from", "0"},
          {"verify"},
        }) {
      Run run = cli(invalid);
      assertEquals(2, run.status(), Arrays.toString(invalid));
      assertTrue(run.err().contains("usage: pit"), Arrays.toString(invalid));
      assertEquals("", run.out(), Arrays.toString(invalid));
    }
    Run missing = cli("verify", dir.resolve("absent.log").toString());
    assertEquals(2, missing.status());
    assertTrue(missing.err().contains("no such journal"), missing.err());

    byte[] bytes = Files.readAllBytes(journal);
    bytes[30] ^= 1;
    Path corrupt = dir.resolve("corrupt.log");
    Files.write(corrupt, bytes);
    for (String command : List.of("verify", "book", "replay")) {
      Run run = cli(command, corrupt.toString());
      assertEquals(2, run.status(), command);
      assertTrue(run.err().contains("cannot read journal"), run.err());
    }
    assertArrayEquals(bytes, Files.readAllBytes(corrupt), "corruption must be left in place");
    assertEquals(0, cli("help").status());
    assertTrue(cli("help").out().contains("usage: pit"));
  }

  @Test
  void emptyJournalIsValidAndShowsAnEmptyBook() throws Exception {
    Path empty = dir.resolve("empty.log");
    Files.write(empty, new byte[0]);
    Run verify = cli("verify", empty.toString());
    assertEquals(0, verify.status(), verify.err());
    assertTrue(
        verify.out().contains("(empty)") && verify.out().contains("0 commands"), verify.out());
    Run book = cli("book", empty.toString());
    assertEquals(0, book.status());
    assertTrue(book.out().contains("Open orders: 0"), book.out());
  }

  @Test
  void auditFlagsEveryBrokenInvariant() {
    Balance sound = new Balance(100, 10, 0, 0);
    View healthy = new View(1, Map.of("a", sound), List.of(), List.of(), Map.of());
    assertEquals(
        List.of(),
        JournalAudit.invariantProblems(healthy, BigInteger.valueOf(100), BigInteger.TEN));

    assertTrue(
        JournalAudit.invariantProblems(healthy, BigInteger.valueOf(99), BigInteger.TEN)
            .getFirst()
            .contains("cash is not conserved"));
    assertTrue(
        JournalAudit.invariantProblems(healthy, BigInteger.valueOf(100), BigInteger.ONE)
            .getFirst()
            .contains("lots are not conserved"));

    View phantomHold =
        new View(1, Map.of("a", new Balance(100, 10, 50, 0)), List.of(), List.of(), Map.of());
    assertTrue(
        JournalAudit.invariantProblems(phantomHold, BigInteger.valueOf(100), BigInteger.TEN)
            .stream()
            .anyMatch(p -> p.contains("reservations do not equal its resting orders")));

    View overReserved =
        new View(1, Map.of("a", new Balance(100, 10, 101, 11)), List.of(), List.of(), Map.of());
    assertTrue(
        JournalAudit.invariantProblems(overReserved, BigInteger.valueOf(100), BigInteger.TEN)
            .stream()
            .anyMatch(p -> p.contains("reserves more than it owns")));

    View negative =
        new View(1, Map.of("a", new Balance(-1, 10, 0, 0)), List.of(), List.of(), Map.of());
    assertTrue(
        JournalAudit.invariantProblems(negative, BigInteger.valueOf(-1), BigInteger.TEN).stream()
            .anyMatch(p -> p.contains("negative balance")));

    View crossed =
        new View(
            1,
            Map.of("a", new Balance(1000, 10, 100, 1)),
            List.of(
                new Order("a", "bid", Side.BUY, 10, 10, 1, 0),
                new Order("a", "ask", Side.SELL, 10, 1, 2, 0)),
            List.of(),
            Map.of());
    assertTrue(
        JournalAudit.invariantProblems(crossed, BigInteger.valueOf(1000), BigInteger.TEN).stream()
            .anyMatch(p -> p.contains("crossed")));

    View badFill =
        new View(
            1,
            Map.of("a", sound),
            List.of(),
            List.of(new Execution(1, "a", "x", "a", "y", 10, 1)),
            Map.of());
    assertTrue(
        JournalAudit.invariantProblems(badFill, BigInteger.valueOf(100), BigInteger.TEN).stream()
            .anyMatch(p -> p.contains("malformed")));
  }

  @Test
  void auditReportsADurableCommandTheEngineRefuses() {
    JournalReader.Scan gap =
        new JournalReader.Scan("run", List.of(new Journal.Entry(2, Demo.init("a", 1, 1))), 0, 0);
    JournalAudit.Report report = JournalAudit.verify(gap);
    assertFalse(report.passed());
    assertTrue(
        report.problems().getFirst().contains("matching engine refused"),
        report.problems().toString());
  }

  @Test
  void tourNarratesTheLessonsAndEndsInAPassingIndependentAudit() throws Exception {
    Path journal = dir.resolve("tour").resolve("commands.log");
    Run run = cli("tour", journal.toString());
    assertEquals(0, run.status(), run.err());
    String out = run.out();
    assertTrue(out.endsWith("PASS\n"), out);
    assertTrue(out.contains("identical: true"), out);
    assertTrue(out.contains("FOK_UNFILLED"), out);
    assertTrue(out.contains("POST_ONLY"), out);
    assertTrue(out.contains("-> RISK"), out);
    assertTrue(out.contains("CONFLICT"), out);
    assertTrue(out.contains("fills so far: 4 before, 4 after"), out);
    int dave = out.indexOf("maker dave[d]  taker alice[a2]"),
        carol = out.indexOf("maker carol[c]  taker alice[a2]");
    assertTrue(dave > 0 && carol > dave, "a priority-losing increase must queue behind Dave");
    JournalAudit.Report audit = JournalAudit.verify(journal);
    assertTrue(audit.passed(), audit.problems().toString());
    assertEquals(14, audit.records());
    assertTrue(out.contains(audit.digest()), "reported digest must be the replayed digest");
  }
}
