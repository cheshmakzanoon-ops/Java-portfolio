package io.github.cheshmakzanoon.portfolio.quorumkv;

import static io.github.cheshmakzanoon.portfolio.quorumkv.Binary.*;
import static io.github.cheshmakzanoon.portfolio.quorumkv.Model.*;
import static io.github.cheshmakzanoon.portfolio.quorumkv.Raft.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** One protocol owner, one bounded disk queue; Netty threads only submit typed events. */
public final class NodeMain implements AutoCloseable {
  private record Waiting(Consumer<Result> response, long deadline) {}

  private final NodeExecutor execution;
  private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
  private final Journal journal;
  private final Raft raft;
  private final TlsTransport transport;
  private final Set<Integer> partitioned = new HashSet<>();
  private final Map<Long, Waiting> waiting = new HashMap<>();
  private long serial;
  private boolean closed;
  private volatile long snapshotLastNanos, snapshotCount, flushCount;

  public NodeMain(int id, Path directory, TlsTransport.Settings settings) throws Exception {
    Journal opened = new Journal(directory);
    TlsTransport initialized = null;
    try {
      raft = new Raft(id, new SecureRandom().nextInt(Integer.MAX_VALUE), opened.recovered());
      initialized = new TlsTransport(id, settings, this::receive);
      transport = initialized;
      execution =
          new NodeExecutor(
              error ->
                  System.err.println(
                      "FAIL_CLOSED "
                          + error.getClass().getSimpleName()
                          + ": "
                          + error.getMessage()));
      journal = opened;
    } catch (Exception | Error failure) {
      closeAdding(failure, initialized);
      closeAdding(failure, opened);
      closeAdding(failure, () -> timer.shutdownNow());
      throw failure;
    }
  }

  public void start() throws Exception {
    try {
      transport.start();
      timer.scheduleAtFixedRate(
          () ->
              submit(
                  () -> {
                    long now = System.nanoTime() / 1_000_000;
                    drive(new Tick(now));
                    var it = waiting.entrySet().iterator();
                    while (it.hasNext()) {
                      Waiting w = it.next().getValue();
                      if (w.deadline() < now) {
                        w.response().accept(new Result("UNKNOWN_OUTCOME", null, raft.leader()));
                        it.remove();
                      }
                    }
                  }),
          10,
          10,
          TimeUnit.MILLISECONDS);
    } catch (Exception | Error failure) {
      closeAdding(failure, this);
      throw failure;
    }
  }

  private boolean submit(Runnable action) {
    return execution.submit(action);
  }

  private void receive(Envelope envelope, Consumer<Result> response) {
    boolean accepted =
        submit(
            () -> {
              if (envelope.body() instanceof Peer peer) {
                if (!partitioned.contains(envelope.source()))
                  drive(new Receive(envelope.source(), peer.rpc()));
                return;
              }
              if (envelope.body() instanceof Admin admin) {
                admin(admin, response);
                return;
              }
              if (waiting.size() >= MAX_PENDING) {
                response.accept(new Result("RESOURCE_EXHAUSTED", null, raft.leader()));
                return;
              }
              long request = ++serial;
              waiting.put(request, new Waiting(response, System.nanoTime() / 1_000_000 + 1200));
              if (envelope.body() instanceof Mutation m) drive(new Mutate(request, m.command()));
              else if (envelope.body() instanceof Get g) drive(new Read(request, g.key()));
            });
    if (!accepted) response.accept(new Result("RESOURCE_EXHAUSTED", null, -1));
  }

