package io.github.cheshmakzanoon.portfolio.daybook;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** CPU-only exact amount parsing and canonical journal digest; excludes SQL and HTTP. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(
    value = 3,
    jvmArgsAppend = {"-Xms256m", "-Xmx256m"})
@Threads(1)
public class AccountingBenchmark {
  @Param({"2", "100"})
  public int lines;

  private Journal journal;
  private String amount;

  @Setup
  public void prepare() {
    var postings = new ArrayList<Journal.Line>();
    for (int i = 0; i < lines; i++)
      postings.add(
          new Journal.Line(
              new UUID(0, i + 1),
              i % 2 == 0 ? Journal.Side.DEBIT : Journal.Side.CREDIT,
              BigInteger.valueOf(1234)));
    journal =
        new Journal(
            new UUID(0, 999), LocalDate.of(2026, 1, 2), "CAD", "jmh-seed-20261017", postings);
    amount = "1234567890123456789012345678.90";
  }

  @Benchmark
  public Money parseExactMoney() {
    return Money.parse("CAD", amount);
  }

  @Benchmark
  public String canonicalDigest() {
    return journal.digest();
  }
}
