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
            Demo.order("partial", "buyer", "partial", Side.BUY, 100, 3, Tif.GTC),
            Demo.order("fill", "buyer", "fill", Side.BUY, 100, 2, Tif.GTC),
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
            "FILLED", "RESTING", "SELF_TRADE_CANCELED"),
        outcomes);
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
