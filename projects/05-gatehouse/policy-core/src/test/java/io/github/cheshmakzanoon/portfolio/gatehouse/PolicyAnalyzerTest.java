package io.github.cheshmakzanoon.portfolio.gatehouse;

import static io.github.cheshmakzanoon.portfolio.gatehouse.PolicyAnalyzer.*;
import static io.github.cheshmakzanoon.portfolio.gatehouse.PolicyEngine.*;
import static org.assertj.core.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.gatehouse.PolicyLanguage.Expr;
import io.github.cheshmakzanoon.portfolio.gatehouse.PolicyLanguage.Inputs;
import io.github.cheshmakzanoon.portfolio.gatehouse.PolicyLanguage.Truth;
import java.util.*;
import org.junit.jupiter.api.Test;

class PolicyAnalyzerTest {
  private static Rule allow(String id, String condition) {
    return new Rule(id, "read", "doc", Effect.ALLOW, condition);
  }

  private static Rule deny(String id, String condition) {
    return new Rule(id, "read", "doc", Effect.DENY, condition);
  }

  private static Definition policy(Rule... rules) {
    return new Definition(
        Map.of("reader", Set.of(), "editor", Set.of("reader"), "auditor", Set.of()),
        List.of(new Grant("reader", "read", "doc"), new Grant("auditor", "read", "doc")),
        List.of(rules));
  }

  private static Report report(Rule... rules) {
    return analyze(compile(policy(rules)));
  }

  private static List<String> codes(Report report, String ruleId) {
    return report.findings().stream()
        .filter(f -> Objects.equals(f.ruleId(), ruleId))
        .map(Finding::code)
        .toList();
  }

  private static boolean has(Report report, String code, String ruleId) {
    return codes(report, ruleId).contains(code);
  }

  // ---------------------------------------------------------------------------------------------
  // Deterministic cases
  // ---------------------------------------------------------------------------------------------

  @Test
  void impossibleConditionsAreProvenForEveryAttributeType() {
    Report report =
        report(
            allow("integers-gap", "subject.clearance > 5 and subject.clearance < 6"),
            allow("integers-range", "subject.clearance >= 7 and subject.clearance <= 6"),
            allow("strings", "subject.department == \"a\" and subject.department == \"b\""),
            allow("string-order", "subject.department > \"b\" and subject.department < \"a\""),
            allow("sets", "subject.department in [\"a\"] and subject.department in [\"b\"]"),
            allow("not-in", "subject.department in [\"a\"] and not subject.department in [\"a\"]"),
            allow("enum", "resource.sensitivity == PUBLIC and resource.sensitivity == INTERNAL"),
            allow("bool", "subject.suspended and not subject.suspended"),
            allow("presence", "exists(subject.clearance) and not exists(subject.clearance)"),
            allow(
                "value-needs-presence", "subject.clearance > 1 and not exists(subject.clearance)"),
            allow("constant", "false"),
            allow("kleene", "not (subject.clearance > 3) and subject.clearance > 3"),
            allow("self", "subject.clearance < subject.clearance"));
    for (String id :
        List.of(
            "integers-gap",
            "integers-range",
            "strings",
            "string-order",
            "sets",
            "not-in",
            "enum",
            "bool",
            "presence",
            "value-needs-presence",
            "constant",
            "kleene",
            "self")) assertThat(has(report, "NEVER_MATCHES", id)).as(id).isTrue();
  }

  @Test
  void satisfiableNeighboursAreNotFlagged() {
    Report report =
        report(
            allow("one-integer", "subject.clearance > 5 and subject.clearance < 7"),
            allow("string-between", "subject.department > \"a\" and subject.department < \"b\""),
            allow("not-equal", "subject.department != \"a\" and subject.department != \"b\""),
            allow("extremes", "subject.clearance < 9223372036854775807"),
            allow("absent-ok", "not exists(subject.clearance)"),
            allow("kleene-or", "not (subject.clearance > 3) or subject.clearance > 3"),
            allow("self-equal", "subject.clearance == subject.clearance"),
            allow("two-fields", "resource.department == subject.department"));
    assertThat(report.findings()).noneMatch(f -> f.code().equals("NEVER_MATCHES"));
  }

  @Test
  void roleInheritanceMakesNegativeRoleTestsImpossible() {
    // editor inherits reader, so holding editor means holding reader.
    Report report =
        report(
            allow("contradiction", "hasRole(\"editor\") and not hasRole(\"reader\")"),
            allow("fine", "hasRole(\"reader\") and not hasRole(\"editor\")"),
            allow("unrelated", "hasRole(\"auditor\") and not hasRole(\"reader\")"));
    assertThat(has(report, "NEVER_MATCHES", "contradiction")).isTrue();
    assertThat(has(report, "NEVER_MATCHES", "fine")).isFalse();
    assertThat(has(report, "NEVER_MATCHES", "unrelated")).isFalse();
  }

