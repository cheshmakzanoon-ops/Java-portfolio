package io.github.cheshmakzanoon.portfolio.threadlens.probe;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/** Bounded MPSC primitive ring. A producer makes at most three CAS attempts and never waits. */
public final class TimingBuffer {
  public record Timing(int methodId, long startedNanos, long durationNanos, boolean exceptional) {}

  private final int capacity;
  private final AtomicLongArray sequence;
  private final int[] methods;
  private final long[] starts, durations;
  private final boolean[] exceptional;
  private final AtomicLong tail = new AtomicLong();
  private long head;
  private final LongAdder dropped = new LongAdder();

  public TimingBuffer(int capacity) {
    if (capacity < 2 || capacity > 1_048_576)
      throw new IllegalArgumentException("capacity 2..1048576");
    this.capacity = capacity;
    sequence = new AtomicLongArray(capacity);
    methods = new int[capacity];
    starts = new long[capacity];
    durations = new long[capacity];
    exceptional = new boolean[capacity];
    for (int i = 0; i < capacity; i++) sequence.set(i, i);
  }

  public boolean offer(int method, long start, long duration, boolean failed) {
    for (int attempt = 0; attempt < 3; attempt++) {
      long position = tail.get();
      int slot = (int) (position % capacity);
      if (sequence.get(slot) != position) break;
      if (!tail.compareAndSet(position, position + 1)) continue;
      methods[slot] = method;
      starts[slot] = start;
      durations[slot] = duration;
      exceptional[slot] = failed;
      sequence.set(slot, position + 1);
      return true;
    }
    dropped.increment();
    return false;
  }

  /** Exactly one consumer may call poll. */
  public Timing poll() {
    int slot = (int) (head % capacity);
    if (sequence.get(slot) != head + 1) return null;
    Timing result = new Timing(methods[slot], starts[slot], durations[slot], exceptional[slot]);
    sequence.set(slot, head + capacity);
    head++;
    return result;
  }

  public long dropped() {
    return dropped.sum();
  }

  public int capacity() {
    return capacity;
  }

  /** Approximate outstanding reservations; exact after producers stop and consumer joins. */
  public long pending() {
    return tail.get() - head;
  }
}
