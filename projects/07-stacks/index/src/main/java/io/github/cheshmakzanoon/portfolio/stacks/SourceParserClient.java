package io.github.cheshmakzanoon.portfolio.stacks;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;

/** One admitted parser process,128MiB heap, bounded frames, watchdog and deterministic bundle. */
public final class SourceParserClient implements AutoCloseable {
  private static final Semaphore WORKER_ADMISSION = new Semaphore(1, true);
  private final Path bundle;
  private final ExecutorService reader;
  private Process worker;
  private DataOutputStream input;
  private DataInputStream output;
  private int requests;
  private final long startupNanos, unitNanos;
  private long lastPid, maxStartupNanos, maxUnitNanos;
  private int starts;
  private boolean ownsPermit;
  private boolean closed;

  public SourceParserClient(Path directory) throws IOException {
    this(directory, TimeUnit.SECONDS.toNanos(5), TimeUnit.SECONDS.toNanos(1));
  }

  SourceParserClient(Path directory, long startupNanos, long unitNanos) throws IOException {
    if (startupNanos < 1
        || startupNanos > TimeUnit.SECONDS.toNanos(5)
        || unitNanos < 1
        || unitNanos > TimeUnit.SECONDS.toNanos(1))
      throw new IllegalArgumentException("parser deadline bounds");
    this.startupNanos = startupNanos;
    this.unitNanos = unitNanos;
    if (Runtime.getRuntime().maxMemory() > 1792L * 1024 * 1024)
      throw new IOException(
          "structural ingest parent heap must be <=1792MiB; worker128MiB shares declared2GiB budget");
    Files.createDirectories(directory);
    byte[] bytes = bundle();
    String identity = Digests.sha256(bytes);
    bundle = directory.resolve("parser-" + identity + ".jar");
    if (Files.exists(bundle, LinkOption.NOFOLLOW_LINKS)) {
      if (!Files.isRegularFile(bundle, LinkOption.NOFOLLOW_LINKS)
          || Files.isSymbolicLink(bundle)
          || Files.size(bundle) != bytes.length
          || !Digests.sha256(bundle).equals(identity))
        throw new IOException("parser bundle integrity");
    } else DurableFiles.atomicWrite(bundle, bytes);
    reader =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1),
            Thread.ofPlatform().daemon(true).name("stacks-parser-frame").factory(),
            new ThreadPoolExecutor.AbortPolicy());
  }

  public String identity() {
    return bundle.getFileName().toString().substring("parser-".length(), "parser-".length() + 64);
  }

  public synchronized SourceFacts.Unit parse(String source) throws IOException {
    if (closed) throw new IOException("parser closed");
    var status = SourcePreflight.check(source);
    if (status != SourceFacts.Status.OBSERVED) return SourceFacts.Unit.unknown(status);
    return SourceWire.decode(exchange(source.getBytes(StandardCharsets.UTF_8), false));
  }

  public synchronized SourceCatalogFacts.Unit catalog(String source) throws IOException {
    if (closed) throw new IOException("parser closed");
    if (source.length() > 2_097_152) throw new IOException("API source byte quota");
    byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > 2_097_152) throw new IOException("API source byte quota");
    return SourceCatalogWire.decode(exchange(bytes, true));
  }

  private byte[] exchange(byte[] bytes, boolean catalog) throws IOException {
    if (Thread.currentThread().isInterrupted()) throw new IOException("parser cancelled");
    if (worker == null || requests == 64) {
      stopWorker();
      startWorker();
    }
    // Input is<=64KiB. A worker stuck reading/executing is also terminated by the same deadline;
    // both write and read happen in the single bounded task, never an unbounded caller write.
    long unitStarted = System.nanoTime();
    Future<byte[]> pending =
        reader.submit(
            () -> {
              if (catalog) input.writeInt(-2);
              input.writeInt(bytes.length);
              input.write(bytes);
              input.flush();
              int length = output.readInt();
              if (length < 1
                  || length > (catalog ? SourceCatalogWire.FRAME_LIMIT : SourceFacts.SUMMARY_BYTES))
                throw new IOException("parser response quota");
              byte[] response = output.readNBytes(length);
              if (response.length != length) throw new EOFException("parser response truncated");
              return response;
            });
    try {
      long remaining = unitNanos - (System.nanoTime() - unitStarted);
      if (remaining <= 0) throw new TimeoutException("parser unit deadline");
      var response = pending.get(remaining, TimeUnit.NANOSECONDS);
      long elapsed = System.nanoTime() - unitStarted;
      if (elapsed > unitNanos) throw new TimeoutException("parser unit deadline");
      maxUnitNanos = Math.max(maxUnitNanos, elapsed);
      requests++;
      return response;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      pending.cancel(true);
      stopWorker();
      throw new IOException("parser cancelled", interrupted);
    } catch (ExecutionException | TimeoutException failed) {
      pending.cancel(true);
      stopWorker();
      throw new IOException("parser worker failed; generation must not publish", failed);
    }
  }

  private void startWorker() throws IOException {
    long started = System.nanoTime();
    if (!Files.isRegularFile(bundle, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(bundle)
        || !Digests.sha256(bundle).equals(identity()))
      throw new IOException("parser bundle changed before launch");
    Path java = Path.of(System.getProperty("java.home"), "bin", "java");
    if (!Files.isRegularFile(java) || !Files.isExecutable(java))
      throw new IOException("JDK Java executable unavailable");
    var process =
        new ProcessBuilder(
                java.toString(),
                "-Xms16m",
                "-Xmx128m",
                "-XX:ActiveProcessorCount=1",
                "--add-modules=jdk.compiler",
                "-jar",
                bundle.toString())
            .redirectError(ProcessBuilder.Redirect.DISCARD);
    for (String option :
        List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH"))
      process.environment().remove(option);
    if (!WORKER_ADMISSION.tryAcquire()) throw new IOException("parser worker admission limit");
    ownsPermit = true;
    try {
      worker = process.start();
    } catch (IOException | RuntimeException failure) {
      releasePermit();
      throw failure;
    }
    lastPid = worker.pid();
    starts++;
    input = new DataOutputStream(new BufferedOutputStream(worker.getOutputStream()));
    output = new DataInputStream(new BufferedInputStream(worker.getInputStream()));
    requests = 0;
    Future<?> readiness =
        reader.submit(
            () -> {
              SourceParserWorker.readReady(output);
              return null;
            });
    try {
      long remaining = startupNanos - (System.nanoTime() - started);
      if (remaining <= 0) throw new TimeoutException("parser startup deadline");
      readiness.get(remaining, TimeUnit.NANOSECONDS);
      long elapsed = System.nanoTime() - started;
      if (elapsed > startupNanos) throw new TimeoutException("parser startup deadline");
      maxStartupNanos = Math.max(maxStartupNanos, elapsed);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      readiness.cancel(true);
      stopWorker();
      throw new IOException("parser startup cancelled", interrupted);
    } catch (ExecutionException | TimeoutException failed) {
      readiness.cancel(true);
      stopWorker();
      throw new IOException("parser startup/readiness failed", failed);
    }
  }

  synchronized long lastWorkerPid() {
    return lastPid;
  }

  synchronized int workerStarts() {
    return starts;
  }

  synchronized long maxStartupNanos() {
    return maxStartupNanos;
  }

  synchronized long maxUnitNanos() {
    return maxUnitNanos;
  }

  private void stopWorker() throws IOException {
    if (worker == null) return;
    boolean interrupted = Thread.interrupted();
    worker.destroyForcibly();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    try {
      while (worker.isAlive()) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IOException("parser worker reap deadline");
        try {
          worker.waitFor(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException cancelled) {
          interrupted = true;
        }
      }
    } finally {
      try {
        if (input != null) input.close();
      } finally {
        try {
          if (output != null) output.close();
        } finally {
          if (!worker.isAlive()) {
            worker = null;
            releasePermit();
          }
          input = null;
          output = null;
          if (interrupted) Thread.currentThread().interrupt();
        }
      }
    }
  }

  @Override
  public synchronized void close() throws IOException {
    if (closed && worker == null) return;
    closed = true;
    try {
      stopWorker();
    } finally {
      reader.shutdownNow();
    }
  }

  private void releasePermit() {
    if (ownsPermit) {
      ownsPermit = false;
      WORKER_ADMISSION.release();
    }
  }

  public static String bundleIdentity() throws IOException {
    return Digests.sha256(bundle());
  }

  static byte[] bundle() throws IOException {
    Map<String, Class<?>> classes = new TreeMap<>();
    var queue = new ArrayDeque<Class<?>>();
    queue.addAll(
        List.of(
            SourceParserWorker.class,
            SourceJavaParser.class,
            SourceSyntax.class,
            SourceCompilerBoundary.class,
            SourcePreflight.class,
            SourceWire.class,
            SourceFacts.class,
            SourceCatalogFacts.class,
            SourceCatalogParser.class,
            SourceCatalogWire.class));
    while (!queue.isEmpty()) {
      Class<?> type = queue.remove();
      if (classes.putIfAbsent(type.getName(), type) != null) continue;
      if (classes.size() > 128) throw new IOException("parser class count quota");
      queue.addAll(List.of(type.getDeclaredClasses()));
    }
    var bytes = new ByteArrayOutputStream();
    try (var jar = new JarOutputStream(bytes)) {
      var entry = new JarEntry("META-INF/MANIFEST.MF");
      entry.setTime(0);
      jar.putNextEntry(entry);
      jar.write(
          ("Manifest-Version: 1.0\r\nMain-Class: "
                  + SourceParserWorker.class.getName()
                  + "\r\n\r\n")
              .getBytes(StandardCharsets.UTF_8));
      jar.closeEntry();
      for (var c : classes.values()) {
        String resource = c.getName().replace('.', '/') + ".class";
        byte[] data;
        try (var input = c.getClassLoader().getResourceAsStream(resource)) {
          if (input == null) throw new IOException("missing parser class resource");
          data = input.readNBytes(1_048_577);
        }
        if (data.length > 1_048_576 || bytes.size() > 8_388_608)
          throw new IOException("parser bundle quota");
        var item = new JarEntry(resource);
        item.setTime(0);
        jar.putNextEntry(item);
        jar.write(data);
        jar.closeEntry();
      }
    }
    if (bytes.size() > 8_388_608) throw new IOException("parser bundle quota");
    return bytes.toByteArray();
  }
}
