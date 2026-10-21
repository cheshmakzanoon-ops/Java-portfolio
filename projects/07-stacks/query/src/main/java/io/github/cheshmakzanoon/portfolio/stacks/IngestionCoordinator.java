package io.github.cheshmakzanoon.portfolio.stacks;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;
import java.util.function.BooleanSupplier;

/**
 * Owns the data-directory lock, tenant writers and durable job/publication ordering. Shutdown
 * excludes writers; an active external lease rejects shutdown before any reader closes.
 */
final class IngestionCoordinator implements AutoCloseable {
  private final Path root;
  private final AuthorizationFilter authorization;
  private final boolean structural;
  private final DataDirectoryLock owner;
  private final ContentAddressedStore cas;
  private final IngestionJob jobs;
  private final Map<String, GenerationPublisher> publishers = new HashMap<>();
  private final Map<String, ReentrantLock> writers = new ConcurrentHashMap<>();
  private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock(true);
  private boolean closed;

  IngestionCoordinator(Path root, AuthorizationFilter authorization, boolean structural)
      throws IOException {
    this.root = root;
    this.authorization = Objects.requireNonNull(authorization);
    this.structural = structural;
    Files.createDirectories(root);
    owner = new DataDirectoryLock(root);
    try {
      cas = new ContentAddressedStore(root.resolve("cas"));
      jobs = new IngestionJob(root.resolve("jobs"));
      Files.createDirectories(root.resolve("index"));
      try (var tenants = Files.list(root.resolve("index"))) {
        for (Path tenant : tenants.filter(Files::isDirectory).toList()) {
          String name = tenant.getFileName().toString();
          if (!name.matches("[a-zA-Z0-9_-]{1,64}"))
            throw new IOException("invalid tenant directory");
          GenerationPublisher publisher = new GenerationPublisher(tenant);
          publishers.put(name, publisher);
          reconcile(publisher);
        }
      }
    } catch (IOException | RuntimeException failure) {
      try {
        close();
      } catch (IOException cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  ContentAddressedStore contentStore() {
    return cas;
  }

  GenerationPublisher generations(String tenant) throws IOException {
    lifecycle.readLock().lock();
    try {
      ensureOpen();
      requireTenant(tenant);
      synchronized (publishers) {
        GenerationPublisher publisher = publishers.get(tenant);
        if (publisher == null) {
          publisher = new GenerationPublisher(root.resolve("index").resolve(tenant));
          publishers.put(tenant, publisher);
        }
        return publisher;
      }
    } finally {
      lifecycle.readLock().unlock();
    }
  }

  IngestionJob.Registered register(
      AuthorizationFilter.Access access, String key, SnapshotManifest manifest) throws IOException {
    lifecycle.readLock().lock();
    try {
      ensureOpen();
      authorization.require(access, manifest.tenantId(), manifest.repositoryId());
      return jobs.register(key, manifest);
    } finally {
      lifecycle.readLock().unlock();
    }
  }

  IngestionJob.Registered job(AuthorizationFilter.Access access, String id) throws IOException {
    lifecycle.readLock().lock();
    try {
      ensureOpen();
      var job = jobs.get(id);
      authorization.require(access, job.manifest().tenantId(), job.manifest().repositoryId());
      return job;
    } finally {
      lifecycle.readLock().unlock();
    }
  }

  boolean completed(String id) {
    lifecycle.readLock().lock();
    try {
      ensureOpen();
      return id != null && id.matches("[a-f0-9]{64}") && jobs.completed(id);
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    } finally {
      lifecycle.readLock().unlock();
    }
  }

  IngestionJob.Registered execute(
      AuthorizationFilter.Access access,
      IngestionJob.Registered job,
      GenerationBuilder.Fault fault,
      BooleanSupplier cancelled)
      throws IOException {
    lifecycle.readLock().lock();
    try {
      ensureOpen();
      ReentrantLock writer =
          writers.computeIfAbsent(access.tenant(), ignored -> new ReentrantLock(true));
      try {
        writer.lockInterruptibly();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IOException("cancelled", interrupted);
      }
      try {
        SnapshotManifest manifest = job.manifest();
        authorization.require(access, manifest.tenantId(), manifest.repositoryId());
        GenerationPublisher publisher = generations(access.tenant());
        reconcile(publisher);
        if (jobs.completed(job.id())) return job;
        GenerationBuilder builder =
            new GenerationBuilder(
                root.resolve("index").resolve(access.tenant()),
                cas,
                GenerationBuilder.MAX_SOURCE_BYTES,
                structural);
        GenerationBuilder.Built built =
            builder.build(manifest, publisher.sources(), fault, cancelled, job.id());
        Throwable primary = null;
        try {
          authorization.require(access, manifest.tenantId(), manifest.repositoryId());
          if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new IOException("cancelled");
          publisher.publish(built, fault);
          reconcile(publisher);
          return job;
        } catch (IOException | RuntimeException | Error failure) {
          primary = failure;
          throw failure;
        } finally {
          try {
            builder.discard(built);
          } catch (IOException cleanup) {
            if (primary != null) primary.addSuppressed(cleanup);
            else throw cleanup;
          }
        }
      } finally {
        writer.unlock();
      }
    } finally {
      lifecycle.readLock().unlock();
    }
  }

  private void reconcile(GenerationPublisher publisher) throws IOException {
    var committed = publisher.committedJob();
    if (committed != null)
      jobs.complete(committed.id(), committed.manifestHash(), committed.generation());
  }

  private static void requireTenant(String tenant) {
    if (tenant == null || !tenant.matches("[a-zA-Z0-9_-]{1,64}"))
      throw new IllegalArgumentException("tenant");
  }

  private void ensureOpen() {
    if (closed) throw new IllegalStateException("engine closed");
  }

  @Override
  public void close() throws IOException {
    lifecycle.writeLock().lock();
    try {
      if (closed) return;
      List<GenerationPublisher> prepared = new ArrayList<>();
      try {
        for (var publisher : publishers.values()) {
          publisher.prepareClose();
          prepared.add(publisher);
        }
      } catch (IOException | RuntimeException | Error rejected) {
        for (var publisher : prepared) publisher.cancelClose();
        throw rejected;
      }
      closed = true;
      IOException failure = null;
      List<AutoCloseable> resources = new ArrayList<>(publishers.values());
      resources.add(owner);
      for (AutoCloseable resource : resources) {
        try {
          resource.close();
        } catch (Exception close) {
          IOException next = close instanceof IOException io ? io : new IOException(close);
          if (failure == null) failure = next;
          else failure.addSuppressed(next);
        }
      }
      publishers.clear();
      if (failure != null) throw failure;
    } finally {
      lifecycle.writeLock().unlock();
    }
  }
}
