package io.github.cheshmakzanoon.portfolio.durableflow;

import static org.junit.jupiter.api.Assertions.*;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class FakeProviderTest {
  HttpRequest request(int port, String key, String body, String mode, long timeout) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/effects"))
        .header("Idempotency-Key", key)
        .header("X-Safety", "IDEMPOTENT_WITH_KEY")
        .header("X-Failure-Mode", mode)
        .timeout(Duration.ofMillis(timeout))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
  }

  @Test
  void independentEffectsSurviveAllProviderFaultModesAndRestart() throws Exception {
    Path log = Files.createTempDirectory("durableflow-provider-test").resolve("effects.log");
    try (HttpClient client = HttpClient.newHttpClient()) {
      try (FakeProvider provider = new FakeProvider(0, log)) {
        assertEquals(
            503,
            client
                .send(
                    request(provider.port(), "reject", "a", "reject", 10000),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
        assertEquals(0, provider.effectCount());
        String receipt =
            client
                .send(
                    request(provider.port(), "stable", "a", "normal", 10000),
                    HttpResponse.BodyHandlers.ofString())
                .body();
        assertEquals(
            receipt,
            client
                .send(
                    request(provider.port(), "stable", "a", "duplicate", 10000),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            409,
            client
                .send(
                    request(provider.port(), "stable", "changed", "normal", 10000),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
        assertEquals(1, provider.effectCount());
        assertThrows(
            java.io.IOException.class,
            () ->
                client.send(
                    request(provider.port(), "disconnected", "b", "disconnect", 10000),
                    HttpResponse.BodyHandlers.ofString()));
        assertEquals(2, provider.effectCount());
        assertEquals(
            200,
            client
                .send(
                    request(provider.port(), "disconnected", "b", "normal", 10000),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
        assertEquals(2, provider.effectCount());
        assertThrows(
            HttpTimeoutException.class,
            () ->
                client.send(
                    request(provider.port(), "delayed", "c", "delay", 500),
                    HttpResponse.BodyHandlers.ofString()));
        assertEquals(3, provider.effectCount());
      }
      try (FakeProvider restarted = new FakeProvider(0, log)) {
        assertEquals(
            200,
            client
                .send(
                    request(restarted.port(), "delayed", "c", "normal", 10000),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
        assertEquals(3, restarted.effectCount());
      }
    }
  }

  @Test
  void maximumProviderBodyIsAcceptedAndOversizeCannotCreateAnEffect(
      @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
    Path log = directory.resolve("bounded-effects.log");
    String parameters = "x".repeat(WorkflowLimits.PROVIDER_REQUEST_BYTES);
    String receipt;
    try (HttpClient client = HttpClient.newHttpClient()) {
      try (FakeProvider provider = new FakeProvider(0, log)) {
        var accepted =
            client.send(
                request(provider.port(), "maximum", parameters, "normal", 10000),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, accepted.statusCode());
        receipt = accepted.body();
        assertEquals(1, provider.effectCount());
        assertEquals(
            400,
            client
                .send(
                    request(provider.port(), "oversize", parameters + "x", "normal", 10000),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
        assertEquals(1, provider.effectCount());
      }
      try (FakeProvider restarted = new FakeProvider(0, log)) {
        assertEquals(
            receipt,
            client
                .send(
                    request(restarted.port(), "maximum", parameters, "normal", 10000),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(1, restarted.effectCount());
      }
    }
  }

  @Test
  void restartStreamsALogLargerThanTheProviderHeap(@org.junit.jupiter.api.io.TempDir Path directory)
      throws Exception {
    Path log = directory.resolve("large-effects.log"),
        output = directory.resolve("recovery-output.txt");
    String parameter =
        java.util.Base64.getEncoder()
            .encodeToString("reserve:{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    String call = "CALL\tnoise:task\t" + "x".repeat(2048) + "\tnormal\n";
    try (var writer = Files.newBufferedWriter(log)) {
      writer.write("EFFECT\trecovered:task\t" + parameter + "\teffect-retained\n");
      for (int i = 0; i < 20000; i++) writer.write(call);
    }
    assertTrue(Files.size(log) > 32L * 1024 * 1024);
    Process child =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx32m",
                "-cp",
                System.getProperty(
                    "surefire.test.class.path", System.getProperty("java.class.path")),
                RecoveryProbe.class.getName(),
                log.toString())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    try {
      assertTrue(
          child.waitFor(90, java.util.concurrent.TimeUnit.SECONDS),
          "bounded-heap provider recovery timed out");
      assertEquals(
          0,
          child.exitValue(),
          () -> {
            try {
              return Files.readString(output);
            } catch (java.io.IOException failure) {
              return failure.toString();
            }
          });
      assertTrue(Files.readString(output).contains("RECOVERED_ONE_EFFECT"));
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
        child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
      }
    }
  }

  public static final class RecoveryProbe {
    public static void main(String[] args) throws Exception {
      try (FakeProvider provider = new FakeProvider(0, Path.of(args[0]));
          HttpClient client = HttpClient.newHttpClient()) {
        var response =
            client.send(
                new FakeProviderTest()
                    .request(provider.port(), "recovered:task", "reserve:{}", "normal", 10000),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200
            || !response.body().equals("effect-retained")
            || provider.effectCount() != 1)
          throw new AssertionError("recovered receipt changed or effect repeated");
        System.out.println("RECOVERED_ONE_EFFECT");
      }
    }
  }
}