  private void drive(Event event) {
    for (Effect effect : raft.accept(event)) {
      switch (effect) {
        case Persist p ->
            execution.persist(
                () -> {
                  boolean snapshot =
                      p.durable().snapshot().index() != journal.recovered().snapshot().index();
                  long begin = System.nanoTime();
                  journal.persist(p.durable());
                  flushCount++;
                  if (snapshot) {
                    snapshotLastNanos = System.nanoTime() - begin;
                    snapshotCount++;
                  }
                },
                () -> drive(new Persisted(p.token())));
        case Send s -> {
          if (!partitioned.contains(s.peer())) transport.send(s.peer(), s.rpc());
        }
        case Reply r -> {
          Waiting w = waiting.remove(r.request());
          if (w != null) w.response().accept(r.result());
        }
        case Applied ignored -> {}
      }
    }
    if (!raft.waitingForDisk() && raft.applied() - raft.durable().snapshot().index() >= 128)
      drive(new TakeSnapshot());
  }

  private void admin(Admin admin, Consumer<Result> response) {
    switch (admin.action()) {
      case "status" ->
          response.accept(
              new Result("OK", status().getBytes(StandardCharsets.UTF_8), raft.leader()));
      case "partition" -> {
        if (admin.peer() < 0 || admin.peer() > 2 || admin.peer() == raft.id()) {
          response.accept(new Result("INVALID_ADMIN", null, raft.leader()));
          return;
        }
        partitioned.add(admin.peer());
        response.accept(new Result("OK", null, raft.leader()));
      }
      case "heal" -> {
        partitioned.clear();
        response.accept(new Result("OK", null, raft.leader()));
      }
      case "snapshot" -> {
        drive(new TakeSnapshot());
        response.accept(new Result("OK", null, raft.leader()));
      }
      default -> response.accept(new Result("INVALID_ADMIN", null, raft.leader()));
    }
  }

  private String status() {
    try {
      Snapshot s = raft.view();
      Snapshot state = new Snapshot(0, 0, CLUSTER, s.data(), s.sessions());
      String digest =
          HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(Codec.snapshot(state)));
      return "node="
          + raft.id()
          + " role="
          + raft.role()
          + " term="
          + raft.term()
          + " commit="
          + raft.commit()
          + " applied="
          + raft.applied()
          + " snapshot="
          + raft.durable().snapshot().index()
          + " digest="
          + digest
          + " snapshots="
          + snapshotCount
          + " snapshotMicros="
          + snapshotLastNanos / 1000
          + " flushes="
          + flushCount
          + " disk="
          + raft.waitingForDisk()
          + " pending="
          + waiting.size();
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  @Override
  public synchronized void close() throws Exception {
    if (closed) return;
    closeResources(execution, timer, transport, journal);
    closed = true;
  }

  static void closeResources(
      NodeExecutor execution, ExecutorService timer, AutoCloseable transport, AutoCloseable journal)
      throws Exception {
    execution.beginClose();
    Throwable failure = closeAdding(null, () -> timer.shutdownNow());
    failure = closeAdding(failure, transport);
    Throwable executionFailure = closeAdding(null, execution);
    if (executionFailure == null) failure = closeAdding(failure, journal);
    else if (failure == null) failure = executionFailure;
    else if (failure != executionFailure) failure.addSuppressed(executionFailure);
    if (failure instanceof Exception exception) throw exception;
    if (failure instanceof Error error) throw error;
  }

  private static Throwable closeAdding(Throwable failure, AutoCloseable resource) {
    if (resource == null) return failure;
    try {
      resource.close();
    } catch (Exception | Error cleanup) {
      if (failure == null) return cleanup;
      if (failure != cleanup) failure.addSuppressed(cleanup);
    }
    return failure;
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 4)
      throw new IllegalArgumentException("nodeId dataDirectory tlsDirectory basePort");
    var node =
        new NodeMain(
            Integer.parseInt(args[0]),
            Path.of(args[1]),
            TlsTransport.Settings.load(Path.of(args[2]), Integer.parseInt(args[3])));
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    node.close();
                  } catch (Exception e) {
                    System.err.println("shutdown " + e.getMessage());
                  }
                }));
    node.start();
    new CountDownLatch(1).await();
  }
}
