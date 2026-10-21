package io.github.cheshmakzanoon.portfolio.handoff;

import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

public final class RuntimeMain {
  private RuntimeMain() {}

  public static void main(String[] args) throws Exception {
    if (args.length == 0 || !java.util.Set.of("provider", "scheduler", "worker").contains(args[0]))
      throw new IllegalArgumentException("provider|scheduler|worker expected");
    String role = args[0];
    if (role.equals("worker") && args.length < 2)
      throw new IllegalArgumentException("worker identity required");
    try (RuntimeShutdown shutdown = new RuntimeShutdown()) {
      run(args, role);
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
    }
  }

  private static void run(String[] args, String role) throws Exception {
    if (role.equals("provider")) {
      try (FakeProvider provider =
          new FakeProvider(
              Integer.parseInt(System.getenv().getOrDefault("PROVIDER_PORT", "18201")),
              Path.of(System.getenv().getOrDefault("EFFECT_LOG", "target/provider-effects.log")))) {
        System.out.println("provider ready " + provider.port());
        new CountDownLatch(1).await();
      }
      return;
    }
    try (WorkflowStore store =
        new WorkflowStore(
            System.getenv("DB_URL"),
            System.getenv().getOrDefault("DB_USER", "handoff"),
            System.getenv("DB_PASSWORD"))) {
      store.migrate();
      if (role.equals("scheduler")) {
        if (args.length > 1) java.nio.file.Files.writeString(Path.of(args[1]), "READY");
        runScheduler(store);
      } else if (role.equals("worker")) {
        try (Worker worker =
            new Worker(
                store,
                URI.create(
                    System.getenv()
                        .getOrDefault("PROVIDER_URL", "http://127.0.0.1:18201/effects")))) {
          worker.run(System.getenv().getOrDefault("TENANT", "demo"), args[1]);
        }
      } else throw new IllegalArgumentException("provider|scheduler|worker expected");
    }
  }

  static void runScheduler(WorkflowStore store) throws InterruptedException {
    while (!Thread.currentThread().isInterrupted()) {
      try {
        WorkflowStore.SchedulerReport result = store.tickReport(256);
        for (WorkflowStore.SchedulerFailure failure : result.failures())
          System.err.println(
              "scheduler deferred " + failure.instance() + ": " + failure.category());
      } catch (PersistenceFailure unavailable) {
        if (!unavailable.retryable()) throw unavailable;
        System.err.println("scheduler storage unavailable; retrying");
        Thread.sleep(1000);
      }
      Thread.sleep(100);
    }
  }
}
