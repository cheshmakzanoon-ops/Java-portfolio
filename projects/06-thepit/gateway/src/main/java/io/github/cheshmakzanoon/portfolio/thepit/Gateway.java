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

  /** One volatile reference keeps a view and the statistics of the same sequence together. */
  private record Published(View view, MarketData.Stats stats) {}

  /** Append-only per-account fills in sequence order; one writer, many short readers. */
  private static final class AccountExecutions {
    private final List<Execution> fills = new ArrayList<>();

    synchronized void add(Execution execution) {
      fills.add(execution);
    }

    synchronized List<Execution> page(long offset, int limit, long maximumSequence) {
      // The comparator never reports equality, so the result encodes the insertion point: the
      // number of leading fills whose sequence does not exceed the cutoff.
      int visible =
          -Collections.binarySearch(
                  fills, null, (fill, ignored) -> fill.sequence() <= maximumSequence ? -1 : 1)
              - 1;
      long from = Math.min(offset, visible), to = Math.min(visible, from + limit);
      return List.copyOf(fills.subList((int) from, (int) to));
    }
  }

  private final Book book = new Book();
  private final ConcurrentMap<String, Committed> committed = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, AccountExecutions> accountExecutions =
      new ConcurrentHashMap<>();
  private final MarketData.Tracker tracker = new MarketData.Tracker();
  private final MarketData.Tape tape = new MarketData.Tape();
  private final Journal journal;
  private final BlockingQueue<Pending> queue;
  private final Map<String, Pending> pending = new HashMap<>();
  private final int maxCommands;
  private int admitted;
  private final Thread writer;
  private final Journal.Fault fault;
  private volatile Published published;
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
      long restored = checkpoint == null ? 0 : checkpoint.view().sequence();
      for (Journal.Entry entry : recovered) {
        Result result;
        if (entry.sequence() <= restored)
          result = checkpoint.view().results().get(entry.command().key());
        else result = book.apply(entry.sequence(), entry.command());
        committed.put(entry.command().key(), new Committed(entry.command(), result));
        record(entry.command(), result);
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
    published = new Published(book.publicView(), tracker.snapshot());
    writer = Thread.ofPlatform().name("thepit-single-writer").start(this::run);
  }

  /** Feeds fill-derived read models; called only by the constructor and the single writer. */
  private void record(Command command, Result result) {
    for (Execution execution : result.executions()) {
      accountExecutions
          .computeIfAbsent(execution.makerAccount(), account -> new AccountExecutions())
          .add(execution);
      accountExecutions
          .computeIfAbsent(execution.takerAccount(), account -> new AccountExecutions())
          .add(execution);
      tracker.accept(execution);
      tape.accept(result.sequence(), command.side(), execution);
    }
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
        long sequence = published.view().sequence() + 1;
        journal.appendAndForce(sequence, next.command());
        Result result = book.apply(sequence, next.command());
        committed.put(next.command().key(), new Committed(next.command(), result));
        record(next.command(), result);
        published = new Published(book.publicView(), tracker.snapshot());
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
      while (published.view().sequence() < minimumSequence) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new TimeoutException("minimum sequence not applied");
        if (failure != null) throw new IllegalStateException("RECOVERY_REQUIRED", failure);
        TimeUnit.NANOSECONDS.timedWait(this, remaining);
      }
      return published.view();
    }
  }

  public Result result(String identity, String commandId) {
    Committed value = committed.get(identity + ":" + commandId);
    return value == null ? null : value.result();
  }

  public View view() {
    return published.view();
  }

  /** Top of book, depth and statistics as of one applied sequence; never blocks the writer. */
  public MarketData.Market market(int depth) {
    Published current = published;
    return MarketData.market(current.view(), current.stats(), depth);
  }

  /** Anonymous recent fills, newest first; the cutoff sequence bounds every returned fill. */
  public record TradeTape(long lastAppliedSequence, List<MarketData.Trade> trades) {}

  public TradeTape trades(int limit) {
    long applied = published.view().sequence();
    return new TradeTape(applied, tape.recent(limit, applied));
  }

  public synchronized View fullView() {
    if (!pending.isEmpty()) throw new IllegalStateException("full state requires idle gateway");
    return book.view();
  }

  public java.util.List<Execution> executions(
      String account, int offset, int limit, long maximumSequence) {
    AccountExecutions fills = accountExecutions.get(account);
    return fills == null ? List.of() : fills.page(Math.max(0, offset), limit, maximumSequence);
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
