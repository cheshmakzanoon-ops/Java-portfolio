package io.github.cheshmakzanoon.portfolio.quorumkv;

import java.io.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Real OS processes, TLS and independent fsynced directories. */
public final class ProcessCluster implements AutoCloseable {
  private final Path directory;
  private final int port;
  private final Process[] processes = new Process[3];

  public ProcessCluster(Path directory, int port) throws Exception {
    this.directory = directory.toAbsolutePath();
    this.port = port;
    Files.createDirectories(directory);
    Path tls = directory.resolve("tls");
    Files.createDirectories(tls);
    if (!Files.exists(tls.resolve("auth.properties"))) {
      var random = new SecureRandom();
      var p = new Properties();
      for (String name : List.of("peer.0", "peer.1", "peer.2", "client", "admin")) {
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        p.setProperty(name, HexFormat.of().formatHex(secret));
      }
      try (var out = Files.newOutputStream(tls.resolve("auth.properties"))) {
        p.store(out, "Disposable local credentials; never commit");
      }
      Files.setPosixFilePermissions(
          tls.resolve("auth.properties"),
          java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
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
                  tls.resolve("server.key").toString(),
                  "-out",
                  tls.resolve("server.crt").toString(),
                  "-subj",
                  "/CN=localhost",
                  "-addext",
                  "subjectAltName=DNS:localhost,IP:127.0.0.1")
              .redirectError(tls.resolve("openssl.log").toFile())
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .start();
      if (!openssl.waitFor(30, TimeUnit.SECONDS) || openssl.exitValue() != 0)
        throw new IOException("OPENSSL_CERTIFICATE_GENERATION_FAILED");
    }
  }

  public TlsTransport.Settings settings() throws Exception {
    return TlsTransport.Settings.load(directory.resolve("tls"), port);
  }

  public void start() throws Exception {
    for (int id = 0; id < 3; id++) {
      Path pid = directory.resolve("node-" + id + ".pid");
      if (Files.exists(pid)
          && ProcessHandle.of(Long.parseLong(Files.readString(pid).trim()))
              .map(ProcessCluster::running)
              .orElse(false)) {
        ProcessHandle existing =
            ProcessHandle.of(Long.parseLong(Files.readString(pid).trim())).orElseThrow();
        if (!expectedProcess(existing, id)) throw new IOException("REFUSE_UNRELATED_PID");
        continue;
      }
      List<String> command =
          new ArrayList<>(
              List.of(
                  Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                  "-Xms32m",
                  "-Xmx512m",
                  "-Xlog:gc:file=" + directory.resolve("node-" + id + "-gc.log")));
      if (Boolean.getBoolean("quorumkv.record"))
        command.add(
            "-XX:StartFlightRecording=name=quorumkv,settings=profile,maxsize=8m,maxage=1m,disk=true");
      command.addAll(
          List.of(
              "-cp",
              System.getProperty("java.class.path"),
              NodeMain.class.getName(),
              Integer.toString(id),
              directory.resolve("node-" + id).toString(),
              directory.resolve("tls").toString(),
              Integer.toString(port)));
      processes[id] =
          new ProcessBuilder(command)
              .redirectOutput(
                  ProcessBuilder.Redirect.appendTo(
                      directory.resolve("node-" + id + ".log").toFile()))
              .redirectError(
                  ProcessBuilder.Redirect.appendTo(
                      directory.resolve("node-" + id + ".log").toFile()))
              .start();
      Files.writeString(pid, Long.toString(processes[id].pid()));
    }
  }

  public long cpuNanos() {
    long total = 0;
    for (Process process : processes)
      if (process != null)
        total += process.info().totalCpuDuration().map(java.time.Duration::toNanos).orElse(0L);
    return total;
  }

