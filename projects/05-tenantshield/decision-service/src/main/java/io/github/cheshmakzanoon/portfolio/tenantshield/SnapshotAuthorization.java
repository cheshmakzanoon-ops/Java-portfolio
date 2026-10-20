package io.github.cheshmakzanoon.portfolio.tenantshield;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Binds server-owned snapshot attributes to a separately validated caller context. */
final class SnapshotAuthorization {
  record Context(String purpose, Long hour) {
    Context {
      if (purpose != null) new PolicyLanguage.Inputs(Map.of("context.purpose", purpose), Set.of());
    }

    static Context from(Map<String, Object> input) {
      if (input == null) return new Context(null, null);
      if (input.size() > 2) throw new IllegalArgumentException("context limits");
      String purpose = null;
      Long hour = null;
      for (var entry : input.entrySet()) {
        if ("purpose".equals(entry.getKey()) && entry.getValue() instanceof String value)
          purpose = value;
        else if ("hour".equals(entry.getKey())
            && (entry.getValue() instanceof Integer || entry.getValue() instanceof Long))
          hour = ((Number) entry.getValue()).longValue();
        else throw new IllegalArgumentException("unapproved context field/type");
      }
      return new Context(purpose, hour);
    }
  }

  private final CompiledPolicies policies;

  SnapshotAuthorization(CompiledPolicies policies) {
    this.policies = policies;
  }

  PolicyEngine.Decision decide(
      String tenant,
      PolicyStore.Snapshot snapshot,
      String action,
      String resourceType,
      Context context) {
    if (snapshot.membership() == null || !snapshot.membership().active())
      return denied("NO_MEMBERSHIP");
    if (snapshot.resource() == null || !snapshot.resource().type().equals(resourceType))
      return denied("RESOURCE_NOT_FOUND");
    if (snapshot.policy() == null) return denied("NO_ACTIVE_POLICY");
    Map<String, Object> fields = new HashMap<>();
    put(fields, "subject.department", snapshot.membership().department());
    put(fields, "subject.clearance", snapshot.membership().clearance());
    put(fields, "subject.suspended", snapshot.membership().suspended());
    put(fields, "resource.department", snapshot.resource().department());
    put(fields, "resource.classification", snapshot.resource().classification());
    put(fields, "resource.type", snapshot.resource().type());
    put(fields, "resource.id", snapshot.resource().id());
    if (snapshot.resource().classification() != null)
      fields.put(
          "resource.sensitivity",
          snapshot.resource().classification() <= 0
              ? PolicyLanguage.Label.PUBLIC
              : snapshot.resource().classification() == 1
                  ? PolicyLanguage.Label.INTERNAL
                  : PolicyLanguage.Label.CONFIDENTIAL);
    put(fields, "context.purpose", context.purpose());
    put(fields, "context.hour", context.hour());
    try {
      return PolicyEngine.decide(
          policies.resolve(tenant, snapshot.policy()),
          snapshot.membership().roles(),
          action,
          resourceType,
          fields);
    } catch (IllegalArgumentException invalidStoredAttributes) {
      throw new IllegalStateException(
          "stored authorization attributes are invalid", invalidStoredAttributes);
    }
  }

  private static void put(Map<String, Object> fields, String key, Object value) {
    if (value != null) fields.put(key, value);
  }

  private static PolicyEngine.Decision denied(String reason) {
    return new PolicyEngine.Decision(false, reason, List.of());
  }
}
