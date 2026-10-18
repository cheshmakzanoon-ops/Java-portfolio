package io.github.cheshmakzanoon.portfolio.durableflow;

import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

public final class RuntimeMain {
  private RuntimeMain() {}

  public static void main(String[] args) throws Exception {
    String role = args[0];
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
    WorkflowStore store =
        new WorkflowStore(
            System.getenv("DB_URL"),
            System.getenv().getOrDefault("DB_USER", "durableflow"),
            System.getenv("DB_PASSWORD"));
    store.migrate();
    if (role.equals("scheduler")) {
      if (args.length > 1) java.nio.file.Files.writeString(Path.of(args[1]), "READY");
      while (!Thread.currentThread().isInterrupted()) {
        store.tick(256);
        Thread.sleep(100);
      }
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