  public void dumpDiagnostics() throws Exception {
    if (!Boolean.getBoolean("quorumkv.record")) return;
    for (int id = 0; id < 3; id++) {
      Process process = processes[id];
      if (process == null) continue;
      Process dump =
          new ProcessBuilder(
                  Path.of(System.getProperty("java.home"), "bin", "jcmd").toString(),
                  Long.toString(process.pid()),
                  "JFR.dump",
                  "name=quorumkv",
                  "filename=" + directory.resolve("node-" + id + ".jfr"))
              .redirectOutput(directory.resolve("jfr-dump-" + id + ".txt").toFile())
              .redirectErrorStream(true)
              .start();
      if (!dump.waitFor(20, TimeUnit.SECONDS) || dump.exitValue() != 0)
        throw new IOException("JFR_DUMP_FAILED node=" + id);
    }
  }

  private boolean expectedProcess(ProcessHandle process, int id) throws IOException {
    if (matchesArguments(process.info().arguments().orElse(new String[0]), id)) return true;
    // Some JVM/OS ProcessHandle implementations truncate command metadata. Linux
    // exposes exact NUL-delimited argv independently; bound reads before allocation.
    Path command = Path.of("/proc", Long.toString(process.pid()), "cmdline");
    if (!Files.isRegularFile(command)) return false;
    try (var input = Files.newInputStream(command)) {
      byte[] bytes = input.readNBytes(131073);
      if (bytes.length > 131072) return false;
      return matchesArguments(
          new String(bytes, java.nio.charset.StandardCharsets.UTF_8).split("\0"), id);
    }
  }

  private boolean matchesArguments(String[] arguments, int id) {
    for (int at = 0; at + 4 < arguments.length; at++)
      if (arguments[at].equals(NodeMain.class.getName())
          && arguments[at + 1].equals(Integer.toString(id))
          && Path.of(arguments[at + 2])
              .toAbsolutePath()
              .normalize()
              .equals(directory.resolve("node-" + id).normalize())
          && Path.of(arguments[at + 3])
              .toAbsolutePath()
              .normalize()
              .equals(directory.resolve("tls").normalize())
          && arguments[at + 4].equals(Integer.toString(port))) return true;
    return false;
  }

  private static boolean running(ProcessHandle process) {
    if (!process.isAlive()) return false;
    // A detached child can remain a zombie under a container init that does not reap.
    // It has already released its sockets/files, although ProcessHandle.isAlive stays true.
    Path stat = Path.of("/proc", Long.toString(process.pid()), "stat");
    if (Files.isRegularFile(stat)) {
      try (var input = Files.newInputStream(stat)) {
        String value = new String(input.readNBytes(4096), java.nio.charset.StandardCharsets.UTF_8);
        int end = value.lastIndexOf(')');
        if (end >= 0 && end + 2 < value.length()) {
          char state = value.charAt(end + 2);
          if (state == 'Z' || state == 'X') return false;
        }
      } catch (IOException ignored) {
        // /proc may disappear as the process exits; retain the portable fallback.
      }
    }
    return process.isAlive();
  }

  public void kill(int id) throws Exception {
    Path pid = directory.resolve("node-" + id + ".pid");
    if (!Files.exists(pid)) return;
    ProcessHandle handle =
        ProcessHandle.of(Long.parseLong(Files.readString(pid).trim())).orElse(null);
    if (handle != null && running(handle)) {
      Process owned = processes[id];
      if (!(owned != null && owned.pid() == handle.pid() && owned.isAlive())
          && !expectedProcess(handle, id)) throw new IOException("REFUSE_UNRELATED_PID");
      handle.destroyForcibly();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (running(handle)) {
        if (System.nanoTime() >= deadline) throw new IOException("PROCESS_STOP_TIMEOUT node=" + id);
        Thread.sleep(20);
      }
    }
    Files.deleteIfExists(pid);
    processes[id] = null;
  }

  @Override
  public void close() throws Exception {
    Exception failure = null;
    for (int id = 0; id < 3; id++) {
      try {
        kill(id);
      } catch (Exception error) {
        if (failure == null) failure = error;
        else failure.addSuppressed(error);
      }
    }
    if (failure != null) throw failure;
  }
}
