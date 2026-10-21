package io.github.cheshmakzanoon.portfolio.stacks;

import static io.github.cheshmakzanoon.portfolio.stacks.SourceSymbolGraph.*;

import java.util.*;

/**
 * Repository-local nominal binding, overload and receiver inference under one charged budget.
 * Unknown inheritance or incomplete syntax abstains; this is not compiler attribution.
 */
final class SourceBindingResolver {
  record TypeRef(Document document, SourceFacts.Type type) {}

  record MemberRef(Document document, SourceFacts.Member member) {
    String key() {
      return document.key() + "/" + member.signature();
    }
  }

  record CallRef(MemberRef caller, SourceFacts.Invocation call) {}

  record Bound(String name, TypeRef source) {
    static Bound unknown() {
      return new Bound("", null);
    }

    boolean known() {
      return !name.isEmpty();
    }
  }

  record Resolution(MemberRef target, boolean unresolved) {}

  private final Platform platform;
  private final Map<String, List<TypeRef>> types;
  private final Map<String, List<MemberRef>> methods;

  SourceBindingResolver(
      Platform platform, Map<String, List<TypeRef>> types, Map<String, List<MemberRef>> methods) {
    this.platform = Objects.requireNonNull(platform);
    this.types = immutableGroups(types);
    this.methods = immutableGroups(methods);
  }

  private static <T> Map<String, List<T>> immutableGroups(Map<String, List<T>> input) {
    Map<String, List<T>> result = new HashMap<>();
    input.forEach((key, group) -> result.put(key, List.copyOf(group)));
    return Map.copyOf(result);
  }

  boolean potential(CallRef call, MemberRef target, Work work) {
    var e = call.call().expression();
    boolean reference = isReference(e);
    if (!reference && e.children().size() - 1 != target.member().parameters().size()) return false;
    if (implicitContextUnknown(call)) return true;
    Bound receiver = typeOf(e.children().getFirst(), call.caller(), work, 0, new HashSet<>());
    if (receiver.source() != null
        && receiver.source().type().closed()
        && !receiver.source().type().unknownSupertype()
        && !receiver.name().equals(target.member().owner())) return false;
    TypeRef targetOwner = unique(target.member().owner());
    if (receiver.known()
        && receiver.source() == null
        && targetOwner != null
        && targetOwner.type().closed()
        && !targetOwner.type().unknownSupertype()) {
      if (receiver.name().startsWith("nominal-local:")
          || receiver.name().startsWith("nominal-import:")) {
        String spelling = receiver.name().substring(receiver.name().lastIndexOf(':') + 1);
        if (!simple(spelling).equals(simple(targetOwner.type().name()))) return false;
      }
      if (receiver.name().startsWith("java.")
          && !Set.of("java.lang.Object", "java.lang.Record", "java.lang.Enum")
              .contains(receiver.name())) return false;
    }
    return true;
  }

  Resolution resolve(CallRef call, Work work, int depth, Set<String> seen) {
    work.step();
    if (depth > 8) return new Resolution(null, true);
    var expression = call.call().expression();
    if (isReference(expression)) return new Resolution(null, true);
    if (implicitContextUnknown(call)) return new Resolution(null, true);
    Bound receiver = typeOf(expression.children().getFirst(), call.caller(), work, depth + 1, seen);
    if (receiver.source() == null) return new Resolution(null, true);
    var owner = receiver.source();
    if (owner.type().unknownSupertype()) return new Resolution(null, true);
    List<MemberRef> candidates =
        methods.getOrDefault(owner.type().name() + "#" + expression.text(), List.of());
    List<MemberRef> compatible = new ArrayList<>();
    boolean uncertain = false;
    for (var candidate : candidates) {
      work.step(); // Duplicate owners/overload groups cannot multiply uncharged inference work.
      var member = candidate.member();
      if (member.parameters().size() != expression.children().size() - 1) continue;
      if (member.privateMember()
          && !candidate.document().key().equals(call.caller().document().key())) continue;
      if (!member.privateMember() && !member.staticMember() && !owner.type().closed()) {
        uncertain = true;
        continue;
      }
      boolean matches = true;
      for (int i = 0; i < member.parameters().size(); i++) {
        Bound expected = type(member.parameters().get(i).type(), candidate);
        Bound actual =
            typeOf(
                expression.children().get(i + 1),
                call.caller(),
                work,
                depth + 1,
                new HashSet<>(seen));
        if (!expected.known() || !actual.known() || actual.name().equals("null")) {
          uncertain = true;
          matches = false;
          break;
        }
        if (!compatible(actual.name(), expected.name())) {
          boolean bothPrimitive = primitive(actual.name()) && primitive(expected.name());
          boolean disjoint =
              actual.source() != null
                  && actual.source().type().closed()
                  && !actual.source().type().unknownSupertype()
                  && !expected.name().equals("java.lang.Object");
          if (!bothPrimitive && !disjoint) uncertain = true;
          matches = false;
          break;
        }
      }
      if (matches) compatible.add(candidate);
    }
    return compatible.size() == 1 && !uncertain
        ? new Resolution(compatible.getFirst(), false)
        : new Resolution(null, uncertain || compatible.size() > 1);
  }

