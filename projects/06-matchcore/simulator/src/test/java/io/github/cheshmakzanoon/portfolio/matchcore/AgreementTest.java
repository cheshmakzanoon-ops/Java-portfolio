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
