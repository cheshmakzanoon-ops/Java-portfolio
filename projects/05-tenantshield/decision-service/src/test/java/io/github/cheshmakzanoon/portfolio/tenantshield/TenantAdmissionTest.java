package io.github.cheshmakzanoon.portfolio.tenantshield;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TenantAdmissionTest {
  @Test
  void tenantChurnCannotEvictLiveQuotaAndOverflowHasOneSharedBudget() {
    AtomicLong clock = new AtomicLong();
    TenantAdmission admission = new TenantAdmission(4, 2, 2, clock::get);
    take(admission, "alice");
    take(admission, "bob");
    take(admission, "alice");
    assertThat(admission.acquire("alice")).isNull();
    take(admission, "carol");
    take(admission, "dave");
    assertThat(admission.acquire("eve")).isNull();
    assertThat(admission.acquire("alice")).isNull();
    assertThat(admission.stats("alice")).containsEntry("rejections", 2);
    assertThat(admission.stats("eve")).containsEntry("maximumInFlight", 0);
    clock.set(1_000_000_000L);
    take(admission, "eve");
    take(admission, "alice");
  }

  @Test
  void activeLeasesRemainOwnedAcrossWindowChangesAndCloseIsIdempotent() {
    AtomicLong clock = new AtomicLong();
    TenantAdmission admission = new TenantAdmission(1, 1, 2, clock::get);
    TenantAdmission.Lease lease = admission.acquire("alice");
    assertThat(lease).isNotNull();
    clock.set(1_000_000_000L);
    assertThat(admission.acquire("bob")).isNull();
    assertThat(admission.stats("alice")).containsEntry("maximumInFlight", 1);
    lease.close();
    lease.close();
    take(admission, "bob");
    TenantAdmission.Lease next = admission.acquire("alice");
    assertThat(next).isNotNull();
    assertThat(admission.acquire("bob")).isNull();
    next.close();
    assertThat(admission.maximumInFlight()).isEqualTo(1);
  }

  @Test
  void concurrentAdmissionHonorsOneTenantBudgetWithoutPermitLeaks() throws Exception {
    TenantAdmission admission = new TenantAdmission(64, 1, 10, () -> 0);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger accepted = new AtomicInteger();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      for (int index = 0; index < 100; index++)
        futures.add(
            executor.submit(
                () -> {
                  if (!start.await(10, TimeUnit.SECONDS))
                    throw new AssertionError("start deadline");
                  try (var lease = admission.acquire("alice")) {
                    if (lease != null) accepted.incrementAndGet();
                  }
                  return null;
                }));
      start.countDown();
      for (Future<?> future : futures) future.get(10, TimeUnit.SECONDS);
    }
    assertThat(accepted).hasValue(10);
    assertThat(admission.stats("alice")).containsEntry("rejections", 90);
    assertThat(admission.maximumInFlight()).isBetween(1, 10);
  }

  private static void take(TenantAdmission admission, String tenant) {
    try (var lease = admission.acquire(tenant)) {
      assertThat(lease).isNotNull();
    }
  }
}
