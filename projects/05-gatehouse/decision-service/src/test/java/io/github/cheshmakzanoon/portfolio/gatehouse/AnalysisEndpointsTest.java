package io.github.cheshmakzanoon.portfolio.gatehouse;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.json.JsonMapper;

/**
 * The lint and explain routes run under simulate's administrative gate and touch no tenant data.
 */
class AnalysisEndpointsTest {
  private final PolicyStore store = mock(PolicyStore.class);
  private final DecisionController controller =
      new DecisionController(store, JsonMapper.builder().build(), new RequestBounds());
  private final Jwt jwt =
      Jwt.withTokenValue("test-token")
          .header("alg", "RS256")
          .issuer("https://issuer.example")
          .subject("administrator")
          .build();
  private final PolicyStore.Principal principal =
      new PolicyStore.Principal("https://issuer.example", "administrator");

  @SuppressWarnings("unchecked")
  private void adminGateRunsTheWork() {
    when(store.simulate(eq("harbor"), eq(principal), any()))
        .thenAnswer(
            call -> ((Function<PolicyStore.Membership, ?>) call.getArgument(2)).apply(null));
  }

  private static PolicyEngine.Definition policy(String allowCondition, String denyCondition) {
    return new PolicyEngine.Definition(
        Map.of("reader", Set.of()),
        List.of(new PolicyEngine.Grant("reader", "read", "document")),
        List.of(
            new PolicyEngine.Rule(
                "open", "read", "document", PolicyEngine.Effect.ALLOW, allowCondition),
            new PolicyEngine.Rule(
                "freeze", "read", "document", PolicyEngine.Effect.DENY, denyCondition)));
  }

  @Test
  void lintReportsProvenFindingsWithCountsAndTheDigest() {
    adminGateRunsTheWork();
    PolicyEngine.Definition definition = policy("subject.clearance >= 3", "subject.clearance >= 1");
    DecisionController.LintReport report =
        controller.lint("harbor", jwt, new DecisionController.Lint(definition));
    assertThat(report.policyDigest()).isEqualTo(PolicyEngine.compile(definition).digest());
    assertThat(report.errors()).isEqualTo(1);
    assertThat(report.warnings()).isEqualTo(1);
    assertThat(report.findings())
        .extracting(PolicyAnalyzer.Finding::code)
        .contains("SCOPE_NEVER_ALLOWS", "ALLOW_SHADOWED_BY_DENY");
    verify(store).simulate(eq("harbor"), eq(principal), any());
  }

  @Test
  void aCleanPolicyHasNoErrorsOrWarnings() {
    adminGateRunsTheWork();
    DecisionController.LintReport report =
        controller.lint(
            "harbor",
            jwt,
            new DecisionController.Lint(
                policy("subject.clearance >= resource.classification", "subject.suspended")));
    assertThat(report.errors()).isZero();
    assertThat(report.warnings()).isZero();
  }

  @Test
  void explainReturnsPerRuleTruthAndNormalizesJsonNumbers() {
    adminGateRunsTheWork();
    DecisionController.Example example =
        new DecisionController.Example(
            Set.of("reader"),
            "read",
            "document",
            // Jackson delivers small JSON integers as Integer; the route must widen them.
            Map.of(
                "subject.clearance", 5, "resource.classification", 2, "subject.suspended", false),
            true,
            "MATCHED_ALLOW");
    PolicyEngine.Explanation explanation =
        controller.explain(
            "harbor",
            jwt,
            new DecisionController.Simulation(
                policy("subject.clearance >= resource.classification", "subject.suspended"),
                example));
    assertThat(explanation.decision().allowed()).isTrue();
    assertThat(explanation.rules())
        .extracting(PolicyEngine.RuleTrace::ruleId)
        .containsExactly("freeze", "open");
    assertThat(explanation.rules().get(1).condition())
        .isEqualTo("subject.clearance >= resource.classification");
  }

  @Test
  void aNonAdministratorNeverReachesPolicyCompilationAndInvalidPoliciesAreClientErrors() {
    when(store.simulate(eq("harbor"), eq(principal), any())).thenThrow(new PolicyStore.Forbidden());
    PolicyEngine.Definition definition = policy("true", "false");
    assertThatThrownBy(
            () -> controller.lint("harbor", jwt, new DecisionController.Lint(definition)))
        .isInstanceOf(PolicyStore.Forbidden.class);
    DecisionController.Example example =
        new DecisionController.Example(Set.of(), "read", "document", Map.of(), false, "x");
    assertThatThrownBy(
            () ->
                controller.explain(
                    "harbor", jwt, new DecisionController.Simulation(definition, example)))
        .isInstanceOf(PolicyStore.Forbidden.class);

    reset(store);
    adminGateRunsTheWork();
    PolicyEngine.Definition cyclic =
        new PolicyEngine.Definition(
            Map.of("a", Set.of("b"), "b", Set.of("a")), List.of(), List.of());
    assertThatThrownBy(() -> controller.lint("harbor", jwt, new DecisionController.Lint(cyclic)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(controller.invalid().getStatusCode().value()).isEqualTo(400);
  }
}
