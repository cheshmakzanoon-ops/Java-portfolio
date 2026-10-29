package io.github.cheshmakzanoon.portfolio.gatehouse;

import static io.github.cheshmakzanoon.portfolio.gatehouse.PolicyLanguage.*;

import java.util.*;

/**
 * Static analysis of a compiled policy. Every ERROR and WARNING about a rule that "can never" do
 * something is a proof: the search is exhaustive over a finite set of representative attribute
 * values (every mentioned constant, its neighbours, and the absent state), and anything the bounded
 * search cannot settle is reported as inconclusive rather than guessed.
 *
 * <p>Conditions are evaluated in Kleene three-valued logic, so a missing attribute makes a
 * comparison UNKNOWN, never TRUE. A rule is "applicable" only when its condition is TRUE, and an
 * ALLOW decision additionally needs every DENY rule in scope to be definitely FALSE, so the checks
 * below reason about TRUE and FALSE separately instead of treating FALSE as "not TRUE".
 */
public final class PolicyAnalyzer {
  public enum Severity {
    ERROR,
    WARNING,
    INFO
  }

  /**
   * @param ruleId the rule concerned, or {@code null} for a scope-level or role-level finding
   */
  public record Finding(Severity severity, String code, String ruleId, String message) {}

  public record Report(String policyDigest, List<Finding> findings) {
    public Report {
      findings = List.copyOf(findings);
    }

    public boolean hasErrors() {
      return findings.stream().anyMatch(f -> f.severity() == Severity.ERROR);
    }
  }

  /** Search-node budget for one satisfiability question. */
  static final int STEP_LIMIT = 50_000;

  /** Search-node budget for a whole analysis; later questions become inconclusive. */
  static final int TOTAL_STEP_LIMIT = 2_000_000;

  private PolicyAnalyzer() {}

  // ------------------------------------------------------------------------------------------
  // Normal form: negation-free formulas over atoms. TRUE(e) and FALSE(e) are both expressed this
  // way, because Kleene negation keeps UNKNOWN unknown and so is not a Boolean complement.
  // ------------------------------------------------------------------------------------------

  private enum Op {
    EQ("=="),
    NE("!="),
    LT("<"),
    LE("<="),
    GT(">"),
    GE(">=");

    final String symbol;

    Op(String symbol) {
      this.symbol = symbol;
    }

    static Op of(String symbol) {
      for (Op op : values()) if (op.symbol.equals(symbol)) return op;
      throw new InvalidPolicy("bad operator");
    }

    Op inverse() {
      return switch (this) {
        case EQ -> NE;
        case NE -> EQ;
        case LT -> GE;
        case LE -> GT;
        case GT -> LE;
        case GE -> LT;
      };
    }

    /** The operator that holds with the operands swapped. */
    Op swapped() {
      return switch (this) {
        case LT -> GT;
        case LE -> GE;
        case GT -> LT;
        case GE -> LE;
        default -> this;
      };
    }

    boolean holds(int order) {
      return switch (this) {
        case EQ -> order == 0;
        case NE -> order != 0;
        case LT -> order < 0;
        case LE -> order <= 0;
        case GT -> order > 0;
        case GE -> order >= 0;
      };
    }
  }

  private sealed interface Atom permits Cmp, Member, Presence, Role, Rel {}

  /** TRUE iff the field is present and the comparison holds. */
  private record Cmp(String field, Op op, Object constant) implements Atom {}

  /** TRUE iff the field is present and its membership in the set equals {@code in}. */
  private record Member(String field, Set<Object> values, boolean in) implements Atom {}

  /**
   * TRUE iff both fields are present and the relation holds. The relation itself is deliberately
   * not modelled: the atom is a free proposition whose truth requires only that both fields are
   * present. That over-approximates satisfiability, so a claim that something is impossible (or,
   * through complement, that something is certain) remains a proof.
   */
  private record Rel(String left, Op op, String right) implements Atom {}

  /** Presence of a field; total, never UNKNOWN. */
  private record Presence(String field, boolean present) implements Atom {}

  /** Role membership of the effective (closed) role set; total, never UNKNOWN. */
  private record Role(String name, boolean has) implements Atom {}

