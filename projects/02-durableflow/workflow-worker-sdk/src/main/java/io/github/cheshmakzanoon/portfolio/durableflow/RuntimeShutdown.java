package io.github.cheshmakzanoon.portfolio.durableflow;

/** SIGTERM interrupts the owner and allows its try-with-resources cleanup to finish. */
final class RuntimeShutdown implements AutoCloseable {
  private final Thread hook;

  RuntimeShutdown() {
    Thread owner = Thread.currentThread();
    hook =
        new Thread(
            () -> {
              owner.interrupt();
              try {
                owner.join(30_000);
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
            },
            "durableflow-shutdown");
    Runtime.getRuntime().addShutdownHook(hook);
  }

  @Override
  public void close() {
    try {
      Runtime.getRuntime().removeShutdownHook(hook);
    } catch (IllegalStateException shuttingDown) {
      /* The executing hook is waiting for this owner. */
    }
  }
}
