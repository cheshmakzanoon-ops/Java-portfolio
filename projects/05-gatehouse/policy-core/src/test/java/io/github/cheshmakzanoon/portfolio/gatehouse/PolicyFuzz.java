package io.github.cheshmakzanoon.portfolio.gatehouse;

import static io.github.cheshmakzanoon.portfolio.gatehouse.PolicyLanguage.*;

import java.util.*;

/**
 * Random typed policies plus an exhaustive evaluation grid. The grid holds, for each attribute the
 * policy mentions, the absent state and every constant in the policy together with its immediate
 * neighbours and the type's extremes. Truth is constant between consecutive constants, so for the
 * modelled language the grid is a complete decision procedure that shares nothing with the
 * analyzer's solver; it evaluates through the production interpreter.
 */
final class PolicyFuzz {
  static final String SCOPE_ACTION = "read", SCOPE_TYPE = "doc";
  private static final List<Long> INTS = List.of(0L, 1L, 2L);
  private static final List<String> STRINGS = List.of("a", "b");
  private static final List<String> ROLES = List.of("r0", "r1", "r2");

  /** A generated policy and the attributes its conditions mention. */
  record Generated(PolicyEngine.Definition definition, List<String> fields, boolean relations) {}

  private final Random random;
  private final List<String> fields;
  private final boolean relations;
  private final List<String> strings;

  PolicyFuzz(long seed, boolean relations, List<String> strings) {
    this.random = new Random(seed);
    this.relations = relations;
    this.strings = strings;
    List<String> all = new ArrayList<>(FIELDS.keySet());
    Collections.sort(all);
    Collections.shuffle(all, random);
    // Relation atoms compare matching attribute pairs, so that mode uses exactly those four; the
    // other mode picks three arbitrary attributes. Both keep the exhaustive grid small.
    List<String> chosen =
        relations
            ? List.of(
                "subject.department",
                "resource.department",
                "subject.clearance",
                "resource.classification")
            : new ArrayList<>(all.subList(0, 3));
    this.fields = chosen.stream().distinct().sorted().toList();
  }

  static PolicyFuzz of(long seed, boolean relations) {
    return new PolicyFuzz(seed, relations, STRINGS);
  }

  /** Strings that stress escaping rather than analysis; used for formatting round trips. */
  static PolicyFuzz awkward(long seed) {
    return new PolicyFuzz(seed, false, List.of("a\"b", "c\\d", "", "\\", "\"", "😀", "x y", "é"));
  }

  Generated policy() {
    Map<String, Set<String>> parents = new TreeMap<>();
    parents.put("r0", Set.of());
    parents.put("r1", random.nextBoolean() ? Set.of("r0") : Set.of());
    Set<String> top = new TreeSet<>();
    if (random.nextBoolean()) top.add("r0");
    if (random.nextBoolean()) top.add("r1");
    parents.put("r2", top);
    List<PolicyEngine.Grant> grants = new ArrayList<>();
    for (String role : ROLES)
      if (random.nextInt(3) > 0) grants.add(new PolicyEngine.Grant(role, SCOPE_ACTION, SCOPE_TYPE));
    List<PolicyEngine.Rule> rules = new ArrayList<>();
    int count = 1 + random.nextInt(4);
    for (int i = 0; i < count; i++)
      rules.add(
          new PolicyEngine.Rule(
              "rule" + i,
              SCOPE_ACTION,
              SCOPE_TYPE,
              random.nextInt(10) < 6 ? PolicyEngine.Effect.ALLOW : PolicyEngine.Effect.DENY,
              condition(3)));
    return new Generated(new PolicyEngine.Definition(parents, grants, rules), fields, relations);
  }

  String condition(int depth) {
    int pick = random.nextInt(depth <= 0 ? 8 : 11);
    return switch (pick) {
      case 0, 1, 2, 3 -> comparison();
      case 4 -> "exists(" + field() + ")";
      case 5 -> "hasRole(\"" + ROLES.get(random.nextInt(ROLES.size())) + "\")";
      case 6 ->
          relations && random.nextBoolean() ? relation() : random.nextBoolean() ? "true" : "false";
      case 7 -> special();
      case 8 -> "not " + wrapped(condition(depth - 1));
      case 9 -> "(" + condition(depth - 1) + " and " + condition(depth - 1) + ")";
      default -> "(" + condition(depth - 1) + " or " + condition(depth - 1) + ")";
    };
  }

