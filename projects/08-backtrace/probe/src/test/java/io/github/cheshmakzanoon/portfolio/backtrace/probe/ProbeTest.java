package io.github.cheshmakzanoon.portfolio.backtrace.probe;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ProbeTest {
  @Test
  void boundedRingAndFaultsPreserveHost() {
    var ring = new TimingBuffer(2);
    assertTrue(ring.offer(1, 2, 3, false));
    assertTrue(ring.offer(2, 3, 4, true));
    assertFalse(ring.offer(3, 4, 5, false));
    assertEquals(1, ring.dropped());
    assertEquals(1, ring.poll().methodId());
    assertEquals(2, ring.poll().methodId());
    assertNull(ring.poll());
    TimingProbe.configure(ring);
    TimingProbe.exit(3, TimingProbe.enter(), false);
    assertNotNull(ring.poll());
    TimingProbe.injectFault(true);
    long failures = TimingProbe.failures();
    assertDoesNotThrow(() -> TimingProbe.exit(1, TimingProbe.enter(), true));
    assertFalse(TimingProbe.enabled());
    assertEquals(failures + 1, TimingProbe.failures());
    TimingProbe.injectFault(false);
  }

  @Test
  void idsAreStableBoundedAndDistinct() {
    var r = new MethodRegistry(1);
    assertEquals(0, r.register("a"));
    assertEquals(0, r.register("a"));
    assertEquals(-1, r.register("b"));
    assertEquals(1, r.overflow());
    assertEquals(MethodRegistry.stableIdentity("a"), MethodRegistry.stableIdentity("a"));
    assertNotEquals(MethodRegistry.stableIdentity("a"), MethodRegistry.stableIdentity("b"));
  }
}
