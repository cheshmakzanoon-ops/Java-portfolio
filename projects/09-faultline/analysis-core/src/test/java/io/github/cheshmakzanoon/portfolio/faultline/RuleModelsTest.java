package io.github.cheshmakzanoon.portfolio.faultline;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.faultline.dataflow.Taint;
import io.github.cheshmakzanoon.portfolio.faultline.rules.RuleModels;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

class RuleModelsTest {
  private final RuleModels builtIn = new RuleModels();

  @Test
  void contextsAreDistinctSingleBitsThatExactlyFillTheLattice() {
    int union = 0;
    for (int mask : RuleModels.CONTEXTS.values()) {
      assertEquals(1, Integer.bitCount(mask));
      assertEquals(0, union & mask, "contexts must not overlap");
      union |= mask;
    }
    assertEquals(RuleModels.ALL_CONTEXTS, union);
    assertEquals(7, RuleModels.CONTEXTS.size());
    assertTrue(RuleModels.CONTEXTS.keySet().containsAll(RuleModels.LEGACY_RULES));
  }

  @Test
  void theBuiltInTablesSatisfyTheSameValidationAsAnOperatorSuppliedModel() {
    // The public constructor checks every descriptor, argument index, rule/mask pair and
    // sanitizer context, so rebuilding the built-in model through it validates the tables.
    RuleModels rebuilt = new RuleModels(builtIn.sources(), builtIn.sinks(), builtIn.sanitizers());
    assertEquals(builtIn.sinks(), rebuilt.sinks());
    assertEquals(builtIn.sources(), rebuilt.sources());
    assertEquals(builtIn.sanitizers(), rebuilt.sanitizers());
  }

  @Test
  void everyRuleExceptTheSpecialCasedShellRuleHasSinksAndASanitizer() {
    Set<String> withSinks = new TreeSet<>();
    builtIn.sinks().values().forEach(sink -> withSinks.add(sink.id()));
    Set<String> expected = new TreeSet<>(RuleModels.CONTEXTS.keySet());
    expected.remove("BG-SHELL-COMMAND");
    assertEquals(expected, withSinks);
    Set<Integer> cleared = new TreeSet<>(builtIn.sanitizers().values());
    assertEquals(new TreeSet<>(RuleModels.CONTEXTS.values()), cleared);
  }

  @Test
  void sinkArgumentsPointAtTheDocumentedParameter() {
    for (var entry : builtIn.sinks().entrySet()) {
      Type[] arguments =
          Type.getArgumentTypes(entry.getKey().substring(entry.getKey().indexOf('(')));
      int index = entry.getValue().argument();
      assertTrue(index < arguments.length, entry.getKey());
      if (entry.getValue().id().equals("BG-LDAP-FILTER"))
        assertEquals(1, index, "the LDAP filter is the second parameter: " + entry.getKey());
      else assertEquals(0, index, entry.getKey());
    }
  }

  @Test
  void webRequestSourcesAreRecognisedForBothServletNamespaces() {
    String string = "(Ljava/lang/String;)Ljava/lang/String;";
    for (String pkg : List.of("javax", "jakarta")) {
      assertTrue(builtIn.source(pkg + "/servlet/ServletRequest.getParameter" + string));
      assertTrue(builtIn.source(pkg + "/servlet/http/HttpServletRequest.getHeader" + string));
      assertTrue(
          builtIn.source(
              pkg + "/servlet/http/HttpServletRequest.getQueryString()Ljava/lang/String;"));
      assertFalse(
          builtIn.source(pkg + "/servlet/http/HttpServletRequest.getMethod()Ljava/lang/String;"),
          "the HTTP method is not attacker-controlled free text");
    }
    assertEquals(17, builtIn.sources().size());
  }

  @Test
  void versionsMapToTheRulesTheyDeclare() {
    assertEquals(RuleModels.LEGACY_RULES, RuleModels.rulesFor(RuleModels.LEGACY_VERSION));
    assertEquals(RuleModels.CONTEXTS.keySet(), RuleModels.rulesFor(RuleModels.VERSION));
    assertThrows(IllegalArgumentException.class, () -> RuleModels.rulesFor("0.9.0"));
    assertEquals(Set.of("1.0.0", "1.1.0"), RuleModels.SUPPORTED_VERSIONS);
  }

  @Test
  void sourceTaintCoversEveryContextAndASanitizerClearsExactlyOne() {
    Taint source = Taint.source("S:x");
    assertEquals(RuleModels.ALL_CONTEXTS, source.labels().get("S:x"));
    for (int mask : RuleModels.CONTEXTS.values()) {
      Taint cleaned = source.sanitize(mask);
      assertEquals(RuleModels.ALL_CONTEXTS & ~mask, cleaned.labels().get("S:x"));
      assertFalse(cleaned.hasConcrete(mask));
      for (int other : RuleModels.CONTEXTS.values())
        if (other != mask) assertTrue(cleaned.hasConcrete(other));
    }
    Taint everything = source;
    for (int mask : RuleModels.CONTEXTS.values()) everything = everything.sanitize(mask);
    assertTrue(everything.labels().isEmpty(), "a value cleaned for every context carries no label");
  }

  @Test
  void invalidOperatorModelsAreStillRejected() {
    String sink = "example/Db.run(Ljava/lang/String;)V";
    Map<String, RuleModels.Sink> sinks = Map.of(sink, new RuleModels.Sink("BG-SQL-TEXT", 1, 0));
    assertDoesNotThrow(() -> new RuleModels(Set.of(), sinks, Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RuleModels(
                Set.of(), Map.of(sink, new RuleModels.Sink("BG-SQL-TEXT", 2, 0)), Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RuleModels(
                Set.of(), Map.of(sink, new RuleModels.Sink("BG-OTHER", 1, 0)), Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RuleModels(
                Set.of(), Map.of(sink, new RuleModels.Sink("BG-SQL-TEXT", 1, 1)), Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RuleModels(
                Set.of(),
                sinks,
                Map.of("example/S.clean(Ljava/lang/String;)Ljava/lang/String;", 3)));
  }
}
