package io.github.cheshmakzanoon.portfolio.faultline;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.faultline.bytecode.InstructionId;
import io.github.cheshmakzanoon.portfolio.faultline.dataflow.Taint;
import io.github.cheshmakzanoon.portfolio.faultline.interprocedural.MethodSummary;
import java.util.*;
import org.junit.jupiter.api.Test;

class MethodSummaryContractTest {
  @Test
  void parameterSubstitutionPreservesReturnSinkArrayAndFieldEffects() {
    String method = "Owner.helper(Ljava/lang/String;)Ljava/lang/String;";
    Taint parameter = Taint.parameter(method, 0), concrete = Taint.source("S:caller-input");
    var flow =
        new MethodSummary.Flow(
            "BG-SQL-TEXT",
            1,
            new InstructionId(method, 3),
            "sink",
            parameter,
            List.of("call"),
            true);
    MethodSummary summary =
        new MethodSummary(
            parameter,
            new TreeMap<>(Map.of("sink", flow)),
            new TreeMap<>(Map.of(0, parameter)),
            new TreeMap<>(Map.of("Owner.field:Ljava/lang/String;", parameter)));
    MethodSummary result = summary.instantiate(List.of(concrete), method);
    assertEquals(concrete, result.returns());
    assertEquals(concrete, result.flows().get("sink").taint());
    assertEquals(Map.of(0, concrete), result.elementWrites());
    assertEquals(Map.of("Owner.field:Ljava/lang/String;", concrete), result.fieldWrites());
    assertEquals(List.of("call"), result.flows().get("sink").callPath());
    assertTrue(result.flows().get("sink").truncated());
    MethodSummary legacy = new MethodSummary(Taint.source("P:0"), new TreeMap<>());
    assertEquals(concrete, legacy.instantiate(List.of(concrete)).returns());
  }

  @Test
  void joinRetainsFieldEffectsFromBothBranchesWithoutErasingEitherSource() {
    Taint first = Taint.source("S:first"), second = Taint.source("S:second");
    MethodSummary a =
        new MethodSummary(
            Taint.BOTTOM,
            new TreeMap<>(),
            new TreeMap<>(),
            new TreeMap<>(Map.of("shared", first, "left-only", first)));
    MethodSummary b =
        new MethodSummary(
            Taint.BOTTOM,
            new TreeMap<>(),
            new TreeMap<>(),
            new TreeMap<>(Map.of("shared", second, "right-only", second)));
    assertEquals(
        Map.of("shared", first.join(second), "left-only", first, "right-only", second),
        a.join(b).fieldWrites());
    assertEquals(a.join(b), b.join(a));
  }

  @Test
  void witnessPrefixOrderingIsIndependentOfJoinOrder() {
    var sink = new InstructionId("Owner.sink()V", 0);
    var shorter = flowSummary(sink, List.of("A"));
    var longer = flowSummary(sink, List.of("A", "B"));
    assertEquals(List.of("A"), shorter.join(longer).flows().get("sink").callPath());
    assertEquals(shorter.join(longer), longer.join(shorter));
  }

  private MethodSummary flowSummary(InstructionId sink, List<String> path) {
    return new MethodSummary(
        Taint.BOTTOM,
        new TreeMap<>(
            Map.of(
                "sink",
                new MethodSummary.Flow(
                    "BG-SQL-TEXT", 1, sink, "sink", Taint.BOTTOM, path, false))));
  }
}
