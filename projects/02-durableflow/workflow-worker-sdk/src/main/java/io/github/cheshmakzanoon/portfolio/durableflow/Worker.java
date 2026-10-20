package io.github.cheshmakzanoon.portfolio.durableflow;

import io.github.cheshmakzanoon.portfolio.durableflow.Data.*;
import io.github.cheshmakzanoon.portfolio.durableflow.WorkflowStore.Claim;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fixed destination and activity registry; caller data cannot supply executable code or URLs. */
public final class Worker implements AutoCloseable {
  private final WorkflowStore store;
  private final ProviderClient provider;
  private final ThreadPoolExecutor pool =
      new ThreadPoolExecutor(
          4,
          4,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(4),
          Thread.ofVirtual().factory(),
          new ThreadPoolExecutor.AbortPolicy());
  private final ScheduledExecutorService heartbeats = Executors.newScheduledThreadPool(1);
  private final AtomicBoolean running = new AtomicBoolean(true);

  public Worker(WorkflowStore store, URI provider) {
    this.store = Objects.requireNonNull(store, "store");
    this.provider = new ProviderClient(provider);
  }

  public List<Value> execute(Claim claim, String mode) throws Exception {
    if (!Definition.ACTIVITIES.contains(claim.activity()))
      throw new IllegalArgumentException("activity unavailable");
    store.dispatch(claim); // Authorization commits before any provider I/O.
    if (claim.semantics().equals("1") && claim.safety() == Definition.Safety.PURE)
      return List.of(Value.text("pure-result"));
    TaskInput typed =
        Set.of("2", "3").contains(claim.semantics())
            ? new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(claim.parameters(), TaskInput.class)
            : null;
    if (claim.safety() == Definition.Safety.PURE) {
      List<Value> result =
          switch (claim.activity()) {
            case "pure" -> typed.arguments();
            case "sum" -> {
              long sum = 0;
              for (Value value : typed.arguments()) {
                if (value.type() != Definition.Type.INTEGER)
                  throw new IllegalArgumentException("sum input type");
                sum = Math.addExact(sum, (Long) value.data());
              }
              yield List.of(Value.integer(sum));
            }
            case "not" -> {
              if (typed.arguments().size() != 1
                  || typed.arguments().getFirst().type() != Definition.Type.BOOLEAN)
                throw new IllegalArgumentException("not input type");
              yield List.of(Value.bool(!((Boolean) typed.arguments().getFirst().data())));
            }
            default -> throw new IllegalArgumentException("pure activity registry mismatch");
          };
      Data.activityResult(claim.activity(), claim.outputType(), typed.arguments().size(), result);
      return result;
    }

    String key = claim.instance() + ":" + claim.task();
    return List.of(
        Value.text(
            provider.execute(
                key, claim.safety(), mode, claim.activity() + ":" + claim.parameters())));
  }

  public void run(String tenant, String id) throws InterruptedException {
    long claimRetryMillis = 100;
    while (running.get() && !Thread.currentThread().isInterrupted()) {
      int capacity = 4 - pool.getActiveCount() - pool.getQueue().size();
      if (capacity > 0) {
        List<Claim> claims;
        try {
          claims = store.claim(tenant, id, Math.min(capacity, 4), 10);
          claimRetryMillis = 100;
        } catch (PersistenceFailure unavailable) {
          if (!unavailable.retryable()) throw unavailable;
          // No claim was acknowledged. Never dispatch from a speculative lease;
          // a lost commit acknowledgement is recovered through normal expiry.
          System.err.println("claim storage unavailable; retrying");
          Thread.sleep(claimRetryMillis);
          claimRetryMillis = Math.min(1000, claimRetryMillis * 2);
          continue;
        }
        for (Claim claim : claims) {
          try {
            pool.execute(
                () -> {
                  try {
                    ScheduledFuture<?> heartbeat =
                        heartbeats.scheduleAtFixedRate(
                            () -> {
                              try {
                                store.heartbeat(claim, 10);
                              } catch (RuntimeException stale) {
                                System.err.println("heartbeat rejected");
                              }
                            },
                            2,
                            2,
                            TimeUnit.SECONDS);
                    try {
                      String mode = "normal";
                      if (Set.of("2", "3").contains(claim.semantics())
                          && claim.task().matches("a[0-7]")) {
                        TaskInput input =
                            new com.fasterxml.jackson.databind.ObjectMapper()
                                .readValue(claim.parameters(), TaskInput.class);
                        Value ordinalValue = input.execution().context().get("ordinal");
                        int ordinal =
                            ordinalValue == null ? -1 : Math.toIntExact((Long) ordinalValue.data());
                        if (ordinal >= 0
                            && Math.floorMod(
                                    ordinal * 8 + Integer.parseInt(claim.task().substring(1)), 5)
                                == 0
                            && claim.attempt() == 1) mode = "transient";
                      }
                      store.complete(claim, execute(claim, mode));
                    } finally {
                      heartbeat.cancel(false);
                    }
                  } catch (Exception e) {
                    try {
                      store.fail(claim, e instanceof ProviderRejectedException, "provider failure");
                    } catch (IllegalStateException stale) {
                      System.err.println("completion rejected: " + stale.getMessage());
                    }
                  }
                });
          } catch (RejectedExecutionException stopped) {
            // The claim transaction may finish after shutdown closed admission. No task
            // was submitted and no dispatch authorization exists for this worker slot.
            try {
              store.fail(claim, true, "worker stopped before dispatch");
            } catch (IllegalStateException stale) {
              System.err.println("abandoned claim is stale");
            }
          }
        }
      }
      Thread.sleep(50);
    }
  }

  public synchronized void close() {
    running.set(false);
    if (pool.isTerminated()) return;
    boolean interrupted = Thread.interrupted();
    pool.shutdown();
    try {
      // Keep heartbeats alive while accepted work records its outcome. Closing the
      // provider or lease scheduler first can turn a successful effect into ambiguity.
      if (!pool.awaitTermination(15, TimeUnit.SECONDS)) {
        pool.shutdownNow();
        pool.awaitTermination(5, TimeUnit.SECONDS);
      }
    } catch (InterruptedException stopping) {
      interrupted = true;
      pool.shutdownNow();
    } finally {
      heartbeats.shutdownNow();
      provider.close();
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  public static final class ProviderRejectedException extends Exception {}
}
