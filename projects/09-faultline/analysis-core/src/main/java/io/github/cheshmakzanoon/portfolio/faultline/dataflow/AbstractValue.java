package io.github.cheshmakzanoon.portfolio.faultline.dataflow;

import java.util.*;
import org.objectweb.asm.Type;

public record AbstractValue(Kind kind, Taint taint, SortedSet<String> references, String constant) {
  public enum Kind {
    TOP,
    INT,
    FLOAT,
    LONG,
    DOUBLE,
    REF
  }

  public static final AbstractValue TOP = of(Kind.TOP, Taint.BOTTOM);

  public AbstractValue {
    if (references.size() > 4096)
      throw new io.github.cheshmakzanoon.portfolio.faultline.interprocedural.AnalysisBudget
          .Exhausted("reference-set budget exceeded (4096 per value)");
    references = Collections.unmodifiableSortedSet(new TreeSet<>(references));
  }

  public static AbstractValue of(Kind k, Taint t) {
    return new AbstractValue(k, t, new TreeSet<>(), null);
  }

  public static AbstractValue reference(Taint t, String id) {
    return new AbstractValue(Kind.REF, t, new TreeSet<>(Set.of(id)), null);
  }

  public static AbstractValue constant(Object value) {
    return new AbstractValue(
        value instanceof Long
            ? Kind.LONG
            : value instanceof Double
                ? Kind.DOUBLE
                : value instanceof Float
                    ? Kind.FLOAT
                    : value instanceof Integer ? Kind.INT : Kind.REF,
        Taint.BOTTOM,
        new TreeSet<>(),
        String.valueOf(value));
  }

  public int size() {
    return kind == Kind.LONG || kind == Kind.DOUBLE ? 2 : 1;
  }

  public AbstractValue withTaint(Taint t) {
    return new AbstractValue(kind, t, references, constant);
  }

  public AbstractValue join(AbstractValue other) {
    if (equals(other)) return this;
    if (kind == Kind.TOP || other.kind == Kind.TOP) return TOP;
    if (kind != other.kind)
      throw new IllegalArgumentException(
          "incompatible verifier merge: " + kind + " / " + other.kind);
    TreeSet<String> refs = new TreeSet<>(references);
    refs.addAll(other.references);
    return new AbstractValue(
        kind,
        taint.join(other.taint),
        refs,
        Objects.equals(constant, other.constant) ? constant : null);
  }

  public static Kind kind(Type type) {
    return switch (type.getSort()) {
      case Type.VOID -> Kind.TOP;
      case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Kind.INT;
      case Type.FLOAT -> Kind.FLOAT;
      case Type.LONG -> Kind.LONG;
      case Type.DOUBLE -> Kind.DOUBLE;
      default -> Kind.REF;
    };
  }
}
