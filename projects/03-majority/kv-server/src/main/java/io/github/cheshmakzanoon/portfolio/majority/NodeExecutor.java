package io.github.cheshmakzanoon.portfolio.majority;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Owns protocol/disk execution and their terminal barrier; no protocol action runs after failure.
 */
final class NodeExecutor implements AutoCloseable {
  @FunctionalInterface
  interface Write {
    void run() throws IOException;
  }

  private enum State {
    OPEN,
    FAILED,
    CLOSING,
    CLOSED
  }

  private final AtomicReference<State> state = new AtomicReference<>(State.OPEN);
  private final ThreadPoolExecutor owner =
      new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(512));
  private final ExecutorService disk = Executors.newSingleThreadExecutor();
  private final Consumer<Throwable> failure;
  private final long shutdownMillis;
  private final AtomicReference<Throwable> firstFailure = new AtomicReference<>();

  NodeExecutor(Consumer<Throwable> failure) {
    this(failure, 5000);
  }

  NodeExecutor(Consumer<Throwable> failure, long shutdownMillis) {
    if (shutdownMillis < 1)
      throw new IllegalArgumentException("shutdown deadline must be positive");
    this.failure = failure;
    this.shutdownMillis = shutdownMillis;
  }

  boolean submit(Runnable action) {
    if (state.get() != State.OPEN) return false;
    try {
      owner.execute(guard(action));
      return true;
    } catch (RejectedExecutionException rejected) {
      return false;
    }
  }

  private Runnable guard(Runnable action) {
    return () -> {
      if (state.get() != State.OPEN) return;
      try {
        action.run();
      } catch (Throwable error) {
        fail(error);
      }
    };
  }

  void persist(Write write, Runnable acknowledgment) {
    if (state.get() != State.OPEN) return;
    try {
      disk.execute(
          () -> {
            try {
              // An admitted write finishes even when shutdown starts; its reply may become unknown.
              write.run();
              Runnable completion = guard(acknowledgment);
              while (state.get() == State.OPEN) {
                if (owner.getQueue().offer(completion, 50, TimeUnit.MILLISECONDS)) {
                  owner.prestartCoreThread();
                  return;
                }
              }
            } catch (Throwable error) {
              if (error instanceof InterruptedException) Thread.currentThread().interrupt();
              fail(error);
            }
          });
    } catch (RejectedExecutionException rejected) {
      fail(rejected);
    }
  }

  private void fail(Throwable error) {
    state.compareAndSet(State.OPEN, State.FAILED);
    if (firstFailure.compareAndSet(null, error)) failure.accept(error);
  }

  void beginClose() {
    state.updateAndGet(previous -> previous == State.CLOSED ? previous : State.CLOSING);
  }

  @Override
  public synchronized void close() throws IOException {
    if (state.get() == State.CLOSED) return;
    beginClose();
    // Protocol work stops before disk admission closes. Never release the journal under a writer.
    owner.shutdown();
    try {
      if (!owner.awaitTermination(shutdownMillis, TimeUnit.MILLISECONDS)) {
        owner.shutdownNow();
        throw new IOException("PROTOCOL_SHUTDOWN_TIMEOUT");
      }
      disk.shutdown();
      if (!disk.awaitTermination(shutdownMillis, TimeUnit.MILLISECONDS))
        throw new IOException("DISK_SHUTDOWN_TIMEOUT");
      owner.getQueue().clear();
      state.set(State.CLOSED);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("NODE_SHUTDOWN_INTERRUPTED", interrupted);
    }
  }
}