  private sealed interface Node permits And, Or, Lit, Const {}

  private record And(List<Node> parts) implements Node {}

  private record Or(List<Node> parts) implements Node {}

  private record Lit(Atom atom) implements Node {}

  private record Const(boolean value) implements Node {}

  private static final Node TRUE_NODE = new Const(true), FALSE_NODE = new Const(false);

  /** The formula under which {@code e} evaluates to TRUE (positive) or to FALSE (negative). */
  private static Node normal(Expr e, boolean positive) {
    return switch (e) {
      case Literal l -> new Const(((Boolean) l.value()) == positive);
      case Field f -> new Lit(new Cmp(f.name(), Op.EQ, positive));
      case Exists x -> new Lit(new Presence(x.field().name(), positive));
      case HasRole r -> new Lit(new Role(r.role(), positive));
      case Negate n -> normal(n.child(), !positive);
      case Binary b -> {
        Node left = normal(b.left(), positive), right = normal(b.right(), positive);
        // De Morgan holds in Kleene logic: NOT(a AND b) is FALSE-of-a OR FALSE-of-b.
        yield (b.op().equals("and") == positive) ? and(left, right) : or(left, right);
      }
      case Compare c -> compare(Op.of(c.op()), c.left(), c.right(), positive);
      case In n -> membership(n, positive);
    };
  }

  private static Node and(Node left, Node right) {
    if (left instanceof Const c) return c.value() ? right : FALSE_NODE;
    if (right instanceof Const c) return c.value() ? left : FALSE_NODE;
    return new And(List.of(left, right));
  }

  private static Node or(Node left, Node right) {
    if (left instanceof Const c) return c.value() ? TRUE_NODE : right;
    if (right instanceof Const c) return c.value() ? TRUE_NODE : left;
    return new Or(List.of(left, right));
  }

  private static Node compare(Op op, Expr left, Expr right, boolean positive) {
    Op effective = positive ? op : op.inverse();
    boolean leftConstant = left instanceof Literal, rightConstant = right instanceof Literal;
    if (leftConstant && rightConstant)
      return new Const(effective.holds(order(((Literal) left).value(), ((Literal) right).value())));
    if (left.type() == Type.BOOLEAN) return booleanCompare(effective, left, right);
    if (left instanceof Field f && rightConstant)
      return new Lit(new Cmp(f.name(), effective, ((Literal) right).value()));
    if (leftConstant && right instanceof Field f)
      return new Lit(new Cmp(f.name(), effective.swapped(), ((Literal) left).value()));
    Field a = (Field) left, b = (Field) right;
    // Equal names compare equal to themselves, which is decidable. Distinct attributes become a
    // free relation atom (see Rel).
    if (a.name().equals(b.name()))
      return effective == Op.EQ || effective == Op.LE || effective == Op.GE
          ? new Lit(new Presence(a.name(), true))
          : FALSE_NODE;
    return new Lit(new Rel(a.name(), effective, b.name()));
  }

  /**
   * Boolean operands are total (role/existence) or three-valued (a boolean attribute), so equality
   * is expressed through each side's TRUE and FALSE formulas; UNKNOWN on either side is neither.
   */
  private static Node booleanCompare(Op effective, Expr left, Expr right) {
    Node leftTrue = normal(left, true), leftFalse = normal(left, false);
    Node rightTrue = normal(right, true), rightFalse = normal(right, false);
    Node same = or(and(leftTrue, rightTrue), and(leftFalse, rightFalse));
    Node different = or(and(leftTrue, rightFalse), and(leftFalse, rightTrue));
    return effective == Op.EQ ? same : different;
  }

  private static Node membership(In n, boolean positive) {
    Set<Object> values = new HashSet<>();
    for (Literal literal : n.values()) values.add(literal.value());
    if (n.value() instanceof Literal literal)
      return new Const(values.contains(literal.value()) == positive);
    if (n.value() instanceof Field f) return new Lit(new Member(f.name(), values, positive));
    // A total boolean test (exists/hasRole): determined, so membership is exact.
    Node isTrue = normal(n.value(), true), isFalse = normal(n.value(), false);
    Node result = FALSE_NODE;
    for (boolean candidate : new boolean[] {true, false}) {
      if (values.contains(candidate) == positive) result = or(result, candidate ? isTrue : isFalse);
    }
    return result;
  }

