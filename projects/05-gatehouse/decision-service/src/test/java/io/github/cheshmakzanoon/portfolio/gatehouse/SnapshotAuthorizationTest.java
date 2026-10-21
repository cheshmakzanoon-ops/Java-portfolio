package io.github.cheshmakzanoon.portfolio.gatehouse;

import static org.assertj.core.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SnapshotAuthorizationTest {
  private final JsonMapper mapper = JsonMapper.builder().build();
  private final CompiledPolicies cache = new CompiledPolicies(mapper);
  private final SnapshotAuthorization authorization = new SnapshotAuthorization(cache);
  private final PolicyStore.Membership member =
      new PolicyStore.Membership(Set.of("reader"), "ops", 3L, false, false, true);
  private final PolicyStore.Resource resource =
      new PolicyStore.Resource("doc-7", "document", "ops", 2L, 1);

  private PolicyStore.Revision revision() {
    PolicyEngine.Definition definition =
        new PolicyEngine.Definition(
            Map.of("reader", Set.of()),
            List.of(new PolicyEngine.Grant("reader", "read", "document")),
            List.of(
                new PolicyEngine.Rule(
                    "read",
                    "read",
                    "document",
                    PolicyEngine.Effect.ALLOW,
                    "subject.department == resource.department and context.purpose == \"support\"")));
    return new PolicyStore.Revision(
        1, mapper.writeValueAsString(definition), PolicyEngine.compile(definition).digest());
  }

  @Test
  void callerContextCannotReplaceServerAttributesAndOwnsItsValues() {
    for (Map<String, Object> invalid :
        List.<Map<String, Object>>of(
            Map.of("subject.department", "ops"), Map.of("resource.classification", 0),
            Map.of("purpose", "x".repeat(257)), Map.of("hour", 12.5)))
      assertThatThrownBy(() -> SnapshotAuthorization.Context.from(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    Map<String, Object> input = new HashMap<>(Map.of("purpose", "support", "hour", 12));
    SnapshotAuthorization.Context context = SnapshotAuthorization.Context.from(input);
    input.put("purpose", "changed");
    PolicyStore.Snapshot snapshot = new PolicyStore.Snapshot(1, member, revision(), resource);
    assertThat(authorization.decide("harbor", snapshot, "read", "document", context).allowed())
        .isTrue();
    assertThat(context.hour()).isEqualTo(12L);
    assertThat(
            authorization
                .decide(
                    "harbor",
                    snapshot,
                    "read",
                    "document",
                    SnapshotAuthorization.Context.from(null))
                .reason())
        .isEqualTo("INDETERMINATE");
  }

  @Test
  void missingMembershipResourceAndPolicyDoNotEnterTheCompiler() {
    var context = SnapshotAuthorization.Context.from(null);
    assertThat(
            authorization
                .decide(
                    "harbor",
                    new PolicyStore.Snapshot(0, null, null, null),
                    "read",
                    "document",
                    context)
                .reason())
        .isEqualTo("NO_MEMBERSHIP");
    assertThat(
            authorization
                .decide(
                    "harbor",
                    new PolicyStore.Snapshot(1, member, null, null),
                    "read",
                    "document",
                    context)
                .reason())
        .isEqualTo("RESOURCE_NOT_FOUND");
    assertThat(
            authorization
                .decide(
                    "harbor",
                    new PolicyStore.Snapshot(1, member, null, resource),
                    "read",
                    "document",
                    context)
                .reason())
        .isEqualTo("NO_ACTIVE_POLICY");
    assertThat(
            authorization
                .decide(
                    "harbor",
                    new PolicyStore.Snapshot(1, member, revision(), resource),
                    "read",
                    "other",
                    context)
                .reason())
        .isEqualTo("RESOURCE_NOT_FOUND");
    assertThat(cache.size()).isZero();
  }

  @Test
  void invalidStoredAttributesAreDependencyFailuresRatherThanClientErrors() {
    PolicyStore.Membership corrupt =
        new PolicyStore.Membership(Set.of("reader"), "x".repeat(257), 3L, false, false, true);
    PolicyStore.Snapshot snapshot = new PolicyStore.Snapshot(1, corrupt, revision(), resource);
    assertThatThrownBy(
            () ->
                authorization.decide(
                    "harbor",
                    snapshot,
                    "read",
                    "document",
                    SnapshotAuthorization.Context.from(Map.of("purpose", "support"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("stored authorization attributes");
  }
}
