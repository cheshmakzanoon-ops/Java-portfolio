package io.github.cheshmakzanoon.portfolio.quorumkv;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalTlsIdentityTest {
  @TempDir Path directory;

  @Test
  void failedCertificateGenerationPublishesNoCredentialsAndCanBeRetried() throws Exception {
    assertThrows(
        IOException.class,
        () ->
            LocalTlsIdentity.ensure(
                directory,
                (staging, log) -> {
                  Files.writeString(staging.resolve("server.key"), "partial key");
                  throw new IOException("injected generator failure");
                }));
    assertFalse(Files.exists(directory.resolve("auth.properties")));
    assertFalse(Files.exists(directory.resolve("server.key")));
    LocalTlsIdentity.ensure(directory);
    byte[] credentials = Files.readAllBytes(directory.resolve("auth.properties"));
    Files.delete(directory.resolve("server.crt"));
    LocalTlsIdentity.ensure(directory);
    assertArrayEquals(credentials, Files.readAllBytes(directory.resolve("auth.properties")));
    try (TlsTransport transport =
        new TlsTransport(
            0, TlsTransport.Settings.load(directory, 18300), (request, response) -> {})) {}
  }

  @Test
  void stoppingAnAbsentClusterDoesNotCreateOrRotateCredentials() throws Exception {
    Path absent = directory.resolve("absent");
    try (ProcessCluster controller = ProcessCluster.controller(absent, 18300)) {}
    assertFalse(Files.exists(absent));
  }

  @Test
  void workloadRejectsInvalidBoundsAndPreservesExistingResults() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> Workload.run(directory, 18300, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> Workload.run(directory, 18300, 100001, 1));
    assertThrows(IllegalArgumentException.class, () -> Workload.run(directory, 18300, 100, 4));
    Files.writeString(directory.resolve("workload.csv"), "retained measurements");
    assertThrows(IOException.class, () -> Workload.run(directory, 18300, 100, 1));
    assertEquals("retained measurements", Files.readString(directory.resolve("workload.csv")));
    assertEquals(
        java.util.List.of("-Xmx512m", "-Xms32m", "-XX:ActiveProcessorCount=4"),
        Workload.safeFlags(
            java.util.List.of(
                "-Xmx512m",
                "-Xms32m",
                "-Dcredential=fixture-secret",
                "-javaagent:fixture-sensitive-config",
                "-XX:ActiveProcessorCount=4")));
  }

  @Test
  void timedOutHelperIsTerminatedBeforeFailureReturns() throws Exception {
    Process process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                Sleeper.class.getName())
            .redirectErrorStream(true)
            .start();
    try {
      assertThrows(IOException.class, () -> LocalTlsIdentity.await(process, 0, "TEST_TIMEOUT"));
      assertFalse(process.isAlive());
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
      }
    }
  }

  public static class Sleeper {
    public static void main(String[] args) throws Exception {
      Thread.sleep(30_000);
    }
  }
}
