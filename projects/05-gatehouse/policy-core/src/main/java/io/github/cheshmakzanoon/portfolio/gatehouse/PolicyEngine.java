package io.github.cheshmakzanoon.portfolio.gatehouse;

import static io.github.cheshmakzanoon.portfolio.gatehouse.PolicyLanguage.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Immutable candidate index, role closure, and conservative deterministic combining algorithm. */
public final class PolicyEngine {
  public enum Effect {
    ALLOW,
    DENY
  }

  public record Rule(
      String id, String action, String resourceType, Effect effect, String condition) {}

  public record Grant(String role, String action, String resourceType) {}

  public record Definition(Map<String, Set<String>> parents, List<Grant> grants, List<Rule> rules) {
    public Definition {
      Map<String, Set<String>> p = new TreeMap<>();
      parents.forEach((k, v) -> p.put(k, Set.copyOf(v)));
      parents = Collections.unmodifiableMap(p);
      grants = List.copyOf(grants);
      rules = List.copyOf(rules);
    }
  }

  public record Candidate(Rule rule, Expr expression) {}

  public record Decision(boolean allowed, String reason, List<String> matchedRuleIds) {
    public Decision {
      matchedRuleIds = List.copyOf(matchedRuleIds);
    }
  }

  private record Scope(String action, String resourceType) {}

  private record Plan(
      Definition definition,
      Map<String, Set<String>> closure,
      Map<String, List<Candidate>> index,
      String digest,
      Map<Scope, Set<String>> grants) {}

  public static final class Compiled {
    private final Plan plan;

    private Compiled(Plan plan) {
      this.plan = plan;
    }

    /** Retains the existing construction API, but never trusts caller-provided derived state. */
    public Compiled(
        Definition definition,
        Map<String, Set<String>> closure,
        Map<String, List<Candidate>> index,
        String digest) {
      this(plan(definition));
      if (!plan.closure().equals(closure)
          || !plan.index().equals(index)
          || !plan.digest().equals(digest))
        throw new InvalidPolicy("compiled state does not match its definition");
    }

    public Definition definition() {
      return plan.definition();
    }

    public Map<String, Set<String>> closure() {
      return plan.closure();
    }

    public Map<String, List<Candidate>> index() {
      return plan.index();
    }

    public String digest() {
      return plan.digest();
    }

    public Set<String> roles(Set<String> assigned) {
      Set<String> all = new TreeSet<>();
      for (String r : assigned) all.addAll(closure().getOrDefault(r, Set.of()));
      return Set.copyOf(all);
    }

    private boolean granted(Set<String> roles, String action, String resourceType) {
      return plan.grants().getOrDefault(new Scope(action, resourceType), Set.of()).stream()
          .anyMatch(roles::contains);
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Compiled compiled && plan.equals(compiled.plan);
    }

    @Override
    public int hashCode() {
      return plan.hashCode();
    }

    @Override
    public String toString() {
      return "Compiled[definition="
          + definition()
          + ", closure="
          + closure()
          + ", index="
          + index()
          + ", digest="
          + digest()
          + "]";
    }
  }

  private PolicyEngine() {}

  public static Compiled compile(Definition d) {
    return new Compiled(plan(d));
  }

