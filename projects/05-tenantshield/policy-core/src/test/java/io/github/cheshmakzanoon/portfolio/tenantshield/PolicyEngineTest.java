package io.github.cheshmakzanoon.portfolio.tenantshield;

import static io.github.cheshmakzanoon.portfolio.tenantshield.PolicyEngine.*;
import static io.github.cheshmakzanoon.portfolio.tenantshield.PolicyLanguage.*;
import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class PolicyEngineTest {
  @Test
  void publicationRejectsInvalidIdentifiersAndHonorsInclusiveCollectionBounds() {
    Map<String, Set<String>> roles = new TreeMap<>();
    for (int n = 0; n < 100; n++) roles.put("role" + n, Set.of());
    List<Rule> rules = new ArrayList<>();
    for (int n = 0; n < 200; n++)
      rules.add(new Rule("rule" + n, "read", "document", Effect.ALLOW, "true"));
    List<Grant> grants = new ArrayList<>();
    for (int n = 0; n < 2000; n++) grants.add(new Grant("role0", "read" + n, "document"));
    assertThat(compile(new Definition(roles, grants, rules)).definition().rules()).hasSize(200);
    roles.put("extra", Set.of());
    assertThatThrownBy(() -> compile(new Definition(roles, grants, rules)))
        .isInstanceOf(InvalidPolicy.class);
    roles.remove("extra");
    rules.add(new Rule("extra", "read", "document", Effect.ALLOW, "true"));
    assertThatThrownBy(() -> compile(new Definition(roles, grants, rules)))
        .isInstanceOf(InvalidPolicy.class);
    rules.removeLast();
    grants.add(new Grant("role0", "extra", "document"));
    assertThatThrownBy(() -> compile(new Definition(roles, grants, rules)))
        .isInstanceOf(InvalidPolicy.class);
    for (Grant invalid :
        List.of(
            new Grant("reader", "bad action", "document"), new Grant("reader", "read", "bad type")))
      assertThatThrownBy(
              () ->
                  compile(new Definition(Map.of("reader", Set.of()), List.of(invalid), List.of())))
          .isInstanceOf(InvalidPolicy.class);
    for (Rule invalid :
        List.of(
            new Rule("bad id", "read", "document", Effect.ALLOW, "true"),
            new Rule("valid", "bad action", "document", Effect.ALLOW, "true"),
            new Rule("valid", "read", "bad type", Effect.ALLOW, "true")))
      assertThatThrownBy(() -> compile(definition(List.of(invalid))))
          .isInstanceOf(InvalidPolicy.class);
    assertThat(
            compile(
                    definition(
                        List.of(
                            new Rule("x".repeat(128), "read", "document", Effect.ALLOW, "true"))))
                .definition()
                .rules())
        .hasSize(1);
    assertThatThrownBy(
            () ->
                compile(
                    definition(
                        List.of(
                            new Rule("x".repeat(129), "read", "document", Effect.ALLOW, "true")))))
        .isInstanceOf(InvalidPolicy.class);
  }

  @Test
  void contentDigestDistinguishesEverySecurityRelevantComponentAndDagReusesAncestors() {
    Definition original = definition(List.of(allow("true")));
    String digest = compile(original).digest();
    assertThat(compile(definition(List.of(allow("false")))).digest()).isNotEqualTo(digest);
    assertThat(
            compile(
                    new Definition(
                        original.parents(),
                        List.of(new Grant("reader", "other", "document")),
                        original.rules()))
                .digest())
        .isNotEqualTo(digest);
    assertThat(
            compile(
                    new Definition(
                        Map.of("reader", Set.of(), "editor", Set.of()),
                        original.grants(),
                        original.rules()))
                .digest())
        .isNotEqualTo(digest);
    var shared =
        compile(
            new Definition(
                Map.of(
                    "root",
                    Set.of(),
                    "a",
                    Set.of("root"),
                    "b",
                    Set.of("root"),
                    "c",
                    Set.of("a", "b")),
                List.of(),
                List.of()));
    assertThat(shared.roles(Set.of("c"))).containsExactlyInAnyOrder("root", "a", "b", "c");
    for (String invalid :
        List.of(
            "hasRole(\"root\") and true",
            "true or hasRole(\"root\")",
            "not hasRole(\"root\")",
            "hasRole(\"root\") == true",
            "true == hasRole(\"root\")",
            "hasRole(\"root\") in [true]"))
      assertThatThrownBy(() -> compile(definition(List.of(allow(invalid)))))
          .as(invalid)
          .isInstanceOf(InvalidPolicy.class)
          .hasMessageContaining("unknown");
  }

  static Definition definition(List<Rule> rules) {
    return new Definition(
        Map.of("reader", Set.of(), "editor", Set.of("reader")),
        List.of(new Grant("reader", "document:read", "document")),
        rules);
  }

  static Rule allow(String expression) {
    return new Rule("read-department", "document:read", "document", Effect.ALLOW, expression);
  }

  @Test
  void rejectsDuplicateGrantsBeforeDatabasePersistence() {
    Grant grant = new Grant("reader", "document:read", "document");
    assertThatThrownBy(
            () ->
                compile(
                    new Definition(
                        Map.of("reader", Set.of()), List.of(grant, grant), List.of(allow("true")))))
        .isInstanceOf(InvalidPolicy.class)
        .hasMessageContaining("duplicate grant");
  }

  @Test
  void typedEnumsAndMissingComparisons() {
    assertThat(
            evaluate(
                PolicyLanguage.compile("resource.sensitivity == CONFIDENTIAL"),
                new Inputs(Map.of("resource.sensitivity", Label.CONFIDENTIAL), Set.of())))
        .isEqualTo(Truth.TRUE);
    assertThat(
            evaluate(
                PolicyLanguage.compile("resource.sensitivity in [PUBLIC, INTERNAL]"),
                new Inputs(Map.of("resource.sensitivity", Label.INTERNAL), Set.of())))
        .isEqualTo(Truth.TRUE);
    assertThatThrownBy(() -> PolicyLanguage.compile("resource.sensitivity == \"PUBLIC\""))
        .isInstanceOf(InvalidPolicy.class);
    assertThatThrownBy(() -> PolicyLanguage.compile("PUBLIC < INTERNAL"))
        .isInstanceOf(InvalidPolicy.class);
    assertThat(
            evaluate(
                PolicyLanguage.compile("subject.clearance in [1]"), new Inputs(Map.of(), Set.of())))
        .isEqualTo(Truth.UNKNOWN);
  }

  @Test
  void inheritedGrantAndAbacBothRequired() {
    Compiled c =
        compile(
            definition(
                List.of(
                    allow(
                        "hasRole(\"reader\") and resource.department == subject.department and subject.clearance >= resource.classification"))));
    Map<String, Object> f =
        Map.of(
            "subject.department",
            "ops",
            "resource.department",
            "ops",
            "subject.clearance",
            3L,
            "resource.classification",
            2L);
    assertThat(decide(c, Set.of("editor"), "document:read", "document", f).allowed()).isTrue();
    assertThat(decide(c, Set.of(), "document:read", "document", f).reason())
        .isEqualTo("NO_RBAC_GRANT");
    assertThat(decide(c, Set.of("reader"), "document:write", "document", f).allowed()).isFalse();
    assertThat(decide(c, Set.of("reader"), "document:read", "other", f).allowed()).isFalse();
  }

  @Test
  void unknownNegationAndExplicitDenyAreConservative() {
    Definition d =
        definition(
            List.of(
                allow("true"),
                new Rule(
                    "suspend", "document:read", "document", Effect.DENY, "subject.suspended")));
    Compiled c = compile(d);
    assertThat(decide(c, Set.of("reader"), "document:read", "document", Map.of()).reason())
        .isEqualTo("INDETERMINATE");
    assertThat(
            decide(
                    c,
                    Set.of("reader"),
                    "document:read",
                    "document",
                    Map.of("subject.suspended", true))
                .reason())
        .isEqualTo("EXPLICIT_DENY");
    assertThat(
            evaluate(
                PolicyLanguage.compile("not (resource.classification == 7)"),
                new Inputs(Map.of(), Set.of())))
        .isEqualTo(Truth.UNKNOWN);
    assertThat(
            evaluate(
                PolicyLanguage.compile("exists(resource.classification)"),
                new Inputs(Map.of(), Set.of())))
        .isEqualTo(Truth.FALSE);
  }

  @Test
  void generatedExpressionsAndIndexedDecisionsAgreeWithOracle() {
    Random random = new Random(20261018);
    for (int n = 0; n < 2500; n++) {
      String expression = expression(random, 3);
      Map<String, Object> fields = new HashMap<>();
      if (random.nextBoolean()) fields.put("subject.clearance", (long) random.nextInt(5));
      if (random.nextBoolean()) fields.put("resource.classification", (long) random.nextInt(5));
      if (random.nextBoolean()) fields.put("subject.suspended", random.nextBoolean());
      Inputs i = new Inputs(fields, random.nextBoolean() ? Set.of("reader") : Set.of());
      Expr ast = PolicyLanguage.compile(expression);
      Object expected = ReferenceInterpreter.value(ast, i);
      Truth truth =
          expected == null ? Truth.UNKNOWN : (Boolean) expected ? Truth.TRUE : Truth.FALSE;
      assertThat(evaluate(ast, i)).as(expression).isEqualTo(truth);
      Definition d =
          definition(
              List.of(
                  allow(expression),
                  new Rule("deny", "document:read", "document", Effect.DENY, "subject.suspended"),
                  new Rule("irrelevant", "other", "document", Effect.DENY, "true")));
      assertThat(decide(compile(d), i.roles(), "document:read", "document", fields))
          .isEqualTo(
              ReferenceInterpreter.decide(d, i.roles(), "document:read", "document", fields));
    }
  }

  static String expression(Random r, int depth) {
    if (depth == 0)
      return List.of(
              "subject.clearance >= resource.classification",
              "subject.clearance != 2",
              "subject.clearance < 3",
              "subject.suspended",
              "exists(subject.clearance)",
              "hasRole(\"reader\")",
              "true",
              "false",
              "subject.clearance in [1, 3, 4]")
          .get(r.nextInt(9));
    return switch (r.nextInt(4)) {
      case 0 -> "not (" + expression(r, depth - 1) + ")";
      case 1 -> "(" + expression(r, depth - 1) + ") and (" + expression(r, depth - 1) + ")";
      case 2 -> "(" + expression(r, depth - 1) + ") or (" + expression(r, depth - 1) + ")";
      default -> expression(r, 0);
    };
  }

  @Test
  void rejectsUnsafeOrIllTypedOrOversizedPolicies() {
    for (String bad :
        List.of(
            "subject.unknown == 1",
            "context.clearance == 1",
            "subject.clearance == \"3\"",
            "true > false",
            "hasRole(reader)",
            "exists(1)",
            "1",
            "\"bad\\n\" == \"ok\"",
            "\"unterminated",
            "true; exit()",
            "true true",
            "resource.classification in [1,\"2\"]",
            "9223372036854775808 == 2",
            "true and 2",
            "not 2",
            "2 or false",
            "hasRole(\"\")")) {
      assertThatThrownBy(() -> PolicyLanguage.compile(bad))
          .as(bad)
          .isInstanceOf(InvalidPolicy.class);
    }
    assertThatThrownBy(() -> PolicyLanguage.compile("(".repeat(40) + "true" + ")".repeat(40)))
        .isInstanceOf(InvalidPolicy.class);
    assertThatThrownBy(() -> PolicyLanguage.compile(" ".repeat(MAX_BYTES + 1)))
        .isInstanceOf(InvalidPolicy.class);
    assertThatThrownBy(
            () -> PolicyLanguage.compile("subject.department == \"" + "x".repeat(257) + "\""))
        .isInstanceOf(InvalidPolicy.class);
    assertThatThrownBy(
            () -> PolicyLanguage.compile("subject.clearance in [" + "1,".repeat(65) + "1]"))
        .isInstanceOf(InvalidPolicy.class);
    assertThatThrownBy(() -> PolicyLanguage.compile("true and ".repeat(520) + "true"))
        .isInstanceOf(InvalidPolicy.class);
    assertThatThrownBy(() -> new Inputs(Map.of("subject.clearance", 1), Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Inputs(Map.of("context.root", true), Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void orderedStringIntegerBooleanAndEscapes() {
    for (String text :
        List.of(
            "\"a\" < \"b\"",
            "2 <= 2",
            "3 > 2",
            "3 >= 3",
            "-1 != 2",
            "true == true",
            "false != true",
            "resource.department in [\"a\",\"b\"]",
            "\"a\\\"b\" == \"a\\\"b\"",
            "\"a\\\\b\" == \"a\\\\b\""))
      assertThat(
              evaluate(
                  PolicyLanguage.compile(text),
                  new Inputs(Map.of("resource.department", "a"), Set.of())))
          .as(text)
          .isEqualTo(Truth.TRUE);
  }

  @Test
  void rejectsRoleCyclesMissingParentsDuplicatesAndUnknownPredicates() {
    assertThatThrownBy(
            () ->
                compile(
                    new Definition(
                        Map.of("a", Set.of("b"), "b", Set.of("a")), List.of(), List.of())))
        .hasMessageContaining("cycle");
    assertThatThrownBy(
            () -> compile(new Definition(Map.of("a", Set.of("b")), List.of(), List.of())))
        .hasMessageContaining("missing");
    assertThatThrownBy(() -> compile(definition(List.of(allow("hasRole(\"root\")")))))
        .hasMessageContaining("unknown");
    assertThatThrownBy(() -> compile(definition(List.of(allow("true"), allow("false")))))
        .hasMessageContaining("duplicate");
    assertThatThrownBy(
            () ->
                compile(
                    new Definition(Map.of(), List.of(new Grant("missing", "a", "r")), List.of())))
        .hasMessageContaining("unknown");
    assertThatThrownBy(
            () -> compile(new Definition(Map.of("bad role", Set.of()), List.of(), List.of())))
        .hasMessageContaining("identifier");
  }

  @Test
  void immutableClosureAndStableTrace() {
    Definition a =
        definition(
            List.of(
                new Rule("z", "document:read", "document", Effect.ALLOW, "true"),
                new Rule("a", "document:read", "document", Effect.ALLOW, "true")));
    List<Rule> reversed = new ArrayList<>(a.rules());
    Collections.reverse(reversed);
    Definition b = definition(reversed);
    Compiled x = compile(a), y = compile(b);
    assertThat(x.digest()).isEqualTo(y.digest());
    assertThat(decide(x, Set.of("editor"), "document:read", "document", Map.of()).matchedRuleIds())
        .containsExactly("a", "z");
    assertThatThrownBy(() -> x.closure().put("admin", Set.of()))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(
            decide(
                    compile(definition(List.of(allow("false")))),
                    Set.of("reader"),
                    "document:read",
                    "document",
                    Map.of())
                .reason())
        .isEqualTo("NO_ABAC_ALLOW");
  }
}
