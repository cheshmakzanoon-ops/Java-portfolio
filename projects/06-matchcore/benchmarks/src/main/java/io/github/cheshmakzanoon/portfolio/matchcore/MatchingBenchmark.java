package io.github.cheshmakzanoon.portfolio.matchcore;

import static io.github.cheshmakzanoon.portfolio.matchcore.Model.*;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(
    value = 3,
    jvmArgsAppend = {"-Xmx512m"})
public class MatchingBenchmark {
  private Book book;
  private Command incoming;

  @Setup(Level.Invocation)
  public void setup() {
    book = new Book();
    book.apply(1, new Command("init", "a", Type.INIT, null, null, 0, 0, null, false, 1000000, 100));
    book.apply(2, new Command("init", "b", Type.INIT, null, null, 0, 0, null, false, 1000000, 100));
    book.apply(
        3, new Command("ask", "b", Type.NEW, "ask", Side.SELL, 100, 10, Tif.GTC, false, 0, 0));
    incoming = new Command("buy", "a", Type.NEW, "buy", Side.BUY, 101, 5, Tif.IOC, false, 0, 0);
  }

  @Benchmark
  public void indexedPartialFill(Blackhole sink) {
    sink.consume(book.apply(4, incoming));
  }
}