  Bound typeOf(
      SourceFacts.Expression expression,
      MemberRef context,
      Work work,
      int depth,
      Set<String> seen) {
    work.step();
    if (depth > 8) return Bound.unknown();
    switch (expression.kind()) {
      case "implicit":
        return new Bound(context.member().owner(), unique(context.member().owner()));
      case "literal":
        return new Bound(
            expression.text().startsWith("zero:")
                ? expression.text().substring(5)
                : expression.text().equals("String") ? "java.lang.String" : expression.text(),
            null);
      case "new":
        return type(expression.text(), context);
      case "name":
        {
          if (expression.text().equals("this"))
            return new Bound(context.member().owner(), unique(context.member().owner()));
          if (!seen.add(expression.text())) return Bound.unknown();
          SourceFacts.Binding found = null;
          for (var binding : context.member().parameters())
            if (binding.name().equals(expression.text())) found = binding;
          for (var binding : context.member().locals())
            if (binding.name().equals(expression.text())) {
              if (found != null) return Bound.unknown();
              found = binding;
            }
          var owner = unique(context.member().owner());
          if (found == null && owner != null)
            for (var field : owner.type().fields())
              if (field.name().equals(expression.text())) found = field;
          if (found != null) {
            if (found.ambiguous() || context.member().bindingUnknown()) return Bound.unknown();
            if (found.type().equals("var"))
              return typeOf(found.initializer(), context, work, depth + 1, seen);
            return type(found.type(), context);
          }
          // An expression identifier is not assumed to be an external receiver merely because
          // a type spelling would be syntactically possible. Unknown value names stay unknown.
          Bound named = type(expression.text(), context);
          return named.source() != null || platform.types().contains(named.name())
              ? named
              : Bound.unknown();
        }
      case "select":
        {
          String qualified = qualified(expression);
          if (qualified != null && !shadowedRoot(qualified, context)) {
            Bound exact = type(qualified, context);
            if (exact.source() != null || platform.types().contains(exact.name())) return exact;
          }
          Bound owner = typeOf(expression.children().getFirst(), context, work, depth + 1, seen);
          if (owner.source() != null) {
            if (expression.text().equals("this")) return owner;
            for (var field : owner.source().type().fields())
              if (field.name().equals(expression.text()) && !field.ambiguous())
                return type(
                    field.type(), new MemberRef(owner.source().document(), context.member()));
          } else if (owner.known()) {
            String result = platform.fieldType(owner.name(), expression.text());
            if (result != null) return canonical(result, context);
          }
          return Bound.unknown();
        }
      case "call":
        {
          if (expression.children().isEmpty()) return Bound.unknown();
          Bound receiver =
              typeOf(
                  expression.children().getFirst(), context, work, depth + 1, new HashSet<>(seen));
          if (receiver.source() != null && expression.children().size() == 1) {
            String component = receiver.source().type().recordComponents().get(expression.text());
            if (component != null)
              return type(component, new MemberRef(receiver.source().document(), context.member()));
          }
          var result =
              resolve(
                  new CallRef(context, new SourceFacts.Invocation(expression, 0, 0)),
                  work,
                  depth + 1,
                  new HashSet<>(seen));
          if (result.target() != null)
            return type(result.target().member().returnType(), result.target());
          if (receiver.known() && receiver.source() == null) {
            var returns =
                platform.returns(
                    receiver.name(), expression.text(), expression.children().size() - 1);
            if (returns.size() == 1) return canonical(returns.iterator().next(), context);
          }
          return Bound.unknown();
        }
      default:
        return Bound.unknown();
    }
  }

