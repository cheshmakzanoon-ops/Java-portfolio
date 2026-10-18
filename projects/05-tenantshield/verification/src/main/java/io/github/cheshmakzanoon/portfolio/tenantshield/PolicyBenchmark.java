package io.github.cheshmakzanoon.portfolio.tenantshield;

import java.util.*;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@State(Scope.Thread)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(
    value = 3,
    jvmArgsAppend = {"-Xms128m", "-Xmx256m"})
public class PolicyBenchmark {
  private PolicyEngine.Definition definition;
  private PolicyEngine.Compiled compiled;
  private Map<String, Object> fields;

  @Setup
  public void setup() {
    definition = Workload.definition();
    compiled = PolicyEngine.compile(definition);
    fields =
        Map.of(
            "subject.department",
            "support",
            "resource.department",
            "support",
            "subject.clearance",
            3L,
            "resource.classification",
            2L,
            "subject.suspended",
            false,
            "context.purpose",
            "support");
  }

  @Benchmark
  public PolicyEngine.Compiled coldCompilation() {
    return PolicyEngine.compile(definition);
  }

  @Benchmark
  public PolicyEngine.Decision warmIndexedEvaluation() {
    return PolicyEngine.decide(compiled, Set.of("reader"), "document:read", "document", fields);
  }
}
