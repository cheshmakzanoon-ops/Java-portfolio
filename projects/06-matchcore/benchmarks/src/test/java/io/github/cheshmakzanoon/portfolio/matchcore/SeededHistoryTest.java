package io.github.cheshmakzanoon.portfolio.matchcore;

import static io.github.cheshmakzanoon.portfolio.matchcore.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class SeededHistoryTest {
  @Test
  void targetsLiveIdentitiesPreservesMixAndTracksPartialFills() {
    for (long seed : List.of(1L, 42L, 42006006L)) {
      SeededHistory history = new SeededHistory(seed);
      Book book = new Book();
      long sequence = 0;
      Map<Type, Integer> mix = new EnumMap<>(Type.class);
      for (int a = 0; a < 64; a++)
        book.apply(
            ++sequence,
            new Command(
                "init",
                "a" + a,
                Type.INIT,
                null,
                null,
                0,
                0,
                null,
                false,
                1_000_000_000_000L,
                100_000_000));
      for (int i = 0; i < 1000; i++) {
        Command command = history.next(i);
        mix.merge(command.type(), 1, Integer::sum);
        View before = book.view();
        if (command.type() != Type.NEW && !before.orders().isEmpty())
          assertTrue(
              before.orders().stream()
                  .anyMatch(
                      o ->
                          o.accountId().equals(command.accountId())
                              && o.orderId().equals(command.orderId())
                              && o.side() == command.side()));
        Result result = book.apply(++sequence, command);
        history.observed(command, result);
        history.verify(book.view());
      }
      assertEquals(Map.of(Type.NEW, 600, Type.CANCEL, 250, Type.REPLACE, 150), mix);
      assertEquals(400, history.activeTargets() + history.emptyTargets());
      assertTrue(
          history.activeTargets() > 200, "most mutation requests should target real live orders");
    }
  }
}
