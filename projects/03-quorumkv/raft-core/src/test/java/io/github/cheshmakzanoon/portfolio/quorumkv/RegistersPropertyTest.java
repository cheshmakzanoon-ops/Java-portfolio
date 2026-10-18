package io.github.cheshmakzanoon.portfolio.quorumkv;

import static io.github.cheshmakzanoon.portfolio.quorumkv.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import net.jqwik.api.*;

class RegistersPropertyTest {
  @Property(tries = 200, seed = "42")
  void distinctKeysCommuteAndRetriesNeverRepeatMutation(@ForAll byte first, @ForAll byte second) {
    Command a = new Command("a", 1, Kind.PUT, new Key(new byte[] {1}), new byte[] {first}, null);
    Command b = new Command("b", 1, Kind.PUT, new Key(new byte[] {2}), new byte[] {second}, null);
    Registers x = new Registers(Snapshot.empty()), y = new Registers(Snapshot.empty());
    Result result = x.apply(a);
    x.apply(b);
    assertEquals(result, x.apply(a));
    y.apply(b);
    y.apply(a);
    assertArrayEquals(Codec.snapshot(x.snapshot(2, 1)), Codec.snapshot(y.snapshot(2, 1)));
  }
}
