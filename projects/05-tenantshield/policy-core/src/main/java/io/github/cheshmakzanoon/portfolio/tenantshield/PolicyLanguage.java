package io.github.cheshmakzanoon.portfolio.tenantshield;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Restricted, statically typed language. Compilation and evaluation have independent work budgets.
 */
public final class PolicyLanguage {
  public static final int MAX_BYTES = 16_384,
      MAX_NODES = 512,
      MAX_DEPTH = 32,
      MAX_STRING = 256,
      MAX_SET = 64;

  public enum Type {
    BOOLEAN,
    STRING,
    INTEGER,
    ENUM
  }

  public enum Label {
    PUBLIC,
    INTERNAL,
    CONFIDENTIAL
  }

  public enum Truth {
    TRUE,
    FALSE,
    UNKNOWN;

    public Truth not() {
      return this == UNKNOWN ? UNKNOWN : this == TRUE ? FALSE : TRUE;
    }

    public Truth and(Truth b) {
      return this == FALSE || b == FALSE ? FALSE : this == UNKNOWN || b == UNKNOWN ? UNKNOWN : TRUE;
    }

    public Truth or(Truth b) {
      return this == TRUE || b == TRUE ? TRUE : this == UNKNOWN || b == UNKNOWN ? UNKNOWN : FALSE;
    }
  }

  public static final Map<String, Type> FIELDS =
      Map.ofEntries(
          Map.entry("subject.department", Type.STRING),
          Map.entry("subject.clearance", Type.INTEGER),
          Map.entry("subject.suspended", Type.BOOLEAN),
          Map.entry("resource.department", Type.STRING),
          Map.entry("resource.classification", Type.INTEGER),
          Map.entry("resource.type", Type.STRING),
          Map.entry("resource.id", Type.STRING),
          Map.entry("resource.sensitivity", Type.ENUM),
          Map.entry("context.purpose", Type.STRING),
          Map.entry("context.hour", Type.INTEGER));

  public sealed interface Expr
      permits Literal, Field, Binary, Negate, Compare, Exists, HasRole, In {
    Type type();
  }

  public record Literal(Object value, Type type) implements Expr {}

  public record Field(String name, Type type) implements Expr {}

  public record Binary(String op, Expr left, Expr right) implements Expr {
    public Type type() {
      return Type.BOOLEAN;
    }
  }

  public record Negate(Expr child) implements Expr {
    public Type type() {
      return Type.BOOLEAN;
    }
  }

  public record Compare(String op, Expr left, Expr right) implements Expr {
    public Type type() {
      return Type.BOOLEAN;
    }
  }

  public record Exists(Field field) implements Expr {
    public Type type() {
      return Type.BOOLEAN;
    }
  }

  public record HasRole(String role) implements Expr {
    public Type type() {
      return Type.BOOLEAN;
    }
  }

  public record In(Expr value, List<Literal> values) implements Expr {
    public In {
      values = List.copyOf(values);
    }

    public Type type() {
      return Type.BOOLEAN;
    }
  }

  public record Inputs(Map<String, Object> fields, Set<String> roles) {
    public Inputs {
      fields = Map.copyOf(fields);
      roles = Set.copyOf(roles);
      fields.forEach(
          (k, v) -> {
            Type t = FIELDS.get(k);
            if (t == null
                || !(t == Type.STRING && v instanceof String s && s.length() <= MAX_STRING
                    || t == Type.INTEGER && v instanceof Long
                    || t == Type.BOOLEAN && v instanceof Boolean
                    || t == Type.ENUM && v instanceof Label))
              throw new IllegalArgumentException("invalid input field: " + k);
          });
    }
  }

  public static final class InvalidPolicy extends IllegalArgumentException {
    public InvalidPolicy(String message) {
      super(message);
    }
  }

  private PolicyLanguage() {}