  private Bound type(String raw, MemberRef context) {
    if (raw == null) return Bound.unknown();
    String name = erase(raw);
    if (context.member().bindingUnknown()) return Bound.unknown();
    if (name.isEmpty()
        || name.equals("var")
        || name.contains(" ")
        || name.contains("?")
        || name.contains("@")) return Bound.unknown();
    if (Set.of("byte", "short", "int", "long", "float", "double", "boolean", "char", "void")
        .contains(name)) return new Bound(name, null);
    if (name.endsWith("[]")) {
      Bound element = type(name.substring(0, name.length() - 2), context);
      return element.known() ? new Bound(element.name() + "[]", null) : Bound.unknown();
    }
    if (types.containsKey(name)) return canonical(name, context);
    Set<String> found = new HashSet<>();
    for (var t : context.document().facts().types())
      if (simple(t.name()).equals(name)) found.add(t.name());
    for (String imported : context.document().facts().imports()) {
      String value = imported.startsWith("static ") ? imported.substring(7) : imported;
      if (!value.endsWith(".*") && simple(value).equals(name)) found.add(value);
    }
    if (found.size() == 1) {
      String candidate = found.iterator().next();
      Bound known = canonical(candidate, context);
      return known.known() ? known : new Bound("nominal-import:" + candidate, null);
    }
    if (found.size() > 1) return Bound.unknown();
    String pkg = context.document().facts().packageName();
    String local = pkg.isEmpty() ? name : pkg + "." + name;
    if (types.containsKey(local)) return canonical(local, context);
    if (platform.types().contains("java.lang." + name)
        || Set.of(
                "String",
                "Object",
                "Integer",
                "Long",
                "Short",
                "Byte",
                "Float",
                "Double",
                "Boolean",
                "Character")
            .contains(name)) return new Bound("java.lang." + name, null);
    boolean unknownWildcard = false;
    for (String imported : context.document().facts().imports()) {
      String value = imported.startsWith("static ") ? imported.substring(7) : imported;
      if (!value.endsWith(".*")) continue;
      String prefix = value.substring(0, value.length() - 1);
      String candidate = prefix + name;
      if (types.containsKey(candidate) || platform.types().contains(candidate))
        found.add(candidate);
      else if (!prefix.startsWith("java.")) unknownWildcard = true;
    }
    if (found.size() == 1 && !unknownWildcard) return canonical(found.iterator().next(), context);
    if (name.startsWith("java.") && platform.types().contains(name)) return new Bound(name, null);
    // This is only equality of the SAME declared nominal spelling in the SAME source scope.
    // It is never a resolved external API, never crosses files, and never resolves a call.
    // Generic type parameters/unknown supertypes were separately marked unsupported.
    TypeRef declaring = unique(context.member().owner());
    if (declaring != null && !declaring.type().unknownSupertype())
      return new Bound(
          "nominal-local:" + context.document().key() + ":" + context.member().owner() + ":" + name,
          null);
    return Bound.unknown();
  }

  private Bound canonical(String name, MemberRef context) {
    TypeRef source = unique(name);
    if (source != null) return new Bound(name, source);
    if (types.containsKey(name)) return Bound.unknown();
    if (platform.types().contains(name) || name.startsWith("java.") && name.endsWith("[]"))
      return new Bound(name, null);
    if (Set.of("byte", "short", "int", "long", "float", "double", "boolean", "char", "void")
        .contains(name)) return new Bound(name, null);
    return Bound.unknown();
  }

  TypeRef unique(String name) {
    var values = types.get(name);
    return values != null && values.size() == 1 ? values.getFirst() : null;
  }

  private boolean implicitContextUnknown(CallRef call) {
    if (!call.call().expression().children().getFirst().kind().equals("implicit")) return false;
    String owner = call.caller().member().owner();
    for (String imported : call.caller().document().facts().imports())
      if (imported.startsWith("static ")) return true;
    // A declared same-owner method group establishes the innermost lookup scope. Ordinary
    // overload/type checks still decide uniqueness; this does not guess an enclosing target.
    if (methods.containsKey(owner + "#" + call.call().expression().text())) return false;
    for (var type : call.caller().document().facts().types())
      if (!owner.equals(type.name()) && owner.startsWith(type.name() + ".")) return true;
    return false;
  }

  private static boolean shadowedRoot(String qualified, MemberRef context) {
    String root =
        qualified.contains(".") ? qualified.substring(0, qualified.indexOf('.')) : qualified;
    for (var b : context.member().parameters()) if (b.name().equals(root)) return true;
    for (var b : context.member().locals()) if (b.name().equals(root)) return true;
    for (var t : context.document().facts().types()) {
      if (simple(t.name()).equals(root)) return true;
      if (t.name().equals(context.member().owner()))
        for (var f : t.fields()) if (f.name().equals(root)) return true;
    }
    return false;
  }

  static String simple(String name) {
    return name.substring(name.lastIndexOf('.') + 1);
  }

  private static String erase(String type) {
    int generic = type.indexOf('<');
    return (generic < 0 ? type : type.substring(0, generic)).strip();
  }

  static boolean isReference(SourceFacts.Expression e) {
    return e.children().size() == 2
        && e.children().get(1).kind().equals("unknown")
        && e.children().get(1).text().equals("reference");
  }

  private static String qualified(SourceFacts.Expression e) {
    if (e.kind().equals("name")) return e.text();
    if (e.kind().equals("select") && e.children().size() == 1) {
      String head = qualified(e.children().getFirst());
      return head == null ? null : head + "." + e.text();
    }
    return null;
  }

  private static boolean primitive(String type) {
    return Set.of("byte", "short", "int", "long", "float", "double", "boolean", "char", "void")
        .contains(type);
  }

  private static boolean compatible(String actual, String expected) {
    if (actual.equals(expected)) return true;
    if (expected.equals("java.lang.Object") && !primitive(actual) && !actual.equals("null"))
      return true;
    return switch (actual) {
      case "byte" -> Set.of("short", "int", "long", "float", "double").contains(expected);
      case "short", "char" -> Set.of("int", "long", "float", "double").contains(expected);
      case "int" -> Set.of("long", "float", "double").contains(expected);
      case "long" -> Set.of("float", "double").contains(expected);
      case "float" -> expected.equals("double");
      default -> false;
    };
  }
}
