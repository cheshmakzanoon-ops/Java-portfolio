package io.github.cheshmakzanoon.portfolio.quorumkv;

import java.io.IOException;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Stages a disposable identity and publishes credentials only after certificate creation succeeds.
 */
final class LocalTlsIdentity {
  @FunctionalInterface
  interface Generator {
    void generate(Path staging, Path log) throws Exception;
  }

  static void ensure(Path directory) throws Exception {
    ensure(directory, LocalTlsIdentity::generate);
  }

  static void ensure(Path directory, Generator generator) throws Exception {
    Files.createDirectories(directory);
    if (complete(directory)) return;
    try (var channel =
            java.nio.channels.FileChannel.open(
                directory.resolve("provision.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        var lock = channel.lock()) {
      if (complete(directory)) return;
      Path staging = Files.createTempDirectory(directory, ".identity-");
      try {
        Path existing = directory.resolve("auth.properties");
        if (Files.exists(existing)) {
          // Recover an interrupted older setup without changing its role credentials.
          TlsTransport.Settings.load(directory, 18300);
          Files.copy(existing, staging.resolve("auth.properties"));
        } else {
          Properties properties = new Properties();
          SecureRandom random = new SecureRandom();
          for (String name : List.of("peer.0", "peer.1", "peer.2", "client", "admin")) {
            byte[] secret = new byte[32];
            random.nextBytes(secret);
            properties.setProperty(name, HexFormat.of().formatHex(secret));
          }
          try (var out = Files.newOutputStream(staging.resolve("auth.properties"))) {
            properties.store(out, "Disposable local credentials; never commit");
          }
        }
        Files.setPosixFilePermissions(
            staging.resolve("auth.properties"),
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        generator.generate(staging, directory.resolve("openssl.log"));
        // Parse both the certificate and private key before exposing any completed identity.
        io.netty.handler.ssl.SslContextBuilder.forServer(
                staging.resolve("server.crt").toFile(), staging.resolve("server.key").toFile())
            .sslProvider(io.netty.handler.ssl.SslProvider.JDK)
            .build();
        for (String name : List.of("server.key", "server.crt", "auth.properties"))
          Files.move(
              staging.resolve(name),
              directory.resolve(name),
              StandardCopyOption.ATOMIC_MOVE,
              StandardCopyOption.REPLACE_EXISTING);
      } finally {
        try (var paths = Files.list(staging)) {
          for (Path path : paths.toList()) Files.deleteIfExists(path);
        }
        Files.deleteIfExists(staging);
      }
    }
  }

  private static boolean complete(Path directory) {
    return List.of("auth.properties", "server.crt", "server.key").stream()
        .allMatch(name -> Files.isRegularFile(directory.resolve(name)));
  }

  private static void generate(Path staging, Path log) throws Exception {
    Process openssl =
        new ProcessBuilder(
                "openssl",
                "req",
                "-x509",
                "-newkey",
                "rsa:2048",
                "-sha256",
                "-days",
                "30",
                "-noenc",
                "-keyout",
                staging.resolve("server.key").toString(),
                "-out",
                staging.resolve("server.crt").toString(),
                "-subj",
                "/CN=localhost",
                "-addext",
                "subjectAltName=DNS:localhost,IP:127.0.0.1")
            .redirectError(log.toFile())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start();
    await(openssl, 30, "OPENSSL_CERTIFICATE_GENERATION_FAILED");
  }

  static void await(Process process, int seconds, String failure) throws Exception {
    try {
      if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        if (!process.waitFor(5, TimeUnit.SECONDS))
          throw new IOException(failure + ": child did not terminate");
        throw new IOException(failure + ": deadline exceeded");
      }
      if (process.exitValue() != 0)
        throw new IOException(failure + ": exit " + process.exitValue());
    } catch (InterruptedException interrupted) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw interrupted;
    }
  }
}
