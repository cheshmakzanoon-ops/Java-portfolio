package io.github.cheshmakzanoon.portfolio.thepit;

import static io.github.cheshmakzanoon.portfolio.thepit.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Market data, the public tape and the per-account fill index, including recovery paths. */
class GatewayMarketTest {
  @TempDir Path dir;

  private static Command init(String account, long cash, long lots) {
    return new Command(
        "init-" + account, account, Type.INIT, null, null, 0, 0, null, false, cash, lots);
  }

  private static Command order(String account, String id, Side side, long price, long lots) {
    return new Command(id, account, Type.NEW, id, side, price, lots, Tif.GTC, false, 0, 0);
  }

  private static List<Command> sweepScenario() {
    return List.of(
        init("buyer", 1000, 0),
        init("seller1", 0, 10),
        init("seller2", 0, 10),
        order("seller1", "ask1", Side.SELL, 10, 1),
        order("seller2", "ask2", Side.SELL, 11, 3),
        order("buyer", "buy", Side.BUY, 12, 3));
  }

  private static void submitAll(Gateway gateway, List<Command> commands) throws Exception {
    for (Command command : commands)
      gateway.submit(command.accountId(), command).get(5, TimeUnit.SECONDS);
  }

  @Test
  void marketAndTapeDescribeTheBookAndSurviveEveryRecoveryPath() throws Exception {
    Path path = dir.resolve("market");
    MarketData.Market live;
    Gateway.TradeTape liveTape;
    try (Gateway gateway = new Gateway(path, "run", 8, 100)) {
      assertNull(gateway.market(5).bid());
      assertEquals("0", gateway.market(5).stats().volumeLots());
      assertTrue(gateway.trades(5).trades().isEmpty());
      submitAll(gateway, sweepScenario());
      live = gateway.market(5);
      liveTape = gateway.trades(5);
      assertEquals(6, live.lastAppliedSequence());
      assertNull(live.bid(), "the buyer's remainder was fully consumed");
      assertEquals(new MarketData.Level(11, 1), live.ask());
      assertEquals(2, live.stats().trades());
      assertEquals("3", live.stats().volumeLots());
      assertEquals("32", live.stats().notionalTicks());
      assertEquals("10.6667", live.stats().vwapTicks());
      assertEquals(10L, live.stats().openTicks());
      assertEquals(11L, live.stats().lastTicks());
      assertEquals(2L, live.stats().lastLots());
      assertEquals(
          List.of(
              new MarketData.Trade(6, 11, 2, Side.BUY), new MarketData.Trade(6, 10, 1, Side.BUY)),
          liveTape.trades());
      assertEquals(6, liveTape.lastAppliedSequence());
      assertEquals(1, gateway.trades(1).trades().size());
    }
    try (Gateway replayed = new Gateway(path, "run", 8, 100)) {
      assertEquals(live, replayed.market(5));
      assertEquals(liveTape, replayed.trades(5));
      replayed.snapshot();
    }
    try (Gateway restored = new Gateway(path, "run", 8, 100)) {
      assertEquals(live, restored.market(5));
      assertEquals(liveTape, restored.trades(5));
      Command second = order("seller1", "ask3", Side.SELL, 9, 2);
      Command taker = order("buyer", "buy2", Side.BUY, 9, 2);
      restored.submit("seller1", second).get(5, TimeUnit.SECONDS);
      restored.submit("buyer", taker).get(5, TimeUnit.SECONDS);
      MarketData.Market after = restored.market(5);
      assertEquals(3, after.stats().trades());
      assertEquals("5", after.stats().volumeLots());
      assertEquals(9L, after.stats().lastTicks());
      assertEquals(
          List.of(9L, 11L, 10L),
          restored.trades(3).trades().stream().map(MarketData.Trade::priceTicks).toList());
    }
  }

  @Test
  void tapeCarriesAggressorSideAndNoIdentity() throws Exception {
    try (Gateway gateway = new Gateway(dir.resolve("aggressor"), "run", 8, 100)) {
      submitAll(
          gateway,
          List.of(
              init("alice", 1000, 10),
              init("bob", 1000, 10),
              order("alice", "bid", Side.BUY, 10, 2),
              order("bob", "hit", Side.SELL, 10, 1),
              order("bob", "ask", Side.SELL, 11, 2),
              order("alice", "lift", Side.BUY, 11, 1)));
      List<MarketData.Trade> tape = gateway.trades(10).trades();
      assertEquals(
          List.of(
              new MarketData.Trade(6, 11, 1, Side.BUY), new MarketData.Trade(4, 10, 1, Side.SELL)),
          tape);
      for (var component : MarketData.Trade.class.getRecordComponents())
        assertFalse(
            component.getName().toLowerCase(Locale.ROOT).matches(".*(account|order|client).*"),
            component.getName());
    }
  }

