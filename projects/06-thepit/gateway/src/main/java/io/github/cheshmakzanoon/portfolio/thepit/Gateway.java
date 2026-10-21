package io.github.cheshmakzanoon.portfolio.thepit;

import static io.github.cheshmakzanoon.portfolio.thepit.Model.*;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Queue publication and CompletableFuture completion establish producer/writer/reader visibility.
 */
public final class Gateway implements AutoCloseable {
  private record Pending(Command command, CompletableFuture<Result> response) {}

  private record Committed(Command command, Result result) {}

  private final Book book = new Book();
  private final ConcurrentMap<String, Committed> committed = new ConcurrentHashMap<>();
  private final ConcurrentLinkedQueue<Execution> executions = new ConcurrentLinkedQueue<>();
  private final Journal journal;
  private final BlockingQueue<Pending> queue;
  private final Map<String, Pending> pending = new HashMap<>();
  private final int maxCommands;
  private int admitted;
  private final Thread writer;
  private final Journal.Fault fault;
  private volatile View view;
  private volatile boolean accepting = true, closed;
  private volatile Throwable failure;

  public Gateway(Path path, String runId, int capacity, int maxCommands) throws IOException {
    this(path, runId, capacity, maxCommands, b -> {});
  }

  public Gateway(Path path, String runId, int capacity, int maxCommands, Journal.Fault fault)
      throws IOException {
    if (capacity < 1 || maxCommands < 1 || maxCommands > 1_100_000)
      throw new IllegalArgumentException();
    this.maxCommands = maxCommands;
    this.fault = fault;
    queue = new ArrayBlockingQueue<>(capacity);
    journal = new Journal(path, runId, fault);
    try {
      List<Journal.Entry> recovered = journal.entries();
      Checkpoint checkpoint = journal.recoveredCheckpoint();
      if (checkpoint != null) {
        try {
          book.restore(
              checkpoint,
              recovered.subList(0, Math.toIntExact(checkpoint.view().sequence())).stream()
                  .map(Journal.Entry::command)
                  .toList());
        } catch (IllegalArgumentException invalidCheckpoint) {
          journal.discardSnapshot();
          checkpoint = null;
        }
      }
      if (checkpoint != null) {
        executions.addAll(checkpoint.view().executions());
      }
      long restored = checkpoint == null ? 0 : checkpoint.view().sequence();
      for (Journal.Entry entry : recovered) {
        Result result;
        if (entry.sequence() <= restored)
          result = checkpoint.view().results().get(entry.command().key());
        else {
          result = book.apply(entry.sequence(), entry.command());
          executions.addAll(result.executions());
        }
        committed.put(entry.command().key(), new Committed(entry.command(), result));
      }
    } catch (RuntimeException e) {
      IOException failure = new IOException("invalid replay", e);
      closeAfterReplayFailure(failure);
      throw failure;
    } catch (Error failure) {
      closeAfterReplayFailure(failure);
      throw failure;
    }
    admitted = journal.entries().size();
    view = book.publicView();
    writer = Thread.ofPlatform().name("thepit-single-writer").start(this::run);
  }

  private void closeAfterReplayFailure(Throwable primary) {
    try {
      journal.close();
    } catch (IOException | RuntimeException | Error cleanup) {
      if (cleanup != primary) primary.addSuppressed(cleanup);
    }
  }

