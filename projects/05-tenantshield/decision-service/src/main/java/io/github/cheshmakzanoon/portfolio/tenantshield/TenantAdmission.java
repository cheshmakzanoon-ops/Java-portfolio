package io.github.cheshmakzanoon.portfolio.tenantshield;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/** Owns quota state and request permits together, so live quota counters cannot be evicted. */
final class TenantAdmission {
  private static final class Bucket {
    long second = Long.MIN_VALUE;
    int requests;
    final AtomicInteger active = new AtomicInteger();
    final AtomicInteger maximum = new AtomicInteger();
    final AtomicInteger rejected = new AtomicInteger();
  }

  final class Lease implements AutoCloseable {
    private final Bucket bucket;
    private final AtomicBoolean closed = new AtomicBoolean();

    private Lease(Bucket bucket) {
      this.bucket = bucket;
    }

    @Override
    public void close() {
      if (!closed.compareAndSet(false, true)) return;
      bucket.active.decrementAndGet();
      active.decrementAndGet();
      permits.release();
    }
  }

  private final Map<String, Bucket> tenants = new LinkedHashMap<>();
  private final Bucket overflow = new Bucket();
  private final Semaphore permits;
  private final int maximumTenants;
  private final int requestsPerSecond;
  private final LongSupplier clock;
  private final AtomicInteger active = new AtomicInteger();
  private final AtomicInteger maximum = new AtomicInteger();
  private long lastReclaim = Long.MIN_VALUE;

  TenantAdmission() {
    this(64, 1000, 5000, System::nanoTime);
  }

  TenantAdmission(int concurrency, int maximumTenants, int requestsPerSecond, LongSupplier clock) {
    if (concurrency < 1 || maximumTenants < 1 || requestsPerSecond < 1)
      throw new IllegalArgumentException("positive admission limits required");
    this.permits = new Semaphore(concurrency);
    this.maximumTenants = maximumTenants;
    this.requestsPerSecond = requestsPerSecond;
    this.clock = java.util.Objects.requireNonNull(clock);
  }

  synchronized Lease acquire(String tenant) {
    long second = Math.floorDiv(clock.getAsLong(), 1_000_000_000L);
    Bucket bucket = tenants.get(tenant);
    if (bucket == null) {
      if (tenants.size() == maximumTenants && lastReclaim != second) {
        lastReclaim = second;
        var entries = tenants.entrySet().iterator();
        while (entries.hasNext()) {
          Bucket candidate = entries.next().getValue();
          if (candidate.second != second && candidate.active.get() == 0) {
            entries.remove();
          }
        }
      }
      if (tenants.size() < maximumTenants) {
        bucket = new Bucket();
        tenants.put(tenant, bucket);
      } else bucket = overflow;
    }
    if (bucket.second != second) {
      bucket.second = second;
      bucket.requests = 0;
    }
    if (bucket.requests >= requestsPerSecond || !permits.tryAcquire()) {
      bucket.rejected.updateAndGet(value -> value == Integer.MAX_VALUE ? value : value + 1);
      return null;
    }
    bucket.requests++;
    bucket.maximum.accumulateAndGet(bucket.active.incrementAndGet(), Math::max);
    maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
    return new Lease(bucket);
  }

  synchronized Map<String, Integer> stats(String tenant) {
    Bucket bucket = tenants.get(tenant);
    return bucket == null
        ? Map.of("maximumInFlight", 0, "rejections", 0)
        : Map.of("maximumInFlight", bucket.maximum.get(), "rejections", bucket.rejected.get());
  }

  int maximumInFlight() {
    return maximum.get();
  }
}
