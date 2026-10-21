package io.github.cheshmakzanoon.portfolio.backtrace.jfr;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.backtrace.analysis.*;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CollectionTest {
  @TempDir Path directory;

  @Test
  void depthBoundCannotHideUnvisitedDiskUsageAndDirectDotSpellingStillDumps() throws Exception {
    Path nested = directory;
    for (int i = 0; i < 9; i++) nested = Files.createDirectory(nested.resolve("level"));
    try (var file = new java.io.RandomAccessFile(nested.resolve("hidden.bin").toFile(), "rw")) {
      file.setLength(300L * 1024 * 1024);
    }
    assertThrows(java.io.IOException.class, () -> RecordingController.directoryBytes(directory));
    Path output = directory.resolve("./direct");
    try (var controller = new RecordingController(output, 2)) {
      controller.start();
      controller.stopAndDump(output.resolve("recording.jfr"));
      assertTrue(Files.size(output.resolve("recording.jfr")) > 0);
    }
  }

  @Test
  void observerMayCloseItsOwnControllerAndExternalCloseCanJoinTheDrain() throws Exception {
    var controller = new RecordingController(directory, 65_536);
    java.util.concurrent.CountDownLatch closedFromCallback =
        new java.util.concurrent.CountDownLatch(1);
    controller.observer(
        event -> {
          if (event.subject().equals("sample")) {
            controller.close();
            closedFromCallback.countDown();
          }
        });
    try {
      controller.start();
      assertTrue(
          controller.publish(
              new NormalizedEvent(
                  NormalizedEvent.Kind.SAMPLE, 1, 1, "sample", List.of("safe.Frame"))));
      assertTrue(closedFromCallback.await(5, java.util.concurrent.TimeUnit.SECONDS));
      controller.close();
      assertTrue(controller.profile().sampleCount() >= 1);
      assertFalse(
          controller.publish(
              new NormalizedEvent(NormalizedEvent.Kind.SAMPLE, 1, 1, "sample", List.of())));
    } finally {
      controller.close();
    }
  }

  @Test
  void dumpCannotUseSymlinkDotDotTraversalOrASymlinkedObserverDirectory() throws Exception {
    Path output = Files.createDirectory(directory.resolve("output"));
    Path external = Files.createDirectory(directory.resolve("external"));
    Path child = Files.createDirectory(external.resolve("child"));
    Files.createSymbolicLink(output.resolve("link"), child);
    try (var controller = new RecordingController(output, 2)) {
      controller.start();
      assertThrows(
          java.io.IOException.class, () -> controller.dump(output.resolve("link/../escaped.jfr")));
      assertFalse(Files.exists(external.resolve("escaped.jfr")));
    }
    Path alias = directory.resolve("alias");
    Files.createSymbolicLink(alias, external);
    try (var controller = new RecordingController(alias.resolve("nested"), 2)) {
      assertThrows(java.io.IOException.class, controller::start);
    }
  }

  @Test
  void queueDropsWithoutBlockingAndKeepsSlotsReusable() {
    var queue = new EventQueue(2);
    var event = new NormalizedEvent(NormalizedEvent.Kind.SAMPLE, 1, 1, "s", List.of("frame"));
    assertTrue(queue.offer(event));
    assertTrue(queue.offer(event));
    assertFalse(queue.offer(event));
    assertEquals(1, queue.drops());
    assertEquals(event, queue.poll());
    assertEquals(event, queue.poll());
    assertNull(queue.poll());
    assertTrue(queue.offer(event));
    assertEquals(event, queue.poll());
    assertThrows(IllegalArgumentException.class, () -> new EventQueue(1));
  }

  @Test
  void discoveryMarksAbsentEventsUnsupported() {
    var empty = EventCapabilities.describe(List.of());
    assertTrue(empty.values().stream().noneMatch(c -> c.supported()));
    assertTrue(EventCapabilities.settings(empty).isEmpty());
    var current = EventCapabilities.discover();
    assertTrue(current.get("jdk.ExecutionSample").supported());
    assertEquals("20 ms", EventCapabilities.settings(current).get("jdk.ExecutionSample#period"));
    assertEquals("false", EventCapabilities.settings(current).get("jdk.ThreadPark#stackTrace"));
    assertEquals(
        "50/s", EventCapabilities.settings(current).get("jdk.ObjectAllocationSample#throttle"));
    assertEquals(
        "true", EventCapabilities.settings(current).get("jdk.JavaMonitorEnter#stackTrace"));
  }

  @Test
  void liveStopDrainEqualsOfflineReplayAndConsumerFailurePreservesHost() throws Exception {
    var controller = new RecordingController(directory, 65_536);
    AtomicLong calls = new AtomicLong();
    controller.observer(
        e -> {
          calls.incrementAndGet();
          throw new IllegalStateException("observer failure fixture secret");
        });
    controller.start();
    long end = System.nanoTime() + 2_200_000_000L;
    long host = 1;
    while (System.nanoTime() < end) {
      for (int i = 0; i < 10000; i++) host = host * 1664525 + 1013904223;
    }
    Path recording = directory.resolve("fixture.jfr");
    controller.stopAndDump(recording);
    assertNotEquals(0, host);
    assertTrue(Files.size(recording) > 0);
    assertTrue(calls.get() > 0);
    assertEquals(calls.get(), controller.consumerFailures());
    assertEquals(calls.get(), controller.profile().counters().get("consumerFailures"));
    assertEquals(0, controller.profile().counters().get("queueDrops"));
    assertTrue(controller.profile().counters().containsKey("truncatedStacks"));
    var importer = new RecordingImporter();
    var a = importer.read(recording);
    var b = importer.read(recording);
    assertEquals(a, b);
    assertTrue(a.profile().sampleCount() > 0);
    // Consumer callbacks fail after aggregation; canonical event totals remain identical.
    assertEquals(a.profile().eventCount(), controller.profile().eventCount());
    assertEquals(a.profile().sampleCount(), controller.profile().sampleCount());
    assertEquals(a.profile().methods(), controller.profile().methods());
    assertTrue(controller.profile().windows().values().stream().allMatch(w -> w.incomplete()));
  }

  @Test
  void directoryBudgetRejectsCollectionWithoutMutatingHost() throws Exception {
    Path huge = directory.resolve("budget.bin");
    try (var file = new java.io.RandomAccessFile(huge.toFile(), "rw")) {
      file.setLength(257L * 1024 * 1024);
    }
    try (var controller = new RecordingController(directory, 2)) {
      assertThrows(java.io.IOException.class, controller::start);
    }
  }

  @Test
  void failedDumpStillClosesAndDrainsAndDoesNotReplaceExistingEvidence() throws Exception {
    Path retained = directory.resolve("retained.jfr");
    Files.writeString(retained, "retained evidence");
    try (var controller = new RecordingController(directory, 2)) {
      controller.start();
      assertThrows(IllegalStateException.class, controller::start);
      assertThrows(java.io.IOException.class, () -> controller.stopAndDump(retained));
      assertEquals("retained evidence", Files.readString(retained));
      assertEquals("closed", controller.stopReason());
      assertFalse(
          controller.publish(
              new NormalizedEvent(NormalizedEvent.Kind.SAMPLE, 1, 1, "s", List.of())));
      assertNotNull(controller.profile());
      assertThrows(IllegalStateException.class, controller::start);
    }
  }

  @Test
  void payloadBudgetBoundsDeepStacksIndependentOfSlotCount() {
    var queue = new EventQueue(65536, 512);
    var large =
        new NormalizedEvent(
            NormalizedEvent.Kind.SAMPLE, 1, 1, "x".repeat(256), List.of("x".repeat(256)));
    assertFalse(queue.offer(large));
    assertEquals(1, queue.drops());
    assertEquals(0, queue.retainedBytes());
    var small = new NormalizedEvent(NormalizedEvent.Kind.SAMPLE, 1, 1, "x", List.of());
    assertTrue(queue.offer(small));
    assertTrue(queue.offer(small));
    assertTrue(queue.offer(small));
    assertFalse(queue.offer(small));
    assertTrue(queue.peakBytes() <= 512);
    while (queue.poll() != null) {}
    assertEquals(0, queue.retainedBytes());
  }

  @Test
  void longIdentifiersPreserveStableDistinctions() {
    String first = "f".repeat(300) + "A", second = "f".repeat(300) + "B";
    assertNotEquals(
        EventNormalizer.boundedIdentity(first), EventNormalizer.boundedIdentity(second));
    assertEquals(EventNormalizer.boundedIdentity(first), EventNormalizer.boundedIdentity(first));
    assertEquals(256, EventNormalizer.boundedIdentity(first).length());
    assertEquals("short", EventNormalizer.boundedIdentity("short"));
  }
}