  private static int order(Object left, Object right) {
    if (left instanceof Long a) return Long.compare(a, (Long) right);
    if (left instanceof String a) return a.compareTo((String) right);
    if (left instanceof Label a) return a.compareTo((Label) right);
    return Boolean.compare((Boolean) left, (Boolean) right);
  }

  /** Boolean complement of a TRUE-formula, so "not TRUE" includes UNKNOWN (absent attributes). */
  private static Node complement(Node node) {
    return switch (node) {
      case Const c -> new Const(!c.value());
      case And a -> new Or(a.parts().stream().map(PolicyAnalyzer::complement).toList());
      case Or o -> new And(o.parts().stream().map(PolicyAnalyzer::complement).toList());
      case Lit l ->
          switch (l.atom()) {
            case Presence p -> new Lit(new Presence(p.field(), !p.present()));
            case Role r -> new Lit(new Role(r.name(), !r.has()));
            case Cmp c ->
                or(
                    new Lit(new Presence(c.field(), false)),
                    new Lit(new Cmp(c.field(), c.op().inverse(), c.constant())));
            case Member m ->
                or(
                    new Lit(new Presence(m.field(), false)),
                    new Lit(new Member(m.field(), m.values(), !m.in())));
            case Rel r ->
                or(
                    or(
                        new Lit(new Presence(r.left(), false)),
                        new Lit(new Presence(r.right(), false))),
                    new Lit(new Rel(r.left(), r.op().inverse(), r.right())));
          };
    };
  }

  // ------------------------------------------------------------------------------------------
  // Satisfiability: depth-first search over disjunctions with early pruning on consistency.
  // ------------------------------------------------------------------------------------------

  private static final class Inconclusive extends RuntimeException {
    Inconclusive() {
      super(null, null, false, false);
    }
  }

  private static final class Solver {
    private final Map<String, Set<String>> closure;
    private final int[] budget;
    private int steps;

    Solver(Map<String, Set<String>> closure, int[] budget) {
      this.closure = closure;
      this.budget = budget;
    }

    /** True when some input satisfies every node; throws {@link Inconclusive} past the budget. */
    boolean satisfiable(List<Node> nodes) {
      steps = 0;
      return search(new ArrayDeque<>(nodes), new ArrayList<>());
    }

    private boolean search(Deque<Node> pending, List<Atom> chosen) {
      if (++steps > STEP_LIMIT || --budget[0] < 0) throw new Inconclusive();
      if (pending.isEmpty()) return true;
      Node next = pending.removeFirst();
      return switch (next) {
        case Const c -> c.value() && search(pending, chosen);
        case Lit l -> {
          chosen.add(l.atom());
          boolean result = consistent(chosen) && search(pending, chosen);
          chosen.removeLast();
          yield result;
        }
        case And a -> {
          for (int i = a.parts().size() - 1; i >= 0; i--) pending.addFirst(a.parts().get(i));
          yield search(pending, chosen);
        }
        case Or o -> {
          for (Node alternative : o.parts()) {
            Deque<Node> branch = new ArrayDeque<>(pending);
            branch.addFirst(alternative);
            if (search(branch, chosen)) yield true;
          }
          yield false;
        }
      };
    }

    private boolean consistent(List<Atom> atoms) {
      Map<String, List<Atom>> byField = new HashMap<>();
      Set<String> mustHave = new HashSet<>(), mustLack = new HashSet<>();
      for (Atom atom : atoms) {
        switch (atom) {
          case Cmp c -> byField.computeIfAbsent(c.field(), k -> new ArrayList<>()).add(c);
          case Member m -> byField.computeIfAbsent(m.field(), k -> new ArrayList<>()).add(m);
          case Presence p -> (p.present() ? mustHave : mustLack).add(p.field());
          case Rel r -> {
            mustHave.add(r.left());
            mustHave.add(r.right());
          }
          case Role r -> {}
        }
      }
      for (String field : mustLack)
        if (mustHave.contains(field) || byField.containsKey(field)) return false;
      for (var entry : byField.entrySet())
        if (!someValueSatisfies(entry.getKey(), entry.getValue())) return false;
      Set<String> positive = new HashSet<>(), negative = new HashSet<>();
      for (Atom atom : atoms)
        if (atom instanceof Role r) (r.has() ? positive : negative).add(r.name());
      Set<String> held = new HashSet<>();
      // The smallest effective role set containing the required roles is the union of their
      // closures, so a negative requirement is satisfiable exactly when that union avoids it.
      for (String role : positive) held.addAll(closure.getOrDefault(role, Set.of(role)));
      for (String role : negative) if (held.contains(role)) return false;
      return true;
    }

