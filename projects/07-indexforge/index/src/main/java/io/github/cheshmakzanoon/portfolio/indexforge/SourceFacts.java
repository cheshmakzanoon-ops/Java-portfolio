package io.github.cheshmakzanoon.portfolio.indexforge;

import java.util.*;

/** Bounded parse-only observations. No record claims runtime behavior or compiler attribution. */
public final class SourceFacts {
  public static final String SCHEMA = "source-facts-v3";
  public static final int SOURCE_BYTES = 65_536;
  public static final int SUMMARY_BYTES = 16_384;
  public static final int RAW_SUMMARY_BYTES = 262_144;
  public static final int MEMBERS = 128;
  public static final int OPERATIONS = 128;

  private SourceFacts() {}

  public enum Status {
    OBSERVED,
    UNSUPPORTED,
    SOURCE_QUOTA,
    SYNTAX,
    TREE_QUOTA
  }

  public record Expression(String kind, String text, List<Expression> children) {
    public Expression {
      if (kind == null
          || !Set.of("name", "select", "call", "new", "literal", "sum", "compare", "not", "unknown")
              .contains(kind)
          || text == null
          || text.length() > 256
          || children == null
          || children.size() > 65) throw new IllegalArgumentException("source expression quota");
      children = List.copyOf(children);
    }

    public static Expression unknown() {
      return new Expression("unknown", "", List.of());
    }

    public static Expression name(String name) {
      return new Expression("name", name, List.of());
    }
  }

  public record Binding(
      String name,
      String type,
      Expression initializer,
      boolean ambiguous,
      int position,
      int block) {
    public Binding {
      name = text(name);
      type = text(type);
      Objects.requireNonNull(initializer);
      if (position < -1 || block < -1 || (position == -1) != (block == -1))
        throw new IllegalArgumentException("binding position");
    }

    public Binding(String name, String type, Expression initializer, boolean ambiguous) {
      this(name, type, initializer, ambiguous, -1, -1);
    }
  }

  public record Invocation(Expression expression, int position, int block) {
    public Invocation {
      Objects.requireNonNull(expression);
      if (!expression.kind().equals("call") || position < 0 || block < 0)
        throw new IllegalArgumentException("invocation identity");
    }
  }

  /** A terminal branch observed before a later operation in the same lexical block. */
  public record Guard(Expression condition, int end, int block, String terminal) {
    public Guard {
      Objects.requireNonNull(condition);
      if (end < 0 || block < 0 || !Set.of("return", "throw").contains(terminal))
        throw new IllegalArgumentException("guard identity");
    }
  }

  /** A returned expression in its actual executable scope, never a nested lambda's return. */
  public record Returned(Expression expression, int position, int block, boolean directFinal) {
    public Returned {
      Objects.requireNonNull(expression);
      if (position < 0 || block < 0) throw new IllegalArgumentException("return position");
    }

    public Returned(Expression expression, int position, int block) {
      this(expression, position, block, false);
    }
  }

  public record Member(
      String owner,
      String name,
      String returnType,
      List<Binding> parameters,
      List<Binding> locals,
      Set<String> identifiers,
      List<Invocation> calls,
      List<Guard> guards,
      List<Returned> returned,
      int position,
      boolean privateMember,
      boolean staticMember,
      boolean bindingUnknown,
      boolean controlUnknown,
      boolean callsComplete) {
    public Member {
      owner = text(owner);
      name = text(name);
      returnType = text(returnType);
      parameters = bounded(parameters, 64);
      locals = bounded(locals, 64);
      identifiers = Collections.unmodifiableSet(new TreeSet<>(identifiers));
      if (identifiers.size() > 128 || position < 0)
        throw new IllegalArgumentException("member quota");
      identifiers.forEach(SourceFacts::text);
      calls = bounded(calls, OPERATIONS);
      guards = bounded(guards, OPERATIONS);
      returned = bounded(returned, OPERATIONS);
    }

    public Member(String owner, String name, String returnType, List<Binding> parameters,
        List<Binding> locals, Set<String> identifiers, List<Invocation> calls, List<Guard> guards,
        List<Returned> returned, int position, boolean privateMember, boolean staticMember,
        boolean bindingUnknown, boolean controlUnknown) {
      this(owner, name, returnType, parameters, locals, identifiers, calls, guards, returned,
          position, privateMember, staticMember, bindingUnknown, controlUnknown, true);
    }

    public Member(
        String owner,
        String name,
        String returnType,
        List<Binding> parameters,
        List<Binding> locals,
        Set<String> identifiers,
        List<Invocation> calls,
        List<Guard> guards,
        int position,
        boolean privateMember,
        boolean staticMember,
        boolean bindingUnknown) {
      this(
          owner,
          name,
          returnType,
          parameters,
          locals,
          identifiers,
          calls,
          guards,
          List.of(),
          position,
          privateMember,
          staticMember,
          bindingUnknown,
          false);
    }

    public String signature() {
      return owner
          + "#"
          + name
          + "("
          + String.join(",", parameters.stream().map(Binding::type).toList())
          + ")@"
          + position;
    }
  }

  public record Type(
      String name,
      boolean closed,
      boolean unknownSupertype,
      List<Binding> fields,
      Map<String, String> recordComponents) {
    public Type {
      name = text(name);
      fields = bounded(fields, 64);
      if (recordComponents.size() > 64) throw new IllegalArgumentException("record quota");
      recordComponents = Collections.unmodifiableMap(new TreeMap<>(recordComponents));
      recordComponents.forEach(
          (k, v) -> {
            text(k);
            text(v);
          });
    }
  }

  public record Unit(
      Status status,
      String packageName,
      List<String> imports,
      List<Type> types,
      List<Member> members) {
    public Unit {
      Objects.requireNonNull(status);
      packageName = text(packageName);
      imports = bounded(imports, 64);
      imports.forEach(SourceFacts::text);
      types = bounded(types, MEMBERS);
      members = bounded(members, MEMBERS);
      if (status != Status.OBSERVED && (!types.isEmpty() || !members.isEmpty()))
        throw new IllegalArgumentException("partial source facts forbidden");
    }

    public static Unit unknown(Status reason) {
      if (reason == Status.OBSERVED) throw new IllegalArgumentException("unknown reason");
      return new Unit(reason, "", List.of(), List.of(), List.of());
    }

    public boolean declarationInventoryKnown() {
      return status == Status.OBSERVED;
    }
  }

  static String text(String value) {
    if (value == null || value.length() > 256)
      throw new IllegalArgumentException("source text quota");
    return value;
  }

  private static <T> List<T> bounded(List<T> values, int max) {
    if (values == null || values.size() > max)
      throw new IllegalArgumentException("source collection quota");
    return List.copyOf(values);
  }
}
