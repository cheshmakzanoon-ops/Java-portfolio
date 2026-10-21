package io.github.cheshmakzanoon.portfolio.majority;

import static io.github.cheshmakzanoon.portfolio.majority.Model.*;
import static io.github.cheshmakzanoon.portfolio.majority.Raft.*;

import java.util.*;

/** Virtual time, network and stable disks are separate. No sleep or real I/O. */
public final class Simulator {
  private record Scheduled(long time, long order, int node, long boot, Event event, Durable write)
      implements Comparable<Scheduled> {
    public int compareTo(Scheduled o) {
      int t = Long.compare(time, o.time);
      return t == 0 ? Long.compare(order, o.order) : t;
    }
  }

  private final Random random;
  private final PriorityQueue<Scheduled> queue = new PriorityQueue<>();
  private final Raft[] nodes = new Raft[3];
  private final Durable[] disks = {Durable.empty(), Durable.empty(), Durable.empty()};
  private final long[] boots = {1, 1, 1}, highestApplied = new long[3];
  private final boolean[] paused = new boolean[3];
  private final boolean[][] blocked = new boolean[3][3];
  private final Map<Long, Integer> leaders = new HashMap<>();
  private final Map<Long, byte[]> committed = new HashMap<>(), results = new HashMap<>();
  private final ArrayDeque<String> trace = new ArrayDeque<>();
  public final Map<Long, Result> replies = new HashMap<>();
  private long now, order, request;
  private double loss = .08;

  public Simulator(long seed) {
    random = new Random(seed);
    for (int i = 0; i < 3; i++) nodes[i] = new Raft(i, boots[i], disks[i]);
  }

  public void reliable() {
    loss = 0;
    for (boolean[] row : blocked) Arrays.fill(row, false);
    Arrays.fill(paused, false);
    for (int i = 0; i < 3; i++) if (nodes[i] == null) restart(i);
  }

  public void partition(int node, boolean isolate) {
    for (int p = 0; p < 3; p++) blocked[node][p] = blocked[p][node] = isolate;
    record("partition " + node + " " + isolate);
  }

  public void pause(int node, boolean pause) {
    paused[node] = pause;
    record("pause " + node + " " + pause);
  }

  public void crash(int node) {
    nodes[node] = null;
    record("crash " + node);
  }

  public void restart(int node) {
    if (nodes[node] == null) {
      nodes[node] = new Raft(node, ++boots[node], disks[node]);
      record("restart " + node);
    }
  }

  public long mutate(int node, Command command) {
    long r = ++request;
    deliver(node, new Mutate(r, command));
    return r;
  }

  public long read(int node, Key key) {
    long r = ++request;
    deliver(node, new Read(r, key));
    return r;
  }

  public Raft node(int i) {
    return nodes[i];
  }

  public long now() {
    return now;
  }

  public int leader() {
    for (int i = 0; i < 3; i++) if (nodes[i] != null && nodes[i].role() == Role.LEADER) return i;
    return -1;
  }

  public void run(long duration) {
    long stop = now + duration;
    while (now < stop) {
      now += 5;
      for (int i = 0; i < 3; i++) if (nodes[i] != null && !paused[i]) deliver(i, new Tick(now));
      while (!queue.isEmpty() && queue.peek().time <= now) {
        Scheduled s = queue.remove();
        if (nodes[s.node] == null || s.boot != boots[s.node]) continue;
        if (paused[s.node]) {
          queue.add(new Scheduled(now + 10, ++order, s.node, s.boot, s.event, s.write));
          continue;
        }
        if (s.write != null) disks[s.node] = s.write;
        else deliver(s.node, s.event);
      }
      invariants();
    }
  }

