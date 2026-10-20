package io.github.cheshmakzanoon.portfolio.tenantshield;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CompiledPoliciesTest {
  private final JsonMapper mapper = JsonMapper.builder().build();

  private PolicyStore.Revision revision(long version, String condition) {
    PolicyEngine.Definition definition =
        new PolicyEngine.Definition(
            Map.of("reader", Set.of()),
            List.of(new PolicyEngine.Grant("reader", "read", "document")),
            List.of(
                new PolicyEngine.Rule(
                    "read", "read", "document", PolicyEngine.Effect.ALLOW, condition)));
    return new PolicyStore.Revision(
        version, mapper.writeValueAsString(definition), PolicyEngine.compile(definition).digest());
  }

  @Test
  void concurrentReadersShareOneImmutablePlanLoad() throws Exception {
    AtomicInteger loads = new AtomicInteger();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    CompiledPolicies cache =
        new CompiledPolicies(
            content -> {
              loads.incrementAndGet();
              entered.countDown();
              try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("load deadline");
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
              }
              return mapper.readValue(content, PolicyEngine.Definition.class);
            });
    PolicyStore.Revision revision = revision(1, "true");
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<PolicyEngine.Compiled>> futures = new ArrayList<>();
      futures.add(executor.submit(() -> cache.resolve("harbor", revision)));
      assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
      for (int index = 0; index < 24; index++)
        futures.add(executor.submit(() -> cache.resolve("harbor", revision)));
      release.countDown();
      PolicyEngine.Compiled first = futures.getFirst().get(10, TimeUnit.SECONDS);
      for (Future<PolicyEngine.Compiled> future : futures)
        assertThat(future.get(10, TimeUnit.SECONDS)).isSameAs(first);
    } finally {
      release.countDown();
    }
    assertThat(loads).hasValue(1);
    assertThat(cache.size()).isEqualTo(1);
  }

  @Test
  void cacheIdentityIncludesTenantVersionAndDigestAndEvictionRevalidates() {
    CompiledPolicies cache = new CompiledPolicies(mapper);
    PolicyStore.Revision first = revision(1, "true");
    PolicyEngine.Compiled harbor = cache.resolve("harbor", first);
    assertThat(cache.resolve("harbor", first)).isSameAs(harbor);
    assertThat(cache.resolve("orchard", first)).isNotSameAs(harbor);
    assertThat(cache.resolve("harbor", revision(2, "true"))).isNotSameAs(harbor);
    PolicyEngine.Compiled different = cache.resolve("harbor", revision(1, "false"));
    assertThat(different.digest()).isNotEqualTo(harbor.digest());
    assertThat(cache.size()).isEqualTo(4);
    assertThat(cache.size("harbor")).isEqualTo(3);
    cache.invalidate();
    assertThat(cache.size()).isZero();
    assertThat(cache.resolve("harbor", first)).isNotSameAs(harbor).isEqualTo(harbor);
  }

  @Test
  void storedCorruptionIsAnUnavailableDependencyAndFailedLoadsAreNotCached() {
    CompiledPolicies cache = new CompiledPolicies(mapper);
    PolicyStore.Revision valid = revision(1, "true");
    for (PolicyStore.Revision corrupt :
        List.of(
            new PolicyStore.Revision(0, valid.content(), valid.digest()),
            new PolicyStore.Revision(1, valid.content(), "bad-digest"),
            new PolicyStore.Revision(1, valid.content(), "0".repeat(64)),
            new PolicyStore.Revision(1, "not json", valid.digest()),
            new PolicyStore.Revision(
                1, valid.content().replace("\"true\"", "\"unknown.field\""), valid.digest()),
            new PolicyStore.Revision(1, " ".repeat(262145), valid.digest()))) {
      assertThatThrownBy(() -> cache.resolve("harbor", corrupt))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("unavailable");
      assertThat(cache.size()).isZero();
    }
    assertThat(cache.resolve("harbor", valid).digest()).isEqualTo(valid.digest());
  }
}
