package io.github.cheshmakzanoon.portfolio.matchcore;

import static io.github.cheshmakzanoon.portfolio.matchcore.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import net.jqwik.api.*;

class AgreementPropertyTest {
  @Property(tries = 120, seed = "42006006")
  void generatedAgreement(@ForAll long seed) {
    Random random = new Random(seed);
    Book production = new Book(100);
    ReferenceBook reference = new ReferenceBook(100);
    long sequence = 0;
    for (int a = 0; a < 8; a++) {
      Command c = Demo.init("a" + a, 1000000, 1000);
      assertEquals(reference.apply(c), production.apply(++sequence, c));
    }
    for (int i = 0; i < 350; i++) {
      String account = "a" + random.nextInt(8), id = "o" + random.nextInt(120);
      Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
      long price = 95 + random.nextInt(11), qty = random.nextInt(15);
      int action = random.nextInt(100);
      Tif generatedTif = Tif.values()[random.nextInt(3)];
      Command c =
          action < 60
              ? new Command(
                  "c" + i,
                  account,
                  Type.NEW,
                  id,
                  side,
                  price,
                  qty + 1,
                  generatedTif,
                  generatedTif == Tif.GTC && random.nextInt(8) == 0,
                  0,
                  0)
              : action < 85
                  ? Demo.cancel("c" + i, account, id)
                  : Demo.replace("c" + i, account, id, side, price, qty);
      assertEquals(
          reference.apply(c), production.apply(++sequence, c), "seed=" + seed + " command=" + c);
      assertEquals(reference.view(), production.view(), "seed=" + seed + " at " + i);
      if (i % 50 == 0) {
        View stable = production.view();
        assertEquals(reference.apply(c), production.lookup(c));
        assertEquals(stable, production.view());
        Command conflict =
            new Command(
                c.clientCommandId(),
                c.accountId(),
                c.type(),
                c.orderId(),
                c.side(),
                c.priceTicks(),
                c.quantityLots() + 1,
                c.timeInForce(),
                c.postOnly(),
                c.initialCash(),
                c.initialLots());
        assertEquals(reference.apply(conflict), production.lookup(conflict));
        assertEquals(stable, production.view());
      }
      View v = production.view();
      assertEquals(8000000, v.accounts().values().stream().mapToLong(Balance::cash).sum());
      assertEquals(8000, v.accounts().values().stream().mapToLong(Balance::lots).sum());
      for (Balance b : v.accounts().values()) {
        assertTrue(b.cash() >= b.reservedCash());
        assertTrue(b.lots() >= b.reservedLots());
        assertTrue(b.reservedCash() >= 0);
        assertTrue(b.reservedLots() >= 0);
      }
    }
  }
}
