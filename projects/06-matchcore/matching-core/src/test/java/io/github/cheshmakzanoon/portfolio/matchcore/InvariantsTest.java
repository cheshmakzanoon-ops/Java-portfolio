package io.github.cheshmakzanoon.portfolio.matchcore;

import static io.github.cheshmakzanoon.portfolio.matchcore.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class InvariantsTest {
  private Command init(String a, long cash, long lots) {
    return new Command("init", a, Type.INIT, null, null, 0, 0, null, false, cash, lots);
  }

  private Command order(
      String cid,
      String account,
      String oid,
      Side side,
      long price,
      long qty,
      Tif tif,
      boolean post) {
    return new Command(cid, account, Type.NEW, oid, side, price, qty, tif, post, 0, 0);
  }

  @Test
  void generatedConservationAndImmutableReads() {
    for (int seed = 0; seed < 40; seed++) {
      Random r = new Random(seed);
      Book b = new Book(20);
      long seq = 0;
      for (int a = 0; a < 4; a++) b.apply(++seq, init("a" + a, 100000, 1000));
      for (int i = 0; i < 300; i++) {
        String account = "a" + r.nextInt(5), oid = "o" + r.nextInt(50);
        Type type = r.nextInt(10) < 6 ? Type.NEW : r.nextBoolean() ? Type.CANCEL : Type.REPLACE;
        Side side = r.nextBoolean() ? Side.BUY : Side.SELL;
        Tif tif = Tif.values()[r.nextInt(3)];
        Command c =
            new Command(
                "c" + i,
                account,
                type,
                oid,
                side,
                95 + r.nextInt(11),
                type == Type.NEW ? 1 + r.nextInt(30) : r.nextInt(30),
                tif,
                tif == Tif.GTC && r.nextInt(5) == 0,
                0,
                0);
        View before = b.publicView();
        b.apply(++seq, c);
        View v = b.view();
        assertEquals(400000, v.accounts().values().stream().mapToLong(Balance::cash).sum());
        assertEquals(4000, v.accounts().values().stream().mapToLong(Balance::lots).sum());
        assertEquals(seq - 1, before.sequence());
        for (var entry : v.accounts().entrySet()) {
          Balance balance = entry.getValue();
          assertTrue(balance.cash() >= balance.reservedCash());
          assertTrue(balance.lots() >= balance.reservedLots());
          assertTrue(balance.reservedCash() >= 0);
          assertTrue(balance.reservedLots() >= 0);
          long cash = 0, lots = 0;
          for (Order o : v.orders())
            if (o.accountId().equals(entry.getKey())) {
              if (o.side() == Side.BUY)
                cash = Math.addExact(cash, Math.multiplyExact(o.priceTicks(), o.remaining()));
              else lots += o.remaining();
            }
          assertEquals(cash, balance.reservedCash());
          assertEquals(lots, balance.reservedLots());
        }
        if (i % 50 == 0) {
          Book restored = new Book(20);
          restored.restore(b.checkpoint());
          assertEquals(v, restored.view());
          assertEquals(b.publicView(), restored.publicView());
          b = restored;
        }
      }
    }
  }

  @Test
  void maximumSupplyCannotOverflowDuringTransfers() {
    Book b = new Book();
    b.apply(1, init("buyer", Long.MAX_VALUE, 0));
    b.apply(2, init("seller", 0, Long.MAX_VALUE));
    b.apply(3, order("sell", "seller", "s", Side.SELL, 1, Long.MAX_VALUE, Tif.GTC, false));
    assertEquals(
        "OVERFLOW",
        b.apply(
                4,
                new Command(
                    "bad",
                    "seller",
                    Type.REPLACE,
                    "s",
                    Side.SELL,
                    Long.MAX_VALUE,
                    2,
                    Tif.GTC,
                    false,
                    0,
                    0))
            .code());
    assertEquals(Long.MAX_VALUE, b.view().orders().getFirst().remaining());
    assertEquals(
        "FILLED",
        b.apply(5, order("buy", "buyer", "b", Side.BUY, 1, Long.MAX_VALUE, Tif.FOK, false)).code());
    assertEquals(Long.MAX_VALUE, b.view().accounts().get("buyer").lots());
    assertEquals(Long.MAX_VALUE, b.view().accounts().get("seller").cash());
    assertEquals(0, b.view().accounts().get("buyer").cash());
  }

  @Test
  void zeroRemainingIsCancellationEvenWhenSideFieldDiffers() {
    Book b = new Book();
    b.apply(1, init("a", 100, 10));
    b.apply(2, order("new", "a", "o", Side.BUY, 1, 1, Tif.GTC, false));
    assertEquals(
        "CANCELED",
        b.apply(
                3,
                new Command("zero", "a", Type.REPLACE, "o", Side.SELL, 1, 0, Tif.GTC, false, 0, 0))
            .code());
    assertTrue(b.view().orders().isEmpty());
  }

  @Test
  void replacementCannotChangeRestingTimeInForce() {
    Book b = new Book();
    b.apply(1, init("a", 1000, 10));
    b.apply(2, order("new", "a", "o", Side.BUY, 10, 2, Tif.GTC, false));
    for (Tif tif : List.of(Tif.IOC, Tif.FOK)) {
      var before = b.view().orders();
      assertEquals(
          "TIF_CHANGE",
          b.apply(
                  tif == Tif.IOC ? 3 : 4,
                  new Command(
                      "replace-" + tif, "a", Type.REPLACE, "o", Side.BUY, 10, 1, tif, false, 0, 0))
              .code());
      assertEquals(before, b.view().orders());
      assertEquals(20, b.view().accounts().get("a").reservedCash());
    }
  }

  @Test
  void explicitTerminalUnknownAndValidationPaths() {
    Book b = new Book(1);
    assertNull(b.lookup(init("a", 100, 10)));
    b.apply(1, init("a", 100, 10));
    assertEquals(
        "ACCOUNT_EXISTS",
        b.apply(2, new Command("again", "a", Type.INIT, null, null, 0, 0, null, false, 0, 0))
            .code());
    b.apply(3, order("o", "a", "o", Side.BUY, 1, 2, Tif.GTC, false));
    assertEquals(
        "OPEN_ORDER_CAP",
        b.apply(4, order("full", "a", "full", Side.BUY, 1, 1, Tif.GTC, false)).code());
    assertThrows(IllegalStateException.class, () -> b.restore(b.checkpoint()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Command("bad id", "a", Type.INIT, null, null, 0, 0, null, false, 0, 0).validate());
    assertThrows(
        IllegalArgumentException.class,
        () -> new Command("x", "a", Type.CANCEL, null, null, 0, 0, null, false, 0, 0).validate());
    assertThrows(
        IllegalArgumentException.class,
        () -> new Command("x", "a", Type.INIT, null, null, 0, 0, null, false, -1, 0).validate());
    assertThrows(
        IllegalArgumentException.class,
        () -> order("x", "a", "x", Side.BUY, 1, 1, Tif.IOC, true).validate());
  }
}
