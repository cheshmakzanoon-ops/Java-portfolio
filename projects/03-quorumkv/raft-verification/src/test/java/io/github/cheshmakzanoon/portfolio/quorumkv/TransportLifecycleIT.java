package io.github.cheshmakzanoon.portfolio.quorumkv;

import static io.github.cheshmakzanoon.portfolio.quorumkv.Binary.*;
import static io.github.cheshmakzanoon.portfolio.quorumkv.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.net.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TransportLifecycleIT {
  @TempDir Path directory;

  private int port() throws Exception {
    try (ServerSocket socket = new ServerSocket(0, 16, InetAddress.getLoopbackAddress())) {
      return Math.min(socket.getLocalPort(), 65532);
    }
  }

  @Test
  void concurrentClientAdmissionIsBoundedAndCloseResolvesOutstandingRequests() throws Exception {
    int port = port();
    try (ProcessCluster fixture = new ProcessCluster(directory, port)) {
      CountDownLatch accepted = new CountDownLatch(MAX_PENDING), exhausted = new CountDownLatch(44);
      AtomicInteger received = new AtomicInteger();
      AtomicBoolean respond = new AtomicBoolean();
      try (TlsTransport server =
              new TlsTransport(
                  0,
                  fixture.settings(),
                  (request, response) -> {
                    if (respond.get()) response.accept(new Result("OK", new byte[] {42}, -1));
                    else {
                      received.incrementAndGet();
                      accepted.countDown();
                    }
                  });
          KvClient client = new KvClient(fixture.settings(), "bounded");
          ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
        server.start();
        List<Future<Result>> calls = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < 300; i++)
          calls.add(
              callers.submit(
                  () -> {
                    start.await();
                    Result result = client.direct(0, new Get(new Key(new byte[] {1})), 30_000);
                    if (result.code().equals("RESOURCE_EXHAUSTED")) exhausted.countDown();
                    return result;
                  }));
        start.countDown();
        assertTrue(accepted.await(10, TimeUnit.SECONDS));
        assertTrue(exhausted.await(10, TimeUnit.SECONDS));
        assertEquals(MAX_PENDING, received.get());
        client.close();
        int rejected = 0, abandoned = 0;
        for (Future<Result> call : calls) {
          String code = call.get(3, TimeUnit.SECONDS).code();
          if (code.equals("RESOURCE_EXHAUSTED")) rejected++;
          else {
            assertEquals("UNAVAILABLE", code);
            abandoned++;
          }
        }
        assertEquals(44, rejected);
        assertEquals(MAX_PENDING, abandoned);
        assertEquals("UNAVAILABLE", client.direct(0, new Get(new Key(new byte[] {1})), 100).code());
        respond.set(true);
        try (KvClient fresh = new KvClient(fixture.settings(), "fresh")) {
          assertArrayEquals(
              new byte[] {42}, fresh.direct(0, new Get(new Key(new byte[] {1})), 2000).value());
        }
      }
    }
  }

  @Test
  void silentUnauthenticatedConnectionsExpireAndReleaseInboundCapacity() throws Exception {
    int port = port();
    try (ProcessCluster fixture = new ProcessCluster(directory, port);
        TlsTransport server =
            new TlsTransport(
                0,
                fixture.settings(),
                (request, response) -> response.accept(new Result("OK", null, -1)))) {
      server.start();
      List<Socket> stalled = new ArrayList<>();
      try {
        for (int i = 0; i < 64; i++) {
          Socket socket = new Socket("localhost", port);
          socket.setSoTimeout(6000);
          stalled.add(socket);
        }
        for (Socket socket : stalled) {
          try {
            assertEquals(-1, socket.getInputStream().read());
          } catch (SocketException reset) {
            assertTrue(socket.isConnected());
          }
        }
        try (KvClient client = new KvClient(fixture.settings(), "after-expiry")) {
          assertEquals("OK", client.direct(0, new Get(new Key(new byte[] {1})), 2000).code());
        }
      } finally {
        for (Socket socket : stalled) socket.close();
      }
    }
  }
}