  private static Plan plan(Definition d) {
    if (d.parents().size() > 100 || d.rules().size() > 200 || d.grants().size() > 2000)
      throw new InvalidPolicy("policy collection bounds");
    Map<String, Set<String>> closure = new TreeMap<>();
    for (String role : d.parents().keySet()) visit(role, d.parents(), closure, new HashSet<>());
    Set<Grant> uniqueGrants = new HashSet<>();
    Map<Scope, Set<String>> grants = new HashMap<>();
    for (Grant g : d.grants()) {
      if (!uniqueGrants.add(g)) throw new InvalidPolicy("duplicate grant");
      identifier(g.action());
      identifier(g.resourceType());
      if (!closure.containsKey(g.role())) throw new InvalidPolicy("grant references unknown role");
      grants
          .computeIfAbsent(new Scope(g.action(), g.resourceType()), scope -> new TreeSet<>())
          .add(g.role());
    }
    Map<String, List<Candidate>> index = new TreeMap<>();
    Set<String> ids = new HashSet<>();
    for (Rule r : d.rules()) {
      identifier(r.id());
      identifier(r.action());
      identifier(r.resourceType());
      if (!ids.add(r.id()) || r.effect() == null) throw new InvalidPolicy("duplicate/invalid rule");
      Expr e = PolicyLanguage.compile(r.condition());
      validateRoles(e, closure.keySet());
      index
          .computeIfAbsent(key(r.action(), r.resourceType()), x -> new ArrayList<>())
          .add(new Candidate(r, e));
    }
    Map<String, List<Candidate>> frozen = new TreeMap<>();
    index.forEach(
        (k, v) ->
            frozen.put(k, v.stream().sorted(Comparator.comparing(c -> c.rule().id())).toList()));
    String canonical =
        d.parents().entrySet().stream().map(e -> e.getKey() + new TreeSet<>(e.getValue())).toList()
            + "|"
            + d.grants().stream().map(g -> encoded(g.toString())).sorted().toList()
            + "|"
            + d.rules().stream().map(r -> encoded(r.toString())).sorted().toList();
    try {
      Map<Scope, Set<String>> frozenGrants = new HashMap<>();
      grants.forEach((scope, roles) -> frozenGrants.put(scope, Set.copyOf(roles)));
      return new Plan(
          d,
          Collections.unmodifiableMap(closure),
          Collections.unmodifiableMap(frozen),
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(canonical.getBytes(StandardCharsets.UTF_8))),
          Map.copyOf(frozenGrants));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String encoded(String value) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static void identifier(String s) {
    if (s == null || s.isBlank() || s.length() > 128 || !s.matches("[A-Za-z0-9_:.\\-]+"))
      throw new InvalidPolicy("invalid identifier");
  }

  private static Set<String> visit(
      String role,
      Map<String, Set<String>> graph,
      Map<String, Set<String>> result,
      Set<String> stack) {
    identifier(role);
    if (result.containsKey(role)) return result.get(role);
    if (!graph.containsKey(role)) throw new InvalidPolicy("missing parent role");
    if (!stack.add(role)) throw new InvalidPolicy("role cycle");
    Set<String> all = new TreeSet<>();
    all.add(role);
    for (String parent : graph.get(role)) all.addAll(visit(parent, graph, result, stack));
    stack.remove(role);
    Set<String> out = Set.copyOf(all);
    result.put(role, out);
    return out;
  }

  private static void validateRoles(Expr e, Set<String> roles) {
    switch (e) {
      case HasRole r -> {
        if (!roles.contains(r.role())) throw new InvalidPolicy("unknown role predicate");
      }
      case Binary b -> {
        validateRoles(b.left(), roles);
        validateRoles(b.right(), roles);
      }
      case Negate n -> validateRoles(n.child(), roles);
      case Compare c -> {
        validateRoles(c.left(), roles);
        validateRoles(c.right(), roles);
      }
      case In n -> validateRoles(n.value(), roles);
      default -> {}
    }
  }

  private static String key(String action, String type) {
    return action + "\u0000" + type;
  }

  public static Decision decide(
      Compiled policy,
      Set<String> assigned,
      String action,
      String resourceType,
      Map<String, Object> fields) {
    Set<String> roles = policy.roles(assigned);
    Inputs inputs = new Inputs(fields, roles);
    List<String> matched = new ArrayList<>();
    boolean allow = false, deny = false, unknown = false;
    for (Candidate c : policy.index().getOrDefault(key(action, resourceType), List.of())) {
      Truth truth;
      try {
        truth = PolicyLanguage.evaluate(c.expression(), inputs);
      } catch (RuntimeException e) {
        truth = Truth.UNKNOWN;
      }
      if (truth == Truth.UNKNOWN) unknown = true;
      else if (truth == Truth.TRUE) {
        matched.add(c.rule().id());
        if (c.rule().effect() == Effect.DENY) deny = true;
        else allow = true;
      }
    }
    if (deny) return new Decision(false, "EXPLICIT_DENY", matched);
    if (unknown) return new Decision(false, "INDETERMINATE", matched);
    boolean grant = policy.granted(roles, action, resourceType);
    return grant && allow
        ? new Decision(true, "MATCHED_ALLOW", matched)
        : new Decision(false, grant ? "NO_ABAC_ALLOW" : "NO_RBAC_GRANT", matched);
  }
}