  /**
   * Shapes the other generators rarely reach: comparisons and set tests between constants, and
   * comparisons or set tests on total boolean predicates such as exists() and hasRole().
   */
  private String special() {
    String role = "hasRole(\"" + ROLES.get(random.nextInt(ROLES.size())) + "\")";
    String presence = "exists(" + field() + ")";
    String total = random.nextBoolean() ? role : presence;
    String other = random.nextBoolean() ? role : presence;
    return switch (random.nextInt(8)) {
      case 0 -> {
        Type type = random.nextBoolean() ? Type.INTEGER : Type.STRING;
        String[] operators = {"==", "!=", "<", "<=", ">", ">="};
        yield literal(type)
            + " "
            + operators[random.nextInt(operators.length)]
            + " "
            + literal(type);
      }
      case 1 ->
          literal(Type.ENUM)
              + " "
              + (random.nextBoolean() ? "==" : "!=")
              + " "
              + literal(Type.ENUM);
      case 2 -> {
        Type type = Type.values()[random.nextInt(Type.values().length)];
        yield literal(type) + " in [" + literal(type) + ", " + literal(type) + "]";
      }
      case 3 ->
          total
              + " "
              + (random.nextBoolean() ? "==" : "!=")
              + " "
              + (random.nextBoolean() ? "true" : "false");
      case 4 -> total + " " + (random.nextBoolean() ? "==" : "!=") + " " + other;
      case 5 -> total + " in [" + (random.nextBoolean() ? "true" : "false") + "]";
      case 6 -> total + " in [true, false]";
      default ->
          (random.nextBoolean() ? "true" : "false")
              + " "
              + (random.nextBoolean() ? "==" : "!=")
              + " "
              + total;
    };
  }

  private static String wrapped(String inner) {
    return inner.startsWith("(") || inner.contains(" ") ? "(" + inner + ")" : inner;
  }

  private String field() {
    return fields.get(random.nextInt(fields.size()));
  }

  private String literal(Type type) {
    return switch (type) {
      case INTEGER -> Long.toString(INTS.get(random.nextInt(INTS.size())));
      case STRING -> quote(strings.get(random.nextInt(strings.size())));
      case BOOLEAN -> random.nextBoolean() ? "true" : "false";
      case ENUM -> Label.values()[random.nextInt(3)].name();
    };
  }

  static String quote(String text) {
    return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
  }

  private String comparison() {
    String name = field();
    Type type = FIELDS.get(name);
    boolean ordered = type == Type.INTEGER || type == Type.STRING;
    String[] operators =
        ordered ? new String[] {"==", "!=", "<", "<=", ">", ">="} : new String[] {"==", "!="};
    int shape = random.nextInt(6);
    if (type == Type.BOOLEAN && shape == 0) return name;
    if (shape == 1) {
      List<String> values = new ArrayList<>();
      for (int i = 0, n = 1 + random.nextInt(3); i < n; i++) values.add(literal(type));
      return name + " in [" + String.join(", ", values) + "]";
    }
    String op = operators[random.nextInt(operators.length)];
    return shape == 2
        ? literal(type) + " " + op + " " + name
        : name + " " + op + " " + literal(type);
  }

  private String relation() {
    String[][] pairs = {
      {"subject.department", "resource.department"},
      {"subject.clearance", "resource.classification"}
    };
    String[] pair = pairs[random.nextInt(pairs.length)];
    String[] operators = {"==", "!=", "<", "<=", ">", ">="};
    String op = operators[random.nextInt(operators.length)];
    if (FIELDS.get(pair[0]) == Type.STRING && random.nextBoolean())
      op = random.nextBoolean() ? "==" : "!=";
    String other = random.nextInt(6) == 0 ? pair[0] : pair[1];
    String text = pair[0] + " " + op + " " + other;
    // A guard that covers "either attribute is absent" is the shape most likely to expose an
    // over-eager claim of certainty: only a sound analysis keeps the relation itself in play.
    return random.nextInt(3) == 0
        ? "(" + text + " or not exists(" + pair[0] + ") or not exists(" + other + "))"
        : text;
  }

  // ---------------------------------------------------------------------------------------------
  // Exhaustive grid
  // ---------------------------------------------------------------------------------------------

  /** Every combination of (absent or representative value) for the given attributes. */
  static List<Map<String, Object>> grid(List<String> fields) {
    List<Map<String, Object>> points = new ArrayList<>();
    points.add(new HashMap<>());
    for (String name : fields) {
      List<Object> values = new ArrayList<>();
      values.add(null);
      switch (FIELDS.get(name)) {
        case INTEGER -> {
          values.addAll(List.of(Long.MIN_VALUE, -1L, 0L, 1L, 2L, 3L, Long.MAX_VALUE));
        }
        case STRING -> {
          values.add("");
          for (String s : List.of("a", "b")) values.addAll(List.of(s, s + "\u0000"));
        }
        case BOOLEAN -> values.addAll(List.of(true, false));
        case ENUM -> values.addAll(Arrays.asList(Label.values()));
      }
      List<Map<String, Object>> next = new ArrayList<>();
      for (Map<String, Object> point : points)
        for (Object value : values) {
          Map<String, Object> copy = new HashMap<>(point);
          if (value != null) copy.put(name, value);
          next.add(copy);
        }
      points = next;
    }
    return points;
  }

  /** Every subset of the generated roles, as assigned (not yet closed) role sets. */
  static List<Set<String>> roleSets() {
    List<Set<String>> sets = new ArrayList<>();
    for (int mask = 0; mask < (1 << ROLES.size()); mask++) {
      Set<String> set = new TreeSet<>();
      for (int bit = 0; bit < ROLES.size(); bit++)
        if ((mask & (1 << bit)) != 0) set.add(ROLES.get(bit));
      sets.add(set);
    }
    return sets;
  }
}