    private boolean someValueSatisfies(String field, List<Atom> atoms) {
      for (Object candidate : candidates(field, atoms)) {
        boolean all = true;
        for (Atom atom : atoms) {
          if (atom instanceof Cmp c) all = c.op().holds(order(candidate, c.constant()));
          else if (atom instanceof Member m) all = m.values().contains(candidate) == m.in();
          if (!all) break;
        }
        if (all) return true;
      }
      return false;
    }

    /**
     * Truth of every atom is constant on each open gap between consecutive mentioned constants, so
     * the constants, their immediate neighbours and the type's extremes represent every case.
     */
    private List<Object> candidates(String field, List<Atom> atoms) {
      Type type = FIELDS.get(field);
      Set<Object> seen = new LinkedHashSet<>();
      for (Atom atom : atoms) {
        if (atom instanceof Cmp c) seen.add(c.constant());
        else if (atom instanceof Member m) seen.addAll(m.values());
      }
      List<Object> out = new ArrayList<>(seen);
      switch (type) {
        case BOOLEAN -> out.addAll(List.of(true, false));
        case ENUM -> out.addAll(Arrays.asList(Label.values()));
        case INTEGER -> {
          out.addAll(List.of(0L, Long.MIN_VALUE, Long.MAX_VALUE));
          for (Object c : seen) {
            long v = (Long) c;
            if (v > Long.MIN_VALUE) out.add(v - 1);
            if (v < Long.MAX_VALUE) out.add(v + 1);
          }
        }
        case STRING -> {
          out.add("");
          for (Object c : seen) out.add(c + "\u0000");
        }
      }
      return out;
    }
  }

  // ------------------------------------------------------------------------------------------
  // Findings
  // ------------------------------------------------------------------------------------------

  /** Analyses a compiled policy. The result is deterministic for a given policy. */
  public static Report analyze(PolicyEngine.Compiled policy) {
    Collector collector = new Collector(policy);
    collector.run();
    List<Finding> findings = new ArrayList<>(collector.findings);
    findings.sort(
        Comparator.comparing(Finding::severity)
            .thenComparing(Finding::code)
            .thenComparing(f -> f.ruleId() == null ? "" : f.ruleId())
            .thenComparing(Finding::message));
    return new Report(policy.digest(), findings);
  }

  private static final class Collector {
    final PolicyEngine.Compiled policy;
    final List<Finding> findings = new ArrayList<>();
    final int[] budget = {TOTAL_STEP_LIMIT};
    final Solver solver;
    final Set<String> limited = new TreeSet<>();

    Collector(PolicyEngine.Compiled policy) {
      this.policy = policy;
      this.solver = new Solver(policy.closure(), budget);
    }

    void add(Severity severity, String code, String ruleId, String message) {
      findings.add(new Finding(severity, code, ruleId, message));
    }

    /** Null when the question could not be settled within budget. */
    Boolean satisfiable(String ruleId, Node... nodes) {
      try {
        return solver.satisfiable(List.of(nodes));
      } catch (Inconclusive limit) {
        limited.add(ruleId);
        return null;
      }
    }

