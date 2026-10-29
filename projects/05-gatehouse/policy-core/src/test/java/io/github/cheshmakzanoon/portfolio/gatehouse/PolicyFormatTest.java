package io.github.cheshmakzanoon.portfolio.gatehouse;

import static org.assertj.core.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.gatehouse.PolicyLanguage.Expr;
import org.junit.jupiter.api.Test;

class PolicyFormatTest {
  /** Formats, re-parses and requires an identical tree and an idempotent text. */
  private static String canonical(String source) {
    Expr parsed = PolicyLanguage.compile(source);
    String canonical = PolicyLanguage.format(parsed);
    assertThat(PolicyLanguage.compile(canonical)).as(source + " -> " + canonical).isEqualTo(parsed);
    assertThat(PolicyLanguage.format(PolicyLanguage.compile(canonical))).isEqualTo(canonical);
    return canonical;
  }

  @Test
  void whitespaceAndRedundantParenthesesNormalize() {
    assertThat(canonical("  subject.clearance>=3   and ( resource.type==\"doc\" )"))
        .isEqualTo("subject.clearance >= 3 and resource.type == \"doc\"");
    assertThat(canonical("((true))")).isEqualTo("true");
    assertThat(canonical("exists( subject.department )")).isEqualTo("exists(subject.department)");
    assertThat(canonical("resource.sensitivity in [PUBLIC,INTERNAL]"))
        .isEqualTo("resource.sensitivity in [PUBLIC, INTERNAL]");
    assertThat(canonical("hasRole( \"reader\" )")).isEqualTo("hasRole(\"reader\")");
  }

  @Test
  void parenthesesAppearWhereGroupingWouldOtherwiseChange() {
    String s = "subject.suspended", d = "exists(subject.department)", r = "exists(resource.id)";
    // A left-nested chain of one operator is what the parser builds, so it needs no grouping.
    assertThat(canonical(s + " and " + d + " and " + r)).isEqualTo(s + " and " + d + " and " + r);
    assertThat(canonical(s + " or " + d + " or " + r)).isEqualTo(s + " or " + d + " or " + r);
    // A right-nested chain differs structurally and must keep its parentheses.
    assertThat(canonical(s + " and (" + d + " and " + r + ")"))
        .isEqualTo(s + " and (" + d + " and " + r + ")");
    // Mixed operators are always grouped, which is also what a reader needs.
    assertThat(canonical(s + " or " + d + " and " + r))
        .isEqualTo(s + " or (" + d + " and " + r + ")");
    assertThat(canonical("(" + s + " or " + d + ") and " + r))
        .isEqualTo("(" + s + " or " + d + ") and " + r);
    assertThat(canonical("not (" + s + " or " + d + ")")).isEqualTo("not (" + s + " or " + d + ")");
    assertThat(canonical("not not " + s)).isEqualTo("not not " + s);
    assertThat(canonical("not subject.clearance > 3")).isEqualTo("not subject.clearance > 3");
  }

  @Test
  void literalsOfEveryTypeSurviveARoundTrip() {
    assertThat(canonical("subject.clearance > -5 and context.hour in [-1, 0, 23]"))
        .isEqualTo("subject.clearance > -5 and context.hour in [-1, 0, 23]");
    assertThat(canonical("9223372036854775807 > subject.clearance"))
        .isEqualTo("9223372036854775807 > subject.clearance");
    assertThat(canonical("subject.department == \"a\\\"b\\\\c\""))
        .isEqualTo("subject.department == \"a\\\"b\\\\c\"");
    assertThat(canonical("subject.department == \"😀 é\""))
        .isEqualTo("subject.department == \"😀 é\"");
    assertThat(canonical("resource.sensitivity != CONFIDENTIAL"))
        .isEqualTo("resource.sensitivity != CONFIDENTIAL");
    assertThat(canonical("subject.department == resource.department"))
        .isEqualTo("subject.department == resource.department");
    assertThat(canonical("exists(subject.clearance) == subject.suspended"))
        .isEqualTo("exists(subject.clearance) == subject.suspended");
  }

  @Test
  void generatedExpressionsAlwaysRoundTrip() {
    for (long seed = 0; seed < 600; seed++) {
      canonical(PolicyFuzz.of(seed, seed % 3 == 0).condition(3));
      canonical(PolicyFuzz.awkward(seed).condition(3));
    }
  }
}
