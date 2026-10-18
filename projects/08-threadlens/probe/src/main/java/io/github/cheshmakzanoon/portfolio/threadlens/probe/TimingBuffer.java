package io.github.cheshmakzanoon.portfolio.threadlens.probe;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/** Bounded MPSC primitive ring. A producer makes at most three CAS attempts and never waits. */
public final class TimingBuffer {
  public record Timing(int methodId, long startedNanos, long durationNanos, boolean exceptional) {}

  @FunctionalInterface
  public interface TimingConsumer {
    void accept(int methodId, long startedNanos, long durationNanos, boolean exceptional)
        throws Exception;
  }

  private final int capacity;
  private final int mask;
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
    mask = Integer.bitCount(capacity) == 1 ? capacity - 1 : -1;
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
      if (position < 0) return false; // Sealed collection: no new reservation can succeed.
      int slot = slot(position);
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
    int slot = slot(head);
    if (sequence.get(slot) != head + 1) return null;
    Timing result = new Timing(methods[slot], starts[slot], durations[slot], exceptional[slot]);
    sequence.set(slot, head + capacity);
    head++;
    return result;
  }

  /**
   * Single-consumer primitive delivery. Reclaim before calling user code, so a throwing observer
   * cannot wedge a reserved queue slot. A thrown callback has consumed the event; callers must
   * report that failure as incomplete collection rather than retrying it.
   */
  public boolean pollTo(TimingConsumer consumer) throws Exception {
    int slot = slot(head);
    if (sequence.get(slot) != head + 1) return false;
    int method = methods[slot];
    long start = starts[slot], duration = durations[slot];
    boolean failed = exceptional[slot];
    sequence.set(slot, head + capacity);
    head++;
    consumer.accept(method, start, duration, failed);
    return true;
  }

  private int slot(long position) {
    return mask >= 0 ? (int) (position & mask) : (int) (position % capacity);
  }

  public long dropped() {
    return dropped.sum();
  }

  public int capacity() {
    return capacity;
  }

  /** Approximate outstanding reservations; exact after producers stop and consumer joins. */
  public long pending() {
    return (tail.get() & Long.MAX_VALUE) - head;
  }

  /** Close reservation admission atomically; previously reserved slots remain drainable. */
  public long seal() {
    return tail.getAndUpdate(value -> value | Long.MIN_VALUE) & Long.MAX_VALUE;
  }
}
