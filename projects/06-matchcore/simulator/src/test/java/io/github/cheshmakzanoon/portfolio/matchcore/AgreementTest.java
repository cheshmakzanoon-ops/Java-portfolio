package io.github.cheshmakzanoon.portfolio.matchcore;

import static io.github.cheshmakzanoon.portfolio.matchcore.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgreementTest {
  @TempDir Path temporary;

  @Test
  void exactDemo() throws Exception {
    Demo.run(temporary.resolve("journal"));
  }

  @Test
  void fullBookAdmissionAgreesWithIndependentReference() {
    Book production = new Book(1);
    ReferenceBook reference = new ReferenceBook(1);
    List<Command> steps =
        List.of(
            Demo.init("seller", 1000, 10),
            Demo.init("buyer", 1000, 0),
            Demo.order("ask", "seller", "ask", Side.SELL, 100, 2, Tif.GTC),
            Demo.order("noncross", "buyer", "noncross", Side.BUY, 99, 3, Tif.GTC),
            Demo.order("partial", "buyer", "partial", Side.BUY, 100, 3, Tif.GTC),
            Demo.order("ask2", "seller", "ask2", Side.SELL, 100, 2, Tif.GTC),
            Demo.order("self", "seller", "self", Side.BUY, 100, 1, Tif.GTC));
    List<String> outcomes = new ArrayList<>();
    for (int i = 0; i < steps.size(); i++) {
      Result actual = production.apply(i + 1L, steps.get(i));
      assertEquals(reference.apply(steps.get(i)), actual, "step=" + (i + 1));
      assertEquals(reference.view(), production.view(), "step=" + (i + 1));
      outcomes.add(actual.code());
    }
    assertEquals(
        List.of(
            "INITIALIZED", "INITIALIZED", "RESTING", "OPEN_ORDER_CAP",
            "RESTING", "RESTING", "SELF_TRADE_CANCELED"),
        outcomes);
  }

  @Test
  void fullBookAllowsARealFillBeforeAStoppingSelfTrade() {
    Book production = new Book(2);
    ReferenceBook reference = new ReferenceBook(2);
    List<Command> steps =
        List.of(
            Demo.init("first", 0, 1),
            Demo.init("second", 1000, 2),
            Demo.order("ask1", "first", "ask1", Side.SELL, 100, 1, Tif.GTC),
            Demo.order("ask2", "second", "ask2", Side.SELL, 101, 1, Tif.GTC),
            Demo.order("take", "second", "take", Side.BUY, 101, 3, Tif.GTC));
    for (int i = 0; i < steps.size(); i++) {
      Result result = production.apply(i + 1L, steps.get(i));
      assertEquals(reference.apply(steps.get(i)), result);
      assertEquals(reference.view(), production.view());
      if (i == steps.size() - 1) {
        assertEquals("SELF_TRADE_CANCELED", result.code());
        assertEquals(1, result.executions().size());
        assertEquals(1, result.executions().getFirst().lots());
        assertEquals(1, production.view().orders().size());
        assertEquals("second", production.view().orders().getFirst().accountId());
      }
    }
  }

  @Test
  void boundedCapacitySweepAgreesWithIndependentReferenceAcrossPricesAndQuantities() {
    for (int cap = 1; cap <= 4; cap++) {
      for (int demand = 1; demand <= cap * 3 + 2; demand++) {
        for (boolean crossAllLevels : List.of(false, true)) {
          Book production = new Book(cap);
          ReferenceBook reference = new ReferenceBook(cap);
          List<Command> steps = new ArrayList<>();
          steps.add(Demo.init("buyer", 100_000, 0));
          for (int level = 0; level < cap; level++)
            steps.add(Demo.init("seller" + level, 0, 10));
          for (int level = 0; level < cap; level++)
            steps.add(
                Demo.order(
                    "ask" + level,
                    "seller" + level,
                    "ask" + level,
                    Side.SELL,
                    100 + level,
                    2,
                    Tif.GTC));
          steps.add(
              Demo.order(
                  "buy",
                  "buyer",
                  "buy",
                  Side.BUY,
                  crossAllLevels ? 100 + cap - 1 : 100,
                  demand,
                  Tif.GTC));
          for (int n = 0; n < steps.size(); n++) {
            Result actual = production.apply(n + 1L, steps.get(n));
            assertEquals(
                reference.apply(steps.get(n)),
                actual,
                "capacity=" + cap + " demand=" + demand + " step=" + n);
            assertEquals(reference.view(), production.view());
            assertTrue(production.view().orders().size() <= cap);
          }
        }
      }
    }
  }

  @Test
  void replacementRulesAndFifo() {
    Book b = new Book();
    ReferenceBook r = new ReferenceBook();
    List<Command> history = new ArrayList<>(Demo.fixture());
    history.removeLast();
    history.add(Demo.replace("bad", "carol", "c", Side.SELL, 10100, 10000));
    history.add(Demo.replace("side", "carol", "c", Side.BUY, 10100, 1));
    history.add(Demo.replace("zero", "carol", "c", Side.SELL, 10100, 0));
    history.add(Demo.replace("terminal", "carol", "c", Side.SELL, 10100, 1));
    history.add(Demo.order("reuse", "carol", "c", Side.SELL, 10100, 1, Tif.GTC));
    for (int i = 0; i < history.size(); i++) {
      assertEquals(r.apply(history.get(i)), b.apply(i + 1, history.get(i)));
      assertEquals(r.view(), b.view());
    }
    assertEquals("RISK", b.view().results().get("carol:bad").code());
    assertEquals("SIDE_CHANGE", b.view().results().get("carol:side").code());
    assertEquals("TERMINAL", b.view().results().get("carol:terminal").code());
    assertEquals("ORDER_ID_USED", b.view().results().get("carol:reuse").code());
  }
}
