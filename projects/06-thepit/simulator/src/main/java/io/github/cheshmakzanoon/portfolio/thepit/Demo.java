package io.github.cheshmakzanoon.portfolio.thepit;

import static io.github.cheshmakzanoon.portfolio.thepit.Model.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class Demo {
  private Demo() {}

  public static Command init(String account, long cash, long lots) {
    return new Command(
        "init-" + account, account, Type.INIT, null, null, 0, 0, null, false, cash, lots);
  }

  public static Command order(
      String cid, String account, String oid, Side side, long price, long qty, Tif tif) {
    return new Command(cid, account, Type.NEW, oid, side, price, qty, tif, false, 0, 0);
  }

  public static Command replace(
      String cid, String account, String oid, Side side, long price, long qty) {
    return new Command(cid, account, Type.REPLACE, oid, side, price, qty, Tif.GTC, false, 0, 0);
  }

  public static Command cancel(String cid, String account, String oid) {
    return new Command(cid, account, Type.CANCEL, oid, null, 0, 0, null, false, 0, 0);
  }

  public static List<Command> fixture() {
    return List.of(
        init("alice", 100000, 0),
        init("bob", 0, 10),
        init("carol", 0, 10),
        init("dave", 0, 10),
        order("b1", "bob", "b", Side.SELL, 10100, 5, Tif.GTC),
        order("c1", "carol", "c", Side.SELL, 10100, 3, Tif.GTC),
        order("a1", "alice", "a1", Side.BUY, 10200, 6, Tif.GTC),
        replace("c2", "carol", "c", Side.SELL, 10100, 1),
        order("d1", "dave", "d", Side.SELL, 10100, 1, Tif.GTC),
        replace("c3", "carol", "c", Side.SELL, 10100, 2),
        order("a2", "alice", "a2", Side.BUY, 10100, 2, Tif.GTC),
        cancel("c4", "carol", "c"));
  }

  public static View run(Path path) throws Exception {
    ReferenceBook reference = new ReferenceBook();
    List<Command> commands = fixture();
    for (Command c : commands) reference.apply(c);
    try (Gateway gateway =
        new Gateway(
            path,
            "demo",
            64,
            1000,
            b -> {
              if (b.equals("AFTER_FORCE") && Files.exists(path.resolveSibling("armed")))
                throw new java.io.IOException("injected crash after force before apply/response");
            })) {
      for (Command c : commands.subList(0, 11))
        gateway.submit(c.accountId(), c).get(5, TimeUnit.SECONDS);
      int before = gateway.fullView().executions().size();
      Command duplicate = commands.get(10);
      gateway.submit("alice", duplicate).get(5, TimeUnit.SECONDS);
      check(gateway.fullView().executions().size() == before, "retry traded twice");
      gateway.snapshot();
      Files.writeString(path.resolveSibling("armed"), "fault");
      try {
        gateway.submit("carol", commands.getLast()).get(5, TimeUnit.SECONDS);
        throw new AssertionError("fault absent");
      } catch (java.util.concurrent.ExecutionException expected) {
        System.out.println("Cancellation client outcome UNKNOWN after durable force; restarting");
      }
    }
    Files.delete(path.resolveSibling("armed"));
    try (Gateway recovered = new Gateway(path, "demo", 64, 1000)) {
      Result r = recovered.submit("carol", commands.getLast()).get(5, TimeUnit.SECONDS);
      check(r.code().equals("CANCELED"), "retry cancellation");
      View v = recovered.fullView();
      check(v.executions().size() == 4, "four executions");
      check(v.executions().stream().mapToLong(Execution::lots).sum() == 8, "eight lots");
      check(v.accounts().get("alice").cash() == 19200, "Alice cash");
      check(v.accounts().get("alice").lots() == 8, "Alice lots");
      check(v.orders().isEmpty(), "empty book");
      check(v.results().size() == 12, "one result per ID");
      check(v.digest().equals(reference.view().digest()), "replay/reference digest");
      System.out.println(
          "PASS executions=4 lots=8 aliceCash=19200 aliceLots=8 openOrders=0 results=12 digest="
              + v.digest());
      return v;
    }
  }

  private static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }

  public static void main(String[] args) throws Exception {
    run(
        args.length == 0
            ? Files.createTempDirectory("thepit-demo").resolve("commands.log")
            : Path.of(args[0]));
  }
}