  @Test
  void absentAttributesAreUnknownSoOnlyTotalCoverageIsATautology() {
    Report report =
        report(
            deny("not-total", "subject.clearance > 3 or subject.clearance <= 3"),
            deny(
                "total",
                "subject.clearance > 3 or subject.clearance <= 3 or not exists(subject.clearance)"),
            deny("constant", "true"),
            allow("roles", "hasRole(\"reader\") or not hasRole(\"reader\")"));
    assertThat(has(report, "DENY_ALWAYS_APPLIES", "not-total")).isFalse();
    assertThat(has(report, "DENY_ALWAYS_APPLIES", "total")).isTrue();
    assertThat(has(report, "DENY_ALWAYS_APPLIES", "constant")).isTrue();
    assertThat(has(report, "ALLOW_UNCONDITIONAL", "roles")).isTrue();
  }

  /**
   * Regression for an unsound first design that modelled two-attribute comparisons as "both
   * present". Its complement made this a tautology, but equal-looking departments may differ.
   */
  @Test
  void comparingTwoAttributesIsNeverReportedAsCertain() {
    Report report =
        report(
            deny(
                "relation",
                "resource.department == subject.department or not exists(resource.department) or not exists(subject.department)"),
            deny(
                "order",
                "subject.clearance >= resource.classification or subject.clearance < resource.classification"));
    assertThat(codes(report, "relation")).doesNotContain("DENY_ALWAYS_APPLIES");
    assertThat(codes(report, "order")).doesNotContain("DENY_ALWAYS_APPLIES");
  }

  @Test
  void denyRulesShadowAllowRulesOnlyWhenDenyIsTrueOrUnknownEverywhereTheAllowIs() {
    Report shadowed =
        report(
            allow("wide-clearance", "subject.clearance >= 3"),
            deny("any-clearance", "subject.clearance >= 1"));
    assertThat(has(shadowed, "ALLOW_SHADOWED_BY_DENY", "wide-clearance")).isTrue();
    assertThat(has(shadowed, "SCOPE_NEVER_ALLOWS", null)).isTrue();
    assertThat(shadowed.hasErrors()).isTrue();

    // The deny also needs an unsupplied attribute, so it can be FALSE while the allow is TRUE.
    Report independent =
        report(
            allow("clearance", "subject.clearance > 1"),
            deny("conditional", "subject.clearance > 0 and resource.classification > 1"));
    assertThat(codes(independent, "clearance")).doesNotContain("ALLOW_SHADOWED_BY_DENY");
    assertThat(independent.hasErrors()).isFalse();

    // Unknown counts as shadowing: when the deny's attribute is absent the decision is
    // INDETERMINATE, but the allow can still apply with the attribute present and unmatched.
    Report unknown =
        report(allow("a", "subject.department == \"x\""), deny("b", "resource.type == \"secret\""));
    assertThat(codes(unknown, "a")).doesNotContain("ALLOW_SHADOWED_BY_DENY");
  }

  @Test
  void combinedDenyRulesCanShadowWhereNoSingleOneDoes() {
    // Each deny leaves one clearance value uncovered; together they cover the whole allow range.
    Report report =
        report(
            allow("range", "subject.clearance >= 0 and subject.clearance <= 2"),
            deny("low", "subject.clearance <= 1"),
            deny("high", "subject.clearance >= 1"));
    Finding finding =
        report.findings().stream()
            .filter(f -> f.code().equals("ALLOW_SHADOWED_BY_DENY"))
            .findFirst()
            .orElseThrow();
    assertThat(finding.ruleId()).isEqualTo("range");
    assertThat(finding.message()).startsWith("Together");

    // Dropping one deny reopens the gap, so the finding disappears.
    assertThat(
            report(
                    allow("range", "subject.clearance >= 0 and subject.clearance <= 2"),
                    deny("low", "subject.clearance <= 1"))
                .findings())
        .noneMatch(f -> f.code().equals("ALLOW_SHADOWED_BY_DENY"));
  }

