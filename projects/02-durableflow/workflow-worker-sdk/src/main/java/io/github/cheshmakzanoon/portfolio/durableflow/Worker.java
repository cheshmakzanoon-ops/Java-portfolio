package io.github.cheshmakzanoon.portfolio.durableflow;

import io.github.cheshmakzanoon.portfolio.durableflow.Data.*;
import io.github.cheshmakzanoon.portfolio.durableflow.WorkflowStore.Claim;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fixed destination and activity registry; caller data cannot supply executable code or URLs. */
public final class Worker implements AutoCloseable {
  private final WorkflowStore store;
  private final URI provider;
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(2))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
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
    if (!"http".equals(provider.getScheme())
        || provider.getHost() == null
        || !provider.getPath().equals("/effects"))
      throw new IllegalArgumentException("configured provider endpoint required");
    this.store = store;
    this.provider = provider;
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
    HttpRequest request =
        HttpRequest.newBuilder(provider)
            .timeout(Duration.ofSeconds(3))
            .header("Idempotency-Key", key)
            .header("X-Safety", claim.safety().name())
            .header("X-Failure-Mode", mode)
            .POST(HttpRequest.BodyPublishers.ofString(claim.activity() + ":" + claim.parameters()))
            .build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() == 409)
      throw new IllegalArgumentException("provider parameter conflict");
    if (response.statusCode() >= 400) throw new ProviderRejectedException();
    return List.of(Value.text(response.body()));
  }

  public void run(String tenant, String id) throws InterruptedException {
    while (running.get() && !Thread.currentThread().isInterrupted()) {
      int capacity = 4 - pool.getActiveCount() - pool.getQueue().size();
      if (capacity > 0)
        for (Claim claim : store.claim(tenant, id, Math.min(capacity, 4), 10))
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
      Thread.sleep(50);
    }
  }

  public void close() {
    running.set(false);
    pool.shutdownNow();
    heartbeats.shutdownNow();
    client.close();
  }

  public static final class ProviderRejectedException extends Exception {}
}
