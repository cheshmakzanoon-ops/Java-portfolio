package io.github.cheshmakzanoon.portfolio.tenantshield;

import static io.github.cheshmakzanoon.portfolio.tenantshield.PolicyLanguage.*;

import java.util.*;

/** Deliberately unindexed, eager AST interpreter used only as an independent oracle. */
final class ReferenceInterpreter {
  static Object value(Expr x, Inputs i) {
    return switch (x) {
      case Literal l -> l.value();
      case Field f -> i.fields().get(f.name());
      case Exists e -> i.fields().containsKey(e.field().name());
      case HasRole h -> i.roles().contains(h.role());
      case Negate n -> {
        Object v = value(n.child(), i);
        yield v == null ? null : !(Boolean) v;
      }
      case Binary b -> {
        Object a = value(b.left(), i), c = value(b.right(), i);
        if (b.op().equals("and")) {
          if (Boolean.FALSE.equals(a) || Boolean.FALSE.equals(c)) yield Boolean.FALSE;
          if (a == null || c == null) yield null;
          yield Boolean.TRUE;
        }
        if (Boolean.TRUE.equals(a) || Boolean.TRUE.equals(c)) yield Boolean.TRUE;
        if (a == null || c == null) yield null;
        yield Boolean.FALSE;
      }
      case In n -> {
        Object a = value(n.value(), i);
        yield a == null ? null : n.values().stream().map(Literal::value).toList().contains(a);
      }
      case Compare c -> {
        Object a = value(c.left(), i), b = value(c.right(), i);
        if (a == null || b == null) yield null;
        int v = compare(a, b);
        yield switch (c.op()) {
          case "==" -> Objects.equals(a, b);
          case "!=" -> !Objects.equals(a, b);
          case ">" -> v > 0;
          case "<" -> v < 0;
          case ">=" -> v >= 0;
          case "<=" -> v <= 0;
          default -> throw new AssertionError();
        };
      }
    };
  }

  @SuppressWarnings("unchecked")
  private static int compare(Object a, Object b) {
    return ((Comparable<Object>) a).compareTo(b);
  }

  static PolicyEngine.Decision decide(
      PolicyEngine.Definition d,
      Set<String> assigned,
      String action,
      String type,
      Map<String, Object> fields) {
    Set<String> roles = new HashSet<>(assigned);
    boolean changed = true;
    while (changed) {
      changed = false;
      for (String r : new HashSet<>(roles))
        for (String p : d.parents().getOrDefault(r, Set.of())) changed |= roles.add(p);
    }
    Inputs i = new Inputs(fields, roles);
    boolean allow = false, deny = false, unknown = false;
    List<String> ids = new ArrayList<>();
    for (PolicyEngine.Rule r : d.rules()) {
      if (!r.action().equals(action) || !r.resourceType().equals(type)) continue;
      Object result = value(compile(r.condition()), i);
      if (result == null) unknown = true;
      else if ((Boolean) result) {
        ids.add(r.id());
        if (r.effect() == PolicyEngine.Effect.DENY) deny = true;
        else allow = true;
      }
    }
    Collections.sort(ids);
    boolean grant =
        d.grants().stream()
            .anyMatch(
                g ->
                    roles.contains(g.role())
                        && g.action().equals(action)
                        && g.resourceType().equals(type));
    String reason =
        deny
            ? "EXPLICIT_DENY"
            : unknown
                ? "INDETERMINATE"
                : grant && allow ? "MATCHED_ALLOW" : grant ? "NO_ABAC_ALLOW" : "NO_RBAC_GRANT";
    return new PolicyEngine.Decision(reason.equals("MATCHED_ALLOW"), reason, ids);
  }
}