    void run() {
      record Scope(String action, String type) {}
      Map<Scope, List<PolicyEngine.Candidate>> scopes =
          new TreeMap<>(Comparator.comparing(Scope::action).thenComparing(Scope::type));
      for (var candidates : policy.index().values())
        for (PolicyEngine.Candidate c : candidates)
          scopes
              .computeIfAbsent(
                  new Scope(c.rule().action(), c.rule().resourceType()), k -> new ArrayList<>())
              .add(c);
      Set<Scope> granted = new HashSet<>();
      for (PolicyEngine.Grant g : policy.definition().grants())
        granted.add(new Scope(g.action(), g.resourceType()));

      for (var scope : scopes.entrySet()) {
        Scope s = scope.getKey();
        String where = "(" + s.action() + ", " + s.type() + ")";
        List<PolicyEngine.Candidate> candidates = scope.getValue();
        List<PolicyEngine.Candidate> allows =
            candidates.stream()
                .filter(c -> c.rule().effect() == PolicyEngine.Effect.ALLOW)
                .toList();
        List<PolicyEngine.Candidate> denies =
            candidates.stream().filter(c -> c.rule().effect() == PolicyEngine.Effect.DENY).toList();
        duplicates(candidates);
        for (PolicyEngine.Candidate c : candidates) {
          neverMatches(c);
          alwaysMatches(c);
          setDuplicates(c);
        }
        Set<String> live = new TreeSet<>();
        for (PolicyEngine.Candidate allow : allows) {
          if (!granted.contains(s)) {
            add(
                Severity.WARNING,
                "ALLOW_WITHOUT_GRANT",
                allow.rule().id(),
                "No role is granted "
                    + where
                    + ", so this ALLOW rule can never lead to an ALLOW decision.");
            continue;
          }
          if (deadAllow(allow, denies)) continue;
          live.add(allow.rule().id());
        }
        if (!allows.isEmpty() && granted.contains(s) && live.isEmpty())
          add(
              Severity.ERROR,
              "SCOPE_NEVER_ALLOWS",
              null,
              "No input can produce an ALLOW decision for "
                  + where
                  + ": every ALLOW rule is unreachable.");
      }
      for (Scope grant : granted)
        if (!scopes.containsKey(grant)
            || scopes.get(grant).stream()
                .noneMatch(c -> c.rule().effect() == PolicyEngine.Effect.ALLOW))
          add(
              Severity.WARNING,
              "GRANT_WITHOUT_ALLOW_RULE",
              null,
              "Roles are granted ("
                  + grant.action()
                  + ", "
                  + grant.type()
                  + ") but no ALLOW rule applies to it, so every request is denied with NO_ABAC_ALLOW.");
      unusedRoles();
      for (String id : limited)
        add(
            Severity.INFO,
            "ANALYSIS_LIMIT",
            id,
            "The analysis budget was reached, so some checks for this rule are inconclusive.");
    }

    void neverMatches(PolicyEngine.Candidate c) {
      Boolean sat = satisfiable(c.rule().id(), normal(c.expression(), true));
      if (sat != null && !sat)
        add(
            Severity.WARNING,
            "NEVER_MATCHES",
            c.rule().id(),
            "The condition can never be TRUE ("
                + PolicyLanguage.format(c.expression())
                + "), so this rule has no effect.");
    }

    void alwaysMatches(PolicyEngine.Candidate c) {
      // Not TRUE means FALSE or UNKNOWN, so absent attributes are part of the search space.
      Boolean notTrue = satisfiable(c.rule().id(), complement(normal(c.expression(), true)));
      if (notTrue == null || notTrue) return;
      if (c.rule().effect() == PolicyEngine.Effect.DENY)
        add(
            Severity.WARNING,
            "DENY_ALWAYS_APPLIES",
            c.rule().id(),
            "This DENY condition is TRUE for every request, so everything in its scope is denied.");
      else
        add(
            Severity.INFO,
            "ALLOW_UNCONDITIONAL",
            c.rule().id(),
            "This ALLOW condition is TRUE for every request; only role grants and DENY rules restrict access.");
    }

