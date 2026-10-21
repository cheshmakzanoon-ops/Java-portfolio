package io.github.cheshmakzanoon.portfolio.majority;

import static io.github.cheshmakzanoon.portfolio.majority.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MutationIdentityTest {
  @Test
  void timeoutThenOverloadCannotForgetOriginalMutation() {
    var session = new MutationIdentity("client");
    Key key = new Key(new byte[] {1});
    Command first = session.begin(Kind.PUT, key, new byte[] {2}, null);
    session.resolve(new Result("UNKNOWN_OUTCOME", null, -1));
    session.resolve(new Result("RESOURCE_EXHAUSTED", null, -1));
    assertThrows(
        IllegalStateException.class, () -> session.begin(Kind.PUT, key, new byte[] {3}, null));
    Command retry = session.begin(Kind.PUT, key, new byte[] {2}, null);
    assertEquals(first.sequence(), retry.sequence());
    assertArrayEquals(first.digest(), retry.digest());
    session.resolve(new Result("OK", new byte[] {2}, -1));
    assertEquals(2, session.begin(Kind.CAS, key, new byte[] {3}, new byte[] {2}).sequence());
    session.resolve(new Result("CAS_MISMATCH", new byte[] {9}, -1));
    assertEquals(3, session.begin(Kind.DELETE, key, null, null).sequence());
  }
}
