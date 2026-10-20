package io.github.cheshmakzanoon.portfolio.threadlens.jfr;

import io.github.cheshmakzanoon.portfolio.threadlens.analysis.NormalizedEvent;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Bounded MPSC normalized queue: both slot and conservative retained-payload budgets are enforced.
 */
public final class EventQueue {
  public static final long DEFAULT_BYTES = 64L * 1024 * 1024;
  private final int capacity;
  private final long byteLimit;
  private final AtomicLong retainedBytes = new AtomicLong(), peakBytes = new AtomicLong();
  private final AtomicLongArray sequence;
  private final AtomicReferenceArray<NormalizedEvent> values;
  private final AtomicLong tail = new AtomicLong();
  private final LongAdder drops = new LongAdder();
  private volatile long head;

  public EventQueue(int capacity) {
    this(capacity, DEFAULT_BYTES);
  }

  public EventQueue(int capacity, long byteLimit) {
    if (capacity < 2 || capacity > 65_536 || byteLimit < 512 || byteLimit > DEFAULT_BYTES)
      throw new IllegalArgumentException("queue capacity/byte bounds");
    this.capacity = capacity;
    this.byteLimit = byteLimit;
    sequence = new AtomicLongArray(capacity);
    values = new AtomicReferenceArray<>(capacity);
    for (int i = 0; i < capacity; i++) sequence.set(i, i);
  }

  private static long weight(NormalizedEvent event) {
    long bytes = 160 + 2L * event.subject().length();
    for (String frame : event.stack()) bytes += 64 + 2L * frame.length();
    return bytes;
  }

  public boolean offer(NormalizedEvent event) {
    if (tail.get() < 0) return false;
    long weight = weight(event);
    boolean reserved = false;
    for (int i = 0; i < 3; i++) {
      long bytes = retainedBytes.get();
      if (weight > byteLimit - bytes) break;
      if (retainedBytes.compareAndSet(bytes, bytes + weight)) {
        peakBytes.accumulateAndGet(bytes + weight, Math::max);
        reserved = true;
        break;
      }
    }
    if (!reserved) {
      drops.increment();
      return false;
    }
    for (int i = 0; i < 3; i++) {
      long position = tail.get();
      if (position < 0) {
        retainedBytes.addAndGet(-weight);
        return false;
      }
      int slot = (int) (position % capacity);
      if (sequence.get(slot) != position) break;
      if (!tail.compareAndSet(position, position + 1)) continue;
      values.set(slot, event);
      sequence.set(slot, position + 1);
      return true;
    }
    retainedBytes.addAndGet(-weight);
    drops.increment();
    return false;
  }

  public NormalizedEvent poll() {
    int slot = (int) (head % capacity);
    if (sequence.get(slot) != head + 1) return null;
    NormalizedEvent event = values.getAndSet(slot, null);
    retainedBytes.addAndGet(-weight(event));
    sequence.set(slot, head + capacity);
    head++;
    return event;
  }

  public long drops() {
    return drops.sum();
  }

  public long retainedBytes() {
    return retainedBytes.get();
  }

  public long peakBytes() {
    return peakBytes.get();
  }

  /** Stop new reservations; a producer already holding a slot may still publish it. */
  public long seal() {
    return tail.getAndUpdate(position -> position | Long.MIN_VALUE) & Long.MAX_VALUE;
  }

  /** Exact after producers and the single consumer have stopped. */
  public long pending() {
    return (tail.get() & Long.MAX_VALUE) - head;
  }
}