  @Test
  void accountFillPagesMatchAnIndependentFilterForEveryCursor() throws Exception {
    Random random = new Random(20261029);
    List<String> accounts = List.of("a0", "a1", "a2", "a3");
    List<Command> commands = new ArrayList<>();
    for (String account : accounts) commands.add(init(account, 100_000, 1_000));
    for (int i = 0; i < 400; i++)
      commands.add(
          new Command(
              "c" + i,
              accounts.get(random.nextInt(accounts.size())),
              Type.NEW,
              "o" + i,
              random.nextBoolean() ? Side.BUY : Side.SELL,
              98 + random.nextInt(5),
              1 + random.nextInt(8),
              random.nextInt(3) == 0 ? Tif.IOC : Tif.GTC,
              false,
              0,
              0));
    try (Gateway gateway = new Gateway(dir.resolve("pages"), "run", 16, 1_000)) {
      submitAll(gateway, commands);
      View full = gateway.fullView();
      assertTrue(full.executions().size() > 100, "workload must trade");
      long last = full.sequence();
      for (String account : accounts) {
        List<Execution> mine =
            full.executions().stream()
                .filter(e -> e.makerAccount().equals(account) || e.takerAccount().equals(account))
                .toList();
        for (long cutoff : new long[] {0, 4, 60, last / 2, last - 1, last}) {
          List<Execution> visible = mine.stream().filter(e -> e.sequence() <= cutoff).toList();
          for (int offset : new int[] {0, 1, 7, visible.size(), visible.size() + 3, 100_000}) {
            for (int limit : new int[] {1, 5, 100}) {
              List<Execution> expected = visible.stream().skip(offset).limit(limit).toList();
              assertEquals(
                  expected,
                  gateway.executions(account, offset, limit, cutoff),
                  account + " offset=" + offset + " limit=" + limit + " cutoff=" + cutoff);
            }
          }
        }
        assertEquals(mine, gateway.executions(account, 0, mine.size(), last));
        assertEquals(
            gateway.executions(account, 0, 5, last),
            gateway.executions(account, -3, 5, last),
            "a negative offset is treated as the start");
      }
      assertTrue(gateway.executions("nobody", 0, 10, last).isEmpty());
    }
  }

  @Test
  void viewAndStatisticsArePublishedAtomically() throws Exception {
    // A deterministic workload produces exactly one fill per even-numbered command after setup.
    List<Command> commands = new ArrayList<>(List.of(init("maker", 1_000_000, 1_000_000)));
    commands.add(init("taker", 1_000_000, 1_000_000));
    for (int i = 0; i < 300; i++) {
      commands.add(order("maker", "m" + i, Side.SELL, 100 + i % 7, 1));
      commands.add(order("taker", "t" + i, Side.BUY, 100 + i % 7, 1));
    }
    Book model = new Book();
    long[] fillsAt = new long[commands.size() + 1];
    long[] lastPriceAt = new long[commands.size() + 1];
    long sequence = 0, fills = 0, lastPrice = 0;
    for (Command command : commands) {
      for (Execution e : model.apply(++sequence, command).executions()) {
        fills++;
        lastPrice = e.priceTicks();
      }
      fillsAt[(int) sequence] = fills;
      lastPriceAt[(int) sequence] = lastPrice;
    }
    assertEquals(300, fills);

    AtomicReference<Throwable> failure = new AtomicReference<>();
    try (Gateway gateway = new Gateway(dir.resolve("atomic"), "run", 64, 10_000)) {
      CountDownLatch stop = new CountDownLatch(1);
      Thread[] readers = new Thread[3];
      for (int r = 0; r < readers.length; r++) {
        readers[r] =
            Thread.ofPlatform()
                .start(
                    () -> {
                      try {
                        while (stop.getCount() > 0) {
                          MarketData.Market market = gateway.market(1);
                          int at = (int) market.lastAppliedSequence();
                          assertEquals(fillsAt[at], market.stats().trades(), "sequence " + at);
                          if (fillsAt[at] > 0)
                            assertEquals(lastPriceAt[at], market.stats().lastTicks());
                          Gateway.TradeTape tape = gateway.trades(100);
                          for (MarketData.Trade trade : tape.trades())
                            assertTrue(trade.sequence() <= tape.lastAppliedSequence());
                        }
                      } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                      }
                    });
      }
      try {
        List<CompletableFuture<Result>> pending = new ArrayList<>();
        for (Command command : commands) {
          // Respect the bounded admission queue; completion order is the journal order.
          pending.add(gateway.submit(command.accountId(), command));
          if (pending.size() >= 32) pending.removeFirst().get(10, TimeUnit.SECONDS);
        }
        for (var future : pending) future.get(10, TimeUnit.SECONDS);
      } finally {
        stop.countDown();
        for (Thread reader : readers) reader.join(5_000);
      }
      assertNull(failure.get(), () -> String.valueOf(failure.get()));
      assertEquals(300, gateway.market(1).stats().trades());
      assertEquals(commands.size(), gateway.market(1).lastAppliedSequence());
    }
  }
}
