package io.github.cheshmakzanoon.portfolio.majority;

import static io.github.cheshmakzanoon.portfolio.majority.Model.*;

import java.util.*;

/** Deterministic committed state machine. All calls belong to the node owner thread. */
public final class Registers {
  private final NavigableMap<Key, byte[]> data = new TreeMap<>();
  private final Map<String, Session> sessions = new HashMap<>();
  private int retained;

  public Registers(Snapshot snapshot) {
    data.putAll(snapshot.data());
    sessions.putAll(snapshot.sessions());
    data.forEach((k, v) -> retained += k.bytes().length + v.length);
  }

  public Result apply(Command c) {
    Session s = sessions.get(c.client());
    if (s != null && c.sequence() == s.sequence())
      return Arrays.equals(s.digest(), c.digest())
          ? s.result()
          : new Result("IDENTITY_CONFLICT", null, -1);
    if (s != null && c.sequence() < s.sequence()) return new Result("STALE_SEQUENCE", null, -1);
    if (c.sequence() != (s == null ? 1 : s.sequence() + 1))
      return new Result("SEQUENCE_GAP", null, -1);
    if (s == null && sessions.size() >= MAX_CLIENTS)
      return new Result("RESOURCE_EXHAUSTED", null, -1);
    byte[] old = data.get(c.key());
    boolean matches = c.kind() != Kind.CAS || Arrays.equals(old, c.expected());
    byte[] next = c.kind() == Kind.DELETE ? null : c.value();
    int delta =
        (next == null ? 0 : next.length + c.key().bytes().length)
            - (old == null ? 0 : old.length + c.key().bytes().length);
    Result r;
    if (!matches) r = new Result("CAS_MISMATCH", old, -1);
    else if (retained + delta > MAX_DATA || next != null && old == null && data.size() >= MAX_KEYS)
      return new Result("RESOURCE_EXHAUSTED", null, -1);
    else {
      if (next == null) data.remove(c.key());
      else data.put(c.key(), next.clone());
      retained += delta;
      r = new Result("OK", c.kind() == Kind.CAS ? old : next, -1);
    }
    sessions.put(c.client(), new Session(c.sequence(), c.digest(), r));
    return r;
  }

  public Result get(Key key) {
    return new Result("OK", data.get(key), -1);
  }

  public Snapshot snapshot(long index, long term) {
    return new Snapshot(index, term, CLUSTER, data, sessions);
  }
}