  public static Expr compile(String text) {
    if (text == null || text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
      throw new InvalidPolicy("policy byte limit");
    Parser p = new Parser(text);
    Expr e = p.expression(0);
    if (!p.peek("<end>")) throw p.error("trailing token");
    if (e.type() != Type.BOOLEAN) throw p.error("condition must be boolean");
    return e;
  }

  public static Truth evaluate(Expr expression, Inputs inputs) {
    Object value = eval(expression, inputs, new int[] {MAX_NODES * 2});
    return value == null ? Truth.UNKNOWN : (Boolean) value ? Truth.TRUE : Truth.FALSE;
  }

  private static Object eval(Expr e, Inputs i, int[] budget) {
    if (--budget[0] < 0) throw new InvalidPolicy("evaluation budget exhausted");
    return switch (e) {
      case Literal l -> l.value();
      case Field f -> i.fields().get(f.name());
      case Exists x -> i.fields().containsKey(x.field().name());
      case HasRole r -> i.roles().contains(r.role());
      case Negate n -> bool(truth(eval(n.child(), i, budget)).not());
      case Binary b -> {
        Truth a = truth(eval(b.left(), i, budget));
        Truth z = truth(eval(b.right(), i, budget));
        yield bool(b.op().equals("and") ? a.and(z) : a.or(z));
      }
      case Compare c -> {
        Object a = eval(c.left(), i, budget), b = eval(c.right(), i, budget);
        if (a == null || b == null) yield null;
        int order =
            a instanceof Long n
                ? Long.compare(n, (Long) b)
                : a instanceof String s
                    ? s.compareTo((String) b)
                    : a instanceof Label l
                        ? l.compareTo((Label) b)
                        : Boolean.compare((Boolean) a, (Boolean) b);
        yield switch (c.op()) {
          case "==" -> order == 0;
          case "!=" -> order != 0;
          case ">" -> order > 0;
          case ">=" -> order >= 0;
          case "<" -> order < 0;
          case "<=" -> order <= 0;
          default -> throw new InvalidPolicy("bad operator");
        };
      }
      case In n -> {
        Object v = eval(n.value(), i, budget);
        yield v == null ? null : n.values().stream().anyMatch(x -> x.value().equals(v));
      }
    };
  }

  private static Truth truth(Object x) {
    return x == null ? Truth.UNKNOWN : (Boolean) x ? Truth.TRUE : Truth.FALSE;
  }

  private static Boolean bool(Truth t) {
    return t == Truth.UNKNOWN ? null : t == Truth.TRUE;
  }

  private record Token(String text, boolean string) {}

  private static final class Parser {
    private final List<Token> tokens = new ArrayList<>();
    private int pos, nodes;

    Parser(String text) {
      for (int n = 0; n < text.length(); ) {
        char c = text.charAt(n);
        if (Character.isWhitespace(c)) {
          n++;
          continue;
        }
        if (c == '"') {
          StringBuilder b = new StringBuilder();
          n++;
          boolean closed = false;
          while (n < text.length()) {
            c = text.charAt(n++);
            if (c == '"') {
              closed = true;
              break;
            }
            if (c == '\\') {
              if (n >= text.length()) throw error("unterminated escape");
              c = text.charAt(n++);
              if (c != '"' && c != '\\') throw error("unsupported escape");
            }
            if (c < 32) throw error("control character");
            b.append(c);
            if (b.length() > MAX_STRING) throw error("literal length");
          }
          if (!closed) throw error("unterminated string");
          tokens.add(new Token(b.toString(), true));
        } else if (Character.isLetter(c) || c == '_' || Character.isDigit(c) || c == '-') {
          int start = n++;
          while (n < text.length()
              && (Character.isLetterOrDigit(text.charAt(n))
                  || text.charAt(n) == '.'
                  || text.charAt(n) == '_'
                  || text.charAt(n) == '-')) n++;
          tokens.add(new Token(text.substring(start, n), false));
        } else {
          String op = String.valueOf(c);
          n++;
          if (n < text.length() && text.charAt(n) == '=' && "=!<>".indexOf(c) >= 0)
            op += text.charAt(n++);
          if (!Set.of("(", ")", "[", "]", ",", "==", "!=", ">", "<", ">=", "<=").contains(op))
            throw error("illegal token");
          tokens.add(new Token(op, false));
        }
        if (tokens.size() > MAX_NODES * 6) throw error("token limit");
      }
      tokens.add(new Token("<end>", false));
    }

    InvalidPolicy error(String x) {
      return new InvalidPolicy(x + " at token " + pos);
    }

    boolean peek(String t) {
      return !tokens.get(pos).string() && tokens.get(pos).text().equals(t);
    }

    boolean take(String t) {
      if (peek(t)) {
        pos++;
        return true;
      }
      return false;
    }

    void expect(String t) {
      if (!take(t)) throw error("expected " + t);
    }

    Expr node(Expr e) {
      if (++nodes > MAX_NODES) throw error("AST node limit");
      return e;
    }

    Expr expression(int depth) {
      checkDepth(depth);
      Expr e = and(depth + 1);
      while (take("or")) {
        Expr b = and(depth + 1);
        requireBool(e);
        requireBool(b);
        e = node(new Binary("or", e, b));
      }
      return e;
    }

    Expr and(int d) {
      Expr e = unary(d);
      while (take("and")) {
        Expr b = unary(d);
        requireBool(e);
        requireBool(b);
        e = node(new Binary("and", e, b));
      }
      return e;
    }

    Expr unary(int d) {
      checkDepth(d);
      if (take("not")) {
        Expr e = unary(d + 1);
        requireBool(e);
        return node(new Negate(e));
      }
      if (take("(")) {
        Expr e = expression(d + 1);
        expect(")");
        return e;
      }
      return comparison(d);
    }

    void checkDepth(int d) {
      if (d > MAX_DEPTH) throw error("nesting limit");
    }

    void requireBool(Expr e) {
      if (e.type() != Type.BOOLEAN) throw error("expected boolean");
    }

    Expr comparison(int d) {
      Expr a = atom();
      if (take("in")) {
        expect("[");
        List<Literal> list = new ArrayList<>();
        do {
          Expr b = atom();
          if (!(b instanceof Literal l) || b.type() != a.type()) throw error("set element type");
          list.add(l);
          if (list.size() > MAX_SET) throw error("set limit");
        } while (take(","));
        expect("]");
        return node(new In(a, list));
      }
      if (Set.of("==", "!=", ">", "<", ">=", "<=").contains(tokens.get(pos).text())
          && !tokens.get(pos).string()) {
        String op = tokens.get(pos++).text();
        Expr b = atom();
        if (a.type() != b.type()) throw error("comparison type mismatch");
        if ((a.type() == Type.BOOLEAN || a.type() == Type.ENUM) && !Set.of("==", "!=").contains(op))
          throw error("boolean ordering forbidden");
        return node(new Compare(op, a, b));
      }
      return a;
    }

    Expr atom() {
      if (pos >= tokens.size() - 1) throw error("unexpected end of input");
      Token t = tokens.get(pos++);
      if (t.string()) return node(new Literal(t.text(), Type.STRING));
      String s = t.text();
      if (s.equals("exists")) {
        expect("(");
        Expr f = atom();
        if (!(f instanceof Field field)) throw error("exists requires field");
        expect(")");
        return node(new Exists(field));
      }
      if (s.equals("hasRole")) {
        expect("(");
        Token role = tokens.get(pos++);
        if (!role.string() || role.text().isBlank()) throw error("role name required");
        expect(")");
        return node(new HasRole(role.text()));
      }
      if (Set.of("PUBLIC", "INTERNAL", "CONFIDENTIAL").contains(s))
        return node(new Literal(Label.valueOf(s), Type.ENUM));
      if (FIELDS.containsKey(s)) return node(new Field(s, FIELDS.get(s)));
      if (s.equals("true") || s.equals("false"))
        return node(new Literal(Boolean.valueOf(s), Type.BOOLEAN));
      try {
        return node(new Literal(Long.parseLong(s), Type.INTEGER));
      } catch (NumberFormatException ignored) {
        throw error("unknown field or literal");
      }
    }
  }
}
