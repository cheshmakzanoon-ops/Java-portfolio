package io.github.cheshmakzanoon.portfolio.gatehouse;

import static io.github.cheshmakzanoon.portfolio.gatehouse.PolicyEngine.*;
import static org.assertj.core.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.gatehouse.PolicyLanguage.Truth;
import java.util.*;
import org.junit.jupiter.api.Test;

class PolicyExplainTest {
  private static Compiled sample() {
    return compile(
        new Definition(
            Map.of("reader", Set.of(), "editor", Set.of("reader")),
            List.of(new Grant("reader", "read", "doc")),
            List.of(
                new Rule(
                    "clearance",
                    "read",
                    "doc",
                    Effect.ALLOW,
                    "subject.clearance>=resource.classification and not subject.suspended"),
                new Rule("freeze", "read", "doc", Effect.DENY, "subject.suspended"),
                new Rule("tagged", "read", "doc", Effect.ALLOW, "exists(subject.department)"))));
  }

  @Test
  void anExplanationNamesTheClosureTheGrantAndEveryRuleInOrder() {
    Explanation explanation =
        explain(
            sample(),
            Set.of("editor"),
            "read",
            "doc",
            Map.of(
                "subject.clearance", 4L,
                "resource.classification", 2L,
                "subject.suspended", false));
    assertThat(explanation.decision().allowed()).isTrue();
    assertThat(explanation.decision().reason()).isEqualTo("MATCHED_ALLOW");
    assertThat(explanation.effectiveRoles()).containsExactly("editor", "reader");
    assertThat(explanation.granted()).isTrue();
    assertThat(explanation.rules())
        .extracting(RuleTrace::ruleId)
        .containsExactly("clearance", "freeze", "tagged");
    assertThat(explanation.rules())
        .extracting(RuleTrace::truth)
        .containsExactly(Truth.TRUE, Truth.FALSE, Truth.FALSE);
    assertThat(explanation.rules().getFirst().condition())
        .isEqualTo("subject.clearance >= resource.classification and not subject.suspended");
    assertThat(explanation.rules()).allSatisfy(r -> assertThat(r.note()).isNull());
    assertThat(explanation.decision().matchedRuleIds()).containsExactly("clearance");
  }

  @Test
  void anIndeterminateDecisionIdentifiesTheUnsuppliedAttribute() {
    Explanation explanation =
        explain(sample(), Set.of("reader"), "read", "doc", Map.of("subject.clearance", 4L));
    assertThat(explanation.decision().reason()).isEqualTo("INDETERMINATE");
    RuleTrace trace = explanation.rules().getFirst();
    assertThat(trace.truth()).isEqualTo(Truth.UNKNOWN);
    assertThat(trace.missingFields())
        .containsExactly("resource.classification", "subject.suspended");
    // exists() is total: an absent department is a definite FALSE, not a missing input.
    RuleTrace presence = explanation.rules().get(2);
    assertThat(presence.truth()).isEqualTo(Truth.FALSE);
    assertThat(presence.missingFields()).isEmpty();
  }

  @Test
  void denyAndMissingGrantAreExplained() {
    Explanation denied =
        explain(sample(), Set.of("reader"), "read", "doc", Map.of("subject.suspended", true));
    assertThat(denied.decision().reason()).isEqualTo("EXPLICIT_DENY");
    assertThat(denied.rules()).extracting(RuleTrace::ruleId).contains("freeze");
    assertThat(denied.rules().get(1).truth()).isEqualTo(Truth.TRUE);

    // Every attribute is supplied so nothing is UNKNOWN: the missing grant is then the only cause.
    Explanation ungranted =
        explain(
            sample(),
            Set.of(),
            "read",
            "doc",
            Map.of(
                "subject.clearance", 4L,
                "resource.classification", 2L,
                "subject.suspended", false));
    assertThat(ungranted.granted()).isFalse();
    assertThat(ungranted.effectiveRoles()).isEmpty();
    assertThat(ungranted.decision().reason()).isEqualTo("NO_RBAC_GRANT");

    Explanation outOfScope = explain(sample(), Set.of("reader"), "write", "doc", Map.of());
    assertThat(outOfScope.rules()).isEmpty();
    assertThat(outOfScope.granted()).isFalse();
    assertThatThrownBy(() -> outOfScope.rules().add(null))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void explanationsNeverDisagreeWithDecisions() {
    int compared = 0;
    for (long seed = 0; seed < 120; seed++) {
      PolicyFuzz.Generated generated = PolicyFuzz.of(seed, false).policy();
      Compiled compiled = compile(generated.definition());
      for (Set<String> assigned : PolicyFuzz.roleSets())
        for (Map<String, Object> point : PolicyFuzz.grid(generated.fields())) {
          Explanation explanation = explain(compiled, assigned, "read", "doc", point);
          assertThat(explanation.decision())
              .as("seed %d %s %s", seed, assigned, point)
              .isEqualTo(decide(compiled, assigned, "read", "doc", point));
          compared++;
        }
    }
    assertThat(compared).isGreaterThan(50_000);
  }
}