  public void faultSchedule(int actions) {
    for (int n = 0; n < actions; n++) {
      int node = random.nextInt(3);
      switch (random.nextInt(12)) {
        case 0 -> partition(node, true);
        case 1 -> partition(node, false);
        case 2 -> crash(node);
        case 3 -> restart(node);
        case 4 -> pause(node, true);
        case 5 -> pause(node, false);
        case 6 -> {
          if (nodes[node] != null) deliver(node, new TakeSnapshot());
        }
        case 7, 8 -> {
          if (nodes[node] != null)
            mutate(
                node,
                new Command(
                    "schedule-" + n,
                    1,
                    Kind.PUT,
                    new Key(new byte[] {(byte) node}),
                    new byte[] {(byte) n},
                    null));
        }
        case 9 -> {
          if (nodes[node] != null) read(node, new Key(new byte[] {0}));
        }
        default -> {}
      }
      run(10 + random.nextInt(30));
    }
    reliable();
    run(5000);
    if (leader() < 0) throw new AssertionError("NO_PROGRESS_AFTER_HEAL " + trace());
    Key recoveryKey = new Key(new byte[] {127});
    long resumed =
        mutate(leader(), new Command("recovery", 1, Kind.PUT, recoveryKey, new byte[] {42}, null));
    run(1000);
    if (!replies.containsKey(resumed) || !replies.get(resumed).code().equals("OK"))
      throw new AssertionError("NO_COMMIT_AFTER_HEAL " + trace());
    for (Raft node : nodes)
      if (!Arrays.equals(new byte[] {42}, node.view().data().get(recoveryKey)))
        throw new AssertionError("NO_CONVERGENCE_AFTER_HEAL " + trace());
  }

  public String trace() {
    return String.join("\n", trace);
  }

  private void record(String line) {
    if (trace.size() == 300) trace.removeFirst();
    trace.add(now + " " + line);
  }

  private void deliver(int node, Event event) {
    if (nodes[node] == null) return;
    record(node + " " + event.getClass().getSimpleName());
    for (Effect effect : nodes[node].accept(event)) {
      switch (effect) {
        case Persist p -> {
          long delay = 1 + random.nextInt(12);
          queue.add(new Scheduled(now + delay, ++order, node, boots[node], null, p.durable()));
          queue.add(
              new Scheduled(
                  now + delay + 1, ++order, node, boots[node], new Persisted(p.token()), null));
        }
        case Send s -> {
          if (blocked[node][s.peer()] || random.nextDouble() < loss) continue;
          int duplicates = random.nextInt(10) == 0 ? 2 : 1;
          for (int d = 0; d < duplicates; d++)
            queue.add(
                new Scheduled(
                    now + 1 + random.nextInt(40),
                    ++order,
                    s.peer(),
                    boots[s.peer()],
                    new Receive(node, s.rpc()),
                    null));
        }
        case Reply r -> replies.put(r.request(), r.result());
        case Applied a -> {
          byte[] identity =
              Codec.encode(
                  out -> {
                    out.writeLong(a.term());
                    Codec.writeCommand(out, a.command());
                  });
          agree(committed, a.index(), identity, "COMMITTED_PREFIX");
          agree(
              results,
              a.index(),
              Codec.encode(out -> Codec.result(out, a.result())),
              "APPLICATION_RESULT");
        }
      }
    }
    invariants();
  }

  private static void agree(Map<Long, byte[]> seen, long index, byte[] value, String invariant) {
    byte[] previous = seen.putIfAbsent(index, value);
    if (previous != null && !Arrays.equals(previous, value))
      throw new AssertionError(invariant + " index=" + index);
  }

  private void invariants() {
    for (int i = 0; i < 3; i++) {
      Raft node = nodes[i];
      if (node == null) continue;
      if (node.role() == Role.LEADER) {
        Integer previous = leaders.putIfAbsent(node.term(), i);
        if (previous != null && previous != i) throw new AssertionError("ELECTION_SAFETY");
      }
      if (node.applied() > node.commit() || node.applied() < highestApplied[i])
        throw new AssertionError("MONOTONIC_APPLICATION");
      highestApplied[i] = node.applied();
      for (Entry e : node.durable().log())
        if (e.index() <= node.commit())
          agree(
              committed,
              e.index(),
              Codec.encode(
                  out -> {
                    out.writeLong(e.term());
                    Codec.writeCommand(out, e.command());
                  }),
              "COMMITTED_PREFIX");
    }
  }
}