  public synchronized CompletableFuture<Result> submit(String identity, Command c) {
    c.validate();
    if (!Objects.equals(identity, c.accountId()))
      throw new SecurityException("account ownership required");
    Committed previous = committed.get(c.key());
    if (previous != null)
      return CompletableFuture.completedFuture(
          previous.command().equals(c)
              ? previous.result()
              : new Result(previous.result().sequence(), "CONFLICT", c.hash(), List.of()));
    Pending existing = pending.get(c.key());
    if (existing != null)
      return existing.command().equals(c)
          ? existing.response().copy()
          : CompletableFuture.completedFuture(new Result(0, "CONFLICT", c.hash(), List.of()));
    if (!accepting)
      return CompletableFuture.failedFuture(
          new IllegalStateException("RECOVERY_REQUIRED_OR_CLOSED", failure));
    if (admitted >= maxCommands)
      return CompletableFuture.failedFuture(new RejectedExecutionException("RUN_CAP"));
    if (pending.size() >= queue.remainingCapacity() + queue.size())
      return CompletableFuture.failedFuture(new RejectedExecutionException("OVERLOADED_RETRYABLE"));
    Pending next = new Pending(c, new CompletableFuture<>());
    pending.put(c.key(), next);
    if (!queue.offer(next)) {
      pending.remove(c.key());
      next.response().completeExceptionally(new RejectedExecutionException("OVERLOADED_RETRYABLE"));
    } else admitted++;
    return next.response().copy();
  }

  private void run() {
    try {
      while (!closed || !queue.isEmpty()) {
        Pending next = queue.poll(50, TimeUnit.MILLISECONDS);
        if (next == null) continue;
        long sequence = view.sequence() + 1;
        journal.appendAndForce(sequence, next.command());
        Result result = book.apply(sequence, next.command());
        committed.put(next.command().key(), new Committed(next.command(), result));
        executions.addAll(result.executions());
        view = book.publicView();
        fault.at("AFTER_APPLY");
        synchronized (this) {
          pending.remove(next.command().key());
          notifyAll();
        }
        next.response().complete(result);
      }
    } catch (Throwable e) {
      List<Pending> failed;
      synchronized (this) {
        failure = e;
        accepting = false;
        failed = List.copyOf(pending.values());
        pending.clear();
        queue.clear();
        notifyAll();
      }
      failed.forEach(
          p ->
              p.response()
                  .completeExceptionally(new IllegalStateException("RECOVERY_REQUIRED", e)));
    }
  }

  public View read(long minimumSequence, Duration timeout)
      throws InterruptedException, TimeoutException {
    long deadline = System.nanoTime() + timeout.toNanos();
    synchronized (this) {
      while (view.sequence() < minimumSequence) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new TimeoutException("minimum sequence not applied");
        if (failure != null) throw new IllegalStateException("RECOVERY_REQUIRED", failure);
        TimeUnit.NANOSECONDS.timedWait(this, remaining);
      }
      return view;
    }
  }

  public Result result(String identity, String commandId) {
    Committed value = committed.get(identity + ":" + commandId);
    return value == null ? null : value.result();
  }

  public View view() {
    return view;
  }

  public synchronized View fullView() {
    if (!pending.isEmpty()) throw new IllegalStateException("full state requires idle gateway");
    return book.view();
  }

  public java.util.List<Execution> executions(
      String account, int offset, int limit, long maximumSequence) {
    return executions.stream()
        .filter(
            e ->
                e.sequence() <= maximumSequence
                    && (e.makerAccount().equals(account) || e.takerAccount().equals(account)))
        .skip(offset)
        .limit(limit)
        .toList();
  }

  public boolean healthy() {
    return accepting && failure == null;
  }

  public int queueDepth() {
    return queue.size();
  }

  public void snapshot() throws IOException {
    synchronized (this) {
      if (!healthy())
        throw new IllegalStateException("snapshot requires a healthy gateway", failure);
      if (!pending.isEmpty()) throw new IllegalStateException("snapshot requires idle gateway");
      journal.snapshot(book.checkpoint());
    }
  }

  public void close() throws IOException {
    if (Thread.currentThread() == writer)
      throw new IllegalStateException("writer cannot close itself");
    synchronized (this) {
      accepting = false;
      closed = true;
    }
    try {
      writer.join(10_000);
      if (writer.isAlive()) throw new IOException("writer failed to stop");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(e);
    }
    journal.close();
  }
}