    /** True when the rule cannot contribute to an ALLOW decision because of DENY rules. */
    boolean deadAllow(PolicyEngine.Candidate allow, List<PolicyEngine.Candidate> denies) {
      String id = allow.rule().id();
      Boolean alive = satisfiable(id, normal(allow.expression(), true));
      if (alive != null && !alive) return true; // already reported as NEVER_MATCHES
      if (alive == null) return false;
      List<String> shadowing = new ArrayList<>();
      for (PolicyEngine.Candidate deny : denies) {
        Boolean possible =
            satisfiable(id, normal(allow.expression(), true), normal(deny.expression(), false));
        if (possible != null && !possible) shadowing.add(deny.rule().id());
      }
      if (!shadowing.isEmpty()) {
        add(
            Severity.WARNING,
            "ALLOW_SHADOWED_BY_DENY",
            id,
            "Whenever this rule is TRUE, DENY rule "
                + String.join(", ", shadowing)
                + " is TRUE or UNKNOWN, so it can never lead to an ALLOW decision.");
        return true;
      }
      if (denies.size() > 1) {
        Node[] all = new Node[denies.size() + 1];
        all[0] = normal(allow.expression(), true);
        for (int i = 0; i < denies.size(); i++)
          all[i + 1] = normal(denies.get(i).expression(), false);
        Boolean combined = satisfiable(id, all);
        if (combined != null && !combined) {
          add(
              Severity.WARNING,
              "ALLOW_SHADOWED_BY_DENY",
              id,
              "Together the DENY rules in this scope are TRUE or UNKNOWN whenever this rule is TRUE, so it can never lead to an ALLOW decision.");
          return true;
        }
      }
      return false;
    }

    void duplicates(List<PolicyEngine.Candidate> candidates) {
      Map<String, String> seen = new HashMap<>();
      for (PolicyEngine.Candidate c : candidates) {
        String signature = c.rule().effect() + "|" + PolicyLanguage.format(c.expression());
        String first = seen.putIfAbsent(signature, c.rule().id());
        if (first != null)
          add(
              Severity.WARNING,
              "DUPLICATE_RULE",
              c.rule().id(),
              "Rule " + first + " has the same effect and an identical condition.");
      }
    }

    void setDuplicates(PolicyEngine.Candidate c) {
      collectSets(c.expression(), c.rule().id());
    }

    void collectSets(Expr e, String ruleId) {
      switch (e) {
        case In n -> {
          Set<Object> seen = new HashSet<>();
          for (Literal literal : n.values())
            if (!seen.add(literal.value())) {
              add(
                  Severity.INFO,
                  "DUPLICATE_SET_ELEMENT",
                  ruleId,
                  "The set in `"
                      + PolicyLanguage.format(n)
                      + "` repeats "
                      + PolicyLanguage.format(literal)
                      + ".");
              break;
            }
        }
        case Binary b -> {
          collectSets(b.left(), ruleId);
          collectSets(b.right(), ruleId);
        }
        case Negate n -> collectSets(n.child(), ruleId);
        case Compare c -> {}
        case Literal l -> {}
        case Field f -> {}
        case Exists x -> {}
        case HasRole r -> {}
      }
    }

    /** A role matters only through its own or an inherited grant, or a hasRole() test. */
    void unusedRoles() {
      Set<String> granted = new HashSet<>(), tested = new HashSet<>();
      for (PolicyEngine.Grant g : policy.definition().grants()) granted.add(g.role());
      for (var candidates : policy.index().values())
        for (PolicyEngine.Candidate c : candidates) testedRoles(c.expression(), tested);
      for (var entry : new TreeMap<>(policy.closure()).entrySet()) {
        boolean useful = false;
        for (String role : entry.getValue())
          useful |= granted.contains(role) || tested.contains(role);
        if (!useful)
          add(
              Severity.INFO,
              "UNUSED_ROLE",
              null,
              "Role "
                  + entry.getKey()
                  + " neither holds a grant (directly or by inheritance) nor appears in a hasRole() test.");
      }
    }

    void testedRoles(Expr e, Set<String> out) {
      switch (e) {
        case HasRole r -> out.add(r.role());
        case Binary b -> {
          testedRoles(b.left(), out);
          testedRoles(b.right(), out);
        }
        case Negate n -> testedRoles(n.child(), out);
        case Compare c -> {
          testedRoles(c.left(), out);
          testedRoles(c.right(), out);
        }
        case In n -> testedRoles(n.value(), out);
        case Literal l -> {}
        case Field f -> {}
        case Exists x -> {}
      }
    }
  }
}
