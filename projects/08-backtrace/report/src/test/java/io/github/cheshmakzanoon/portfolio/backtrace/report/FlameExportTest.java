package io.github.cheshmakzanoon.portfolio.backtrace.report;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cheshmakzanoon.portfolio.backtrace.analysis.StackAggregator;
import io.github.cheshmakzanoon.portfolio.backtrace.analysis.StackAggregator.Flame;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlameExportTest {
  private static final String RUN = "a.A.run()V";
  private static final String WORK = "a.B.work(Ljava/lang/String;[I)V";

  private static Flame tree(int limit, List<List<String>> stacks) {
    StackAggregator aggregator = new StackAggregator(limit);
    stacks.forEach(aggregator::accept);
    return aggregator.snapshot();
  }

  private static long total(List<FlameExport.Stack> stacks) {
    return stacks.stream().mapToLong(FlameExport.Stack::weight).sum();
  }

  @Test
  void foldedOutputHasTheExactCollapsedStackShape() {
    Flame flame =
        tree(
            100,
            List.of(
                List.of(RUN, WORK),
                List.of(RUN, WORK),
                List.of(RUN, WORK),
                List.of(RUN),
                List.of(RUN),
                List.of()));
    assertEquals(
        "[unattributed] 1\n" + "a.A.run() 2\n" + "a.A.run();a.B.work(java.lang.String,int[]) 3\n",
        FlameExport.folded(flame));
  }

  @Test
  void everyExportSumsToTheSampleCountEvenWhenTheNodeLimitTruncatedTheTree() {
    Random random = new Random(7);
    List<List<String>> samples = new ArrayList<>();
    for (int i = 0; i < 300; i++) {
      int depth = random.nextInt(7);
      List<String> stack = new ArrayList<>();
      for (int d = 0; d < depth; d++) stack.add("p.C" + random.nextInt(5) + ".m" + d + "()V");
      samples.add(stack);
    }
    Flame flame = tree(8, samples);
    List<FlameExport.Stack> stacks = FlameExport.stacks(flame);
    assertEquals(300, flame.inclusive());
    assertEquals(300, total(stacks));
    assertTrue(stacks.stream().anyMatch(s -> s.frames().getLast().equals(FlameExport.TRUNCATED)));
    assertTrue(
        stacks.stream().anyMatch(s -> s.frames().equals(List.of(FlameExport.UNATTRIBUTED))),
        "stackless samples are reported, not dropped");
    long folded = 0;
    for (String line : FlameExport.folded(flame).split("\n"))
      folded += Long.parseLong(line.substring(line.lastIndexOf(' ') + 1));
    assertEquals(300, folded);
  }

  @Test
  void anUnboundedTreeRoundTripsThroughItsStacks() {
    Random random = new Random(11);
    List<List<String>> samples = new ArrayList<>();
    for (int i = 0; i < 500; i++) {
      List<String> stack = new ArrayList<>();
      for (int d = 1 + random.nextInt(6); d > 0; d--)
        stack.add("p.C" + random.nextInt(4) + ".m()V");
      samples.add(stack);
    }
    Flame original = tree(10_000, samples);
    StackAggregator rebuilt = new StackAggregator(10_000);
    for (FlameExport.Stack stack : FlameExport.stacks(original))
      for (long n = 0; n < stack.weight(); n++) rebuilt.accept(stack.frames());
    assertEquals(original, rebuilt.snapshot());
  }

  @Test
  void exportsAreDeterministicAndIndependentOfInsertionOrder() {
    List<List<String>> samples =
        List.of(List.of("x.X.a()V", "x.X.b()V"), List.of("x.X.c()V"), List.of("x.X.a()V"));
    List<List<String>> reversed = new ArrayList<>(samples);
    Collections.reverse(reversed);
    assertEquals(FlameExport.folded(tree(50, samples)), FlameExport.folded(tree(50, reversed)));
    assertEquals(FlameExport.stacks(tree(50, samples)), FlameExport.stacks(tree(50, reversed)));
  }

  @Test
  void displayNamesReadLikeSourceAndNeverBreakTheFormat() {
    assertEquals("a.A.run()", FlameExport.displayName("a.A.run()V"));
    assertEquals("a.B.work(java.lang.String,int[])", FlameExport.displayName(WORK));
    assertEquals(
        "m.M.f(long,double[][],boolean,byte,char,short,float)",
        FlameExport.displayName("m.M.f(J[[DZBCSF)V"));
    assertEquals("c.C.<init>(c.C$Inner)", FlameExport.displayName("c.C.<init>(Lc/C$Inner;)V"));
    assertEquals("[unknown_frame]", FlameExport.displayName("[unknown frame]"));
    for (String malformed :
        List.of(
            "a.A.m(Lbroken)V",
            "a.A.m(Q)V",
            "a.A.m(L;)V",
            "a.A.m([)V",
            "(I)V",
            "a.A.m(",
            "a;b c\nd")) {
      String shown = FlameExport.displayName(malformed);
      assertFalse(shown.contains(";"), shown);
      assertFalse(shown.chars().anyMatch(Character::isWhitespace), shown);
    }
    assertEquals("a:b_c_d", FlameExport.displayName("a;b c\nd"));
    // Anything that is not a well-formed descriptor is kept as is (modulo the format characters).
    assertEquals("a.A.m(Lbroken)V", FlameExport.displayName("a.A.m(Lbroken)V"));
    assertEquals("a.A.m(Q)V", FlameExport.displayName("a.A.m(Q)V"));
    assertEquals("a.A.m(L:)V", FlameExport.displayName("a.A.m(L;)V"));
    assertEquals("a.A.m([)V", FlameExport.displayName("a.A.m([)V"));
    assertEquals("(I)V", FlameExport.displayName("(I)V"));
    assertEquals("a.A.m(", FlameExport.displayName("a.A.m("));
    assertEquals("a.A.m(x.Y)", FlameExport.displayName("a.A.m(Lx/Y;)V"));
    assertEquals("a.A.m(x.Y,int)", FlameExport.displayName("a.A.m(Lx/Y;I)V"));
    assertEquals("a.A.m(int,x.Y)", FlameExport.displayName("a.A.m(ILx/Y;)V"));
    assertEquals("a.A.m(x.Y[][],int[])", FlameExport.displayName("a.A.m([[Lx/Y;[I)V"));
    String limit = "a.A.m(" + "I".repeat(255) + ")V";
    assertEquals(
        "a.A.m(" + String.join(",", Collections.nCopies(255, "int")) + ")",
        FlameExport.displayName(limit));
    String beyond = "a.A.m(" + "I".repeat(256) + ")V";
    assertEquals(beyond, FlameExport.displayName(beyond), "256 parameters is not a JVM signature");
    assertEquals(
        "long.name#0123abcd0123abcd.m(java.lang.String)",
        FlameExport.displayName("long.name#0123abcd0123abcd.m(Ljava/lang/String;)V"));
  }

  @Test
  void perJvmClassNumbersDoNotKeepTheSameCodeFromAligningAcrossRecordings() throws Exception {
    String baseline = "app.Main$$Lambda.0x000000008a048538.run()V";
    String candidate = "app.Main$$Lambda$14/0x00007f10c0001234.run()V";
    assertEquals("app.Main$$Lambda.run()", FlameExport.displayName(baseline));
    assertEquals("app.Main$$Lambda.run()", FlameExport.displayName(candidate));
    assertEquals(
        "jdk.internal.reflect.GeneratedMethodAccessor.invoke(java.lang.Object)",
        FlameExport.displayName(
            "jdk.internal.reflect.GeneratedMethodAccessor117.invoke(Ljava/lang/Object;)V"));
    assertEquals(
        "com.sun.proxy.$Proxy.call()", FlameExport.displayName("com.sun.proxy.$Proxy42.call()V"));
    assertEquals("app.Plain$Inner.m()", FlameExport.displayName("app.Plain$Inner.m()V"));
    assertEquals(
        "app.Main$$Lambda.run() 1 1\n",
        FlameExport.foldedDifferential(
            tree(10, List.of(List.of(baseline))), tree(10, List.of(List.of(candidate)))));
    // The exact export is untouched.
    assertTrue(
        FlameExport.speedscope("x", tree(10, List.of(List.of(baseline))))
            .contains("0x000000008a048538"));
  }

  @Test
  void framesThatPrintAlikeAreSummedInTextButStayDistinctInSpeedscope() throws Exception {
    // Bridge methods differ only by return type, which the readable form drops.
    Flame flame =
        tree(
            50,
            List.of(
                List.of("a.A.get()Ljava/lang/Object;"), List.of("a.A.get()Ljava/lang/String;")));
    assertEquals("a.A.get() 2\n", FlameExport.folded(flame));
    JsonNode scope = new ObjectMapper().readTree(FlameExport.speedscope("bridge", flame));
    assertEquals(2, scope.at("/shared/frames").size());
  }

  @Test
  void speedscopeIsALosslessWeightedSampledProfile() throws Exception {
    Flame flame =
        tree(100, List.of(List.of(RUN, WORK), List.of(RUN, WORK), List.of(RUN), List.of()));
    JsonNode scope = new ObjectMapper().readTree(FlameExport.speedscope("rec-1", flame));
    assertEquals(
        "https://www.speedscope.app/file-format-schema.json", scope.get("$schema").asText());
    assertEquals("Backtrace", scope.get("exporter").asText());
    assertEquals("rec-1", scope.get("name").asText());
    JsonNode profile = scope.at("/profiles/0");
    assertEquals("sampled", profile.get("type").asText());
    assertEquals(4, profile.get("endValue").asLong());
    List<String> names = new ArrayList<>();
    scope.at("/shared/frames").forEach(f -> names.add(f.get("name").asText()));
    assertEquals(new HashSet<>(names).size(), names.size(), "frames are unique");
    assertTrue(names.contains(WORK), "names keep their exact descriptors");
    // Reading the indices back must give exactly the stacks the tree holds.
    List<FlameExport.Stack> expected = FlameExport.stacks(flame);
    assertEquals(expected.size(), profile.get("samples").size());
    for (int i = 0; i < expected.size(); i++) {
      List<String> frames = new ArrayList<>();
      for (JsonNode index : profile.at("/samples/" + i)) frames.add(names.get(index.asInt()));
      assertEquals(expected.get(i).frames(), frames);
      assertEquals(expected.get(i).weight(), profile.at("/weights/" + i).asLong());
    }
    long weights = 0;
    for (int i = 0; i < profile.get("samples").size(); i++) {
      weights += profile.at("/weights/" + i).asLong();
      for (JsonNode frame : profile.at("/samples/" + i))
        assertTrue(frame.asInt() >= 0 && frame.asInt() < names.size());
    }
    assertEquals(4, weights);
    assertEquals(profile.get("samples").size(), profile.get("weights").size());
  }

  @Test
  void aDifferentialListsEveryStackFromEitherSideWithZeroForTheMissingOne() {
    Flame before = tree(100, List.of(List.of(RUN, WORK), List.of(RUN, WORK), List.of(RUN)));
    Flame after =
        tree(100, List.of(List.of(RUN, WORK), List.of(RUN), List.of(RUN), List.of("n.N.fresh()V")));
    assertEquals(
        "a.A.run() 1 2\n"
            + "a.A.run();a.B.work(java.lang.String,int[]) 2 1\n"
            + "n.N.fresh() 0 1\n",
        FlameExport.foldedDifferential(before, after));
    assertEquals(
        "a.A.run() 1 1\n",
        FlameExport.foldedDifferential(
            tree(5, List.of(List.of(RUN))), tree(5, List.of(List.of(RUN)))));
    assertEquals("", FlameExport.foldedDifferential(tree(5, List.of()), tree(5, List.of())));
  }
}
