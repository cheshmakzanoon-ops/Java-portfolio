package io.github.cheshmakzanoon.portfolio.latearrival;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(
    value = 1,
    jvmArgsAppend = {"-Xmx512m"})
public class EngineBenchmark {
  private Engine.State initial;
  private List<Engine.Input> batch;

  @Setup
  public void setup() {
    initial =
        new Engine.State("bench", new Engine.Limits(8, 50_000, 100_000, 2_000_000, 60_000), 0);
    var items = new java.util.ArrayList<Engine.Input>();
    for (int i = 0; i < 500; i++) {
      var e = new Engine.Event(1, "bench", "e" + i, "t", "s" + i, 0, i, false);
      items.add(Engine.Input.of(i % 8, i / 8, e));
    }
    batch = List.copyOf(items);
  }

  @Benchmark
  @OperationsPerInvocation(500)
  public void freshBatch(Blackhole bh) {
    bh.consume(Engine.transition(initial, batch, List.of(), 0));
  }
}