  @Test
  void scopeLevelFindings() {
    Definition noGrant =
        new Definition(Map.of("reader", Set.of()), List.of(), List.of(allow("lonely", "true")));
    Report ungranted = analyze(compile(noGrant));
    assertThat(has(ungranted, "ALLOW_WITHOUT_GRANT", "lonely")).isTrue();
    assertThat(has(ungranted, "SCOPE_NEVER_ALLOWS", null)).isFalse();

    Definition noRule =
        new Definition(
            Map.of("reader", Set.of()),
            List.of(new Grant("reader", "read", "doc")),
            List.of(deny("only", "subject.suspended")));
    Report empty = analyze(compile(noRule));
    assertThat(has(empty, "GRANT_WITHOUT_ALLOW_RULE", null)).isTrue();

    Report duplicate =
        report(allow("one", "subject.clearance > 1"), allow("two", "subject.clearance > 1"));
    assertThat(has(duplicate, "DUPLICATE_RULE", "two")).isTrue();
    assertThat(has(duplicate, "DUPLICATE_RULE", "one")).isFalse();
    assertThat(
            report(allow("one", "subject.clearance > 1"), deny("two", "subject.clearance > 1"))
                .findings())
        .noneMatch(f -> f.code().equals("DUPLICATE_RULE"));

    Report sets = report(allow("dup", "subject.department in [\"a\", \"b\", \"a\"]"));
    assertThat(has(sets, "DUPLICATE_SET_ELEMENT", "dup")).isTrue();
  }

  @Test
  void unusedRolesAreOnlyThoseWithNoGrantNoInheritedGrantAndNoRoleTest() {
    Definition definition =
        new Definition(
            Map.of(
                "reader", Set.of(),
                "editor", Set.of("reader"),
                "orphan", Set.of(),
                "tested", Set.of(),
                "child-of-tested", Set.of("tested")),
            List.of(new Grant("reader", "read", "doc")),
            List.of(allow("r", "hasRole(\"tested\")")));
    Report report = analyze(compile(definition));
    List<String> unused =
        report.findings().stream()
            .filter(f -> f.code().equals("UNUSED_ROLE"))
            .map(Finding::message)
            .toList();
    assertThat(unused).hasSize(1);
    assertThat(unused.getFirst()).contains("orphan");
  }

  @Test
  void aSearchBudgetIsReportedAsInconclusiveNeverGuessed() {
    StringBuilder condition = new StringBuilder();
    for (int i = 0; i < 17; i++)
      condition
          .append("(context.hour > ")
          .append(2 * i)
          .append(" or context.hour > ")
          .append(2 * i + 1)
          .append(") and ");
    condition.append("context.hour < 0");
    Report report = report(allow("explosive", condition.toString()));
    assertThat(has(report, "ANALYSIS_LIMIT", "explosive")).isTrue();
    assertThat(has(report, "NEVER_MATCHES", "explosive")).isFalse();
    // An easily refuted version of the same shape is decided.
    assertThat(
            has(
                report("(context.hour > 1 or context.hour > 2) and context.hour < 0"),
                "NEVER_MATCHES",
                "easy"))
        .isTrue();
  }

  private static Report report(String easyCondition) {
    return report(allow("easy", easyCondition));
  }

