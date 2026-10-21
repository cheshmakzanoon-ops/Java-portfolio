package io.github.cheshmakzanoon.portfolio.stacks;

import com.sun.source.tree.*;
import com.sun.source.util.TreeScanner;
import java.util.*;

/**
 * Bounded syntax projection; declarations and lexical scopes remain the parser's responsibility.
 */
final class SourceSyntax {
  private SourceSyntax() {}

  static String type(Tree tree) {
    return tree == null ? "" : tree.toString();
  }

  static SourceFacts.Expression expression(Tree tree, int depth) {
    if (tree == null || depth >= 8) return SourceFacts.Expression.unknown();
    if (tree instanceof ParenthesizedTree p) return expression(p.getExpression(), depth);
    if (tree instanceof IdentifierTree id)
      return SourceFacts.Expression.name(id.getName().toString());
    if (tree instanceof MemberSelectTree select)
      return new SourceFacts.Expression(
          "select",
          select.getIdentifier().toString(),
          List.of(expression(select.getExpression(), depth + 1)));
    if (tree instanceof MethodInvocationTree call) {
      if (call.getArguments().size() > 64) return SourceFacts.Expression.unknown();
      List<SourceFacts.Expression> children = new ArrayList<>();
      String name;
      if (call.getMethodSelect() instanceof MemberSelectTree select) {
        name = select.getIdentifier().toString();
        children.add(expression(select.getExpression(), depth + 1));
      } else if (call.getMethodSelect() instanceof IdentifierTree id) {
        name = id.getName().toString();
        children.add(new SourceFacts.Expression("implicit", "this", List.of()));
      } else return SourceFacts.Expression.unknown();
      for (Tree arg : call.getArguments()) children.add(expression(arg, depth + 1));
      return new SourceFacts.Expression("call", name, children);
    }
    if (tree instanceof NewClassTree n) {
      if (n.getClassBody() != null || n.getArguments().size() > 64)
        return SourceFacts.Expression.unknown();
      List<SourceFacts.Expression> args = new ArrayList<>();
      for (Tree arg : n.getArguments()) args.add(expression(arg, depth + 1));
      return new SourceFacts.Expression("new", type(n.getIdentifier()), args);
    }
    if (tree instanceof LiteralTree literal) {
      String kind =
          literal.getValue() == null
              ? "null"
              : literal.getValue() instanceof String
                  ? "String"
                  : literal.getValue() instanceof Character
                      ? "char"
                      : literal.getValue() instanceof Boolean
                          ? "boolean"
                          : literal.getValue() instanceof Long
                              ? "long"
                              : literal.getValue() instanceof Integer
                                  ? "int"
                                  : literal.getValue() instanceof Float ? "float" : "double";
      if (literal.getValue() instanceof Number n && n.doubleValue() == 0) kind = "zero:" + kind;
      return new SourceFacts.Expression("literal", kind, List.of());
    }
    if (tree instanceof UnaryTree unary && tree.getKind() == Tree.Kind.LOGICAL_COMPLEMENT)
      return new SourceFacts.Expression(
          "not", "", List.of(expression(unary.getExpression(), depth + 1)));
    if (tree instanceof BinaryTree binary) {
      String kind = tree.getKind() == Tree.Kind.PLUS ? "sum" : "compare";
      if (!Set.of(
              Tree.Kind.PLUS,
              Tree.Kind.LESS_THAN,
              Tree.Kind.GREATER_THAN,
              Tree.Kind.LESS_THAN_EQUAL,
              Tree.Kind.GREATER_THAN_EQUAL,
              Tree.Kind.EQUAL_TO,
              Tree.Kind.NOT_EQUAL_TO)
          .contains(tree.getKind())) return SourceFacts.Expression.unknown();
      return new SourceFacts.Expression(
          kind,
          tree.getKind().name(),
          List.of(
              expression(binary.getLeftOperand(), depth + 1),
              expression(binary.getRightOperand(), depth + 1)));
    }
    return SourceFacts.Expression.unknown();
  }

  static List<Tree> children(Tree tree) {
    var out = new ArrayList<Tree>();
    tree.accept(new Children(), out);
    return out;
  }

  private static final class Children extends TreeScanner<Void, List<Tree>> {
    @Override
    public Void scan(Tree tree, List<Tree> out) {
      if (tree != null) out.add(tree);
      return null;
    }
  }
}