  @Test
  void reportsAreDeterministicSortedAndBoundToThePolicyDigest() {
    Definition definition =
        policy(
            allow("b", "false"),
            allow("a", "subject.clearance > 5 and subject.clearance < 6"),
            deny("c", "true"));
    Report first = analyze(compile(definition)), second = analyze(compile(definition));
    assertThat(first).isEqualTo(second);
    assertThat(first.policyDigest()).isEqualTo(compile(definition).digest());
    List<Finding> sorted =
        first.findings().stream()
            .sorted(
                Comparator.comparing(Finding::severity)
                    .thenComparing(Finding::code)
                    .thenComparing(f -> f.ruleId() == null ? "" : f.ruleId())
                    .thenComparing(Finding::message))
            .toList();
    assertThat(first.findings()).isEqualTo(sorted);
    assertThatThrownBy(() -> first.findings().add(first.findings().getFirst()))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  // ---------------------------------------------------------------------------------------------
  // Independent oracle: exhaustive evaluation through the production interpreter
  // ---------------------------------------------------------------------------------------------

  @Test
  void everyClaimIsConfirmedByExhaustiveEvaluation() {
    for (long seed = 0; seed < 360; seed++) confirm(seed, false);
    for (long seed = 1000; seed < 1060; seed++) confirm(seed, true);
    // A generator that never produced a finding kind would make this test vacuous for that kind.
    assertThat(kinds.keySet())
        .containsExactlyInAnyOrder(
            "NEVER_MATCHES",
            "ALLOW_SHADOWED_BY_DENY",
            "DENY_ALWAYS_APPLIES",
            "ALLOW_UNCONDITIONAL",
            "SCOPE_NEVER_ALLOWS",
            "ALLOW_WITHOUT_GRANT",
            "GRANT_WITHOUT_ALLOW_RULE");
    assertThat(kinds.values()).as("claims per kind: %s", kinds).allMatch(count -> count >= 5);
  }

  private final Map<String, Integer> kinds = new TreeMap<>();

  private void claim(String kind) {
    kinds.merge(kind, 1, Integer::sum);
  }

  private void confirm(long seed, boolean relations) {
    PolicyFuzz.Generated generated = PolicyFuzz.of(seed, relations).policy();
    Definition definition = generated.definition();
    Compiled compiled = compile(definition);
    Report report = analyze(compiled);
    String context =
        "seed " + seed + (relations ? " (relations)" : "") + " " + definition + " " + report;
    assertThat(report.findings()).as(context).noneMatch(f -> f.code().equals("ANALYSIS_LIMIT"));

    Map<String, Expr> expressions = new LinkedHashMap<>();
    for (Rule rule : definition.rules())
      expressions.put(rule.id(), PolicyLanguage.compile(rule.condition()));
    List<Map<String, Object>> grid = PolicyFuzz.grid(generated.fields());
    List<Set<String>> roleSets = PolicyFuzz.roleSets();

    Map<String, Boolean> anyTrue = new HashMap<>(), allTrue = new HashMap<>();
    Map<String, Boolean> possible = new HashMap<>();
    expressions
        .keySet()
        .forEach(
            id -> {
              anyTrue.put(id, false);
              allTrue.put(id, true);
              possible.put(id, false);
            });
    boolean everAllowed = false;
    for (Set<String> assigned : roleSets) {
      Set<String> roles = compiled.roles(assigned);
      for (Map<String, Object> point : grid) {
        Inputs inputs = new Inputs(point, roles);
        Map<String, Truth> truth = new HashMap<>();
        for (var entry : expressions.entrySet())
          truth.put(entry.getKey(), PolicyLanguage.evaluate(entry.getValue(), inputs));
        for (Rule rule : definition.rules()) {
          Truth t = truth.get(rule.id());
          if (t == Truth.TRUE) anyTrue.put(rule.id(), true);
          if (t != Truth.TRUE) allTrue.put(rule.id(), false);
          if (rule.effect() == Effect.ALLOW && t == Truth.TRUE) {
            boolean denied =
                definition.rules().stream()
                    .filter(d -> d.effect() == Effect.DENY)
                    .anyMatch(d -> truth.get(d.id()) != Truth.FALSE);
            if (!denied) possible.put(rule.id(), true);
          }
        }
        if (decide(compiled, assigned, "read", "doc", point).allowed()) everAllowed = true;
      }
    }

    boolean granted = !definition.grants().isEmpty();
    for (Rule rule : definition.rules()) {
      String id = rule.id();
      boolean never = has(report, "NEVER_MATCHES", id);
      boolean certain =
          has(
              report,
              rule.effect() == Effect.DENY ? "DENY_ALWAYS_APPLIES" : "ALLOW_UNCONDITIONAL",
              id);
      boolean shadowed = has(report, "ALLOW_SHADOWED_BY_DENY", id);
      // Soundness: every claim must hold on the exhaustive grid.
      if (never) {
        assertThat(anyTrue.get(id)).as("never matches " + id + " " + context).isFalse();
        claim("NEVER_MATCHES");
      }
      if (certain) {
        assertThat(allTrue.get(id)).as("always applies " + id + " " + context).isTrue();
        claim(rule.effect() == Effect.DENY ? "DENY_ALWAYS_APPLIES" : "ALLOW_UNCONDITIONAL");
      }
      if (shadowed) {
        assertThat(possible.get(id)).as("shadowed " + id + " " + context).isFalse();
        claim("ALLOW_SHADOWED_BY_DENY");
      }
      if (!relations) {
        // Completeness: without two-attribute relations the analysis is exact, not just sound.
        assertThat(never)
            .as("never-matches is exact for " + id + " " + context)
            .isEqualTo(!anyTrue.get(id));
        assertThat(certain)
            .as("certainty is exact for " + id + " " + context)
            .isEqualTo(allTrue.get(id));
        if (rule.effect() == Effect.ALLOW && granted)
          assertThat(shadowed)
              .as("shadowing is exact for " + id + " " + context)
              .isEqualTo(anyTrue.get(id) && !possible.get(id));
      }
    }
    for (String scopeCode :
        List.of("SCOPE_NEVER_ALLOWS", "ALLOW_WITHOUT_GRANT", "GRANT_WITHOUT_ALLOW_RULE")) {
      boolean reported = report.findings().stream().anyMatch(f -> f.code().equals(scopeCode));
      if (reported) {
        assertThat(everAllowed)
            .as(scopeCode + " must mean no input is allowed: " + context)
            .isFalse();
        claim(scopeCode);
      }
    }
  }
}
