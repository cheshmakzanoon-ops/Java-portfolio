package io.github.cheshmakzanoon.portfolio.handoff;

import io.github.cheshmakzanoon.portfolio.handoff.Definition.Node;
import java.util.*;

/**
 * Renders a definition as a Mermaid flowchart or a Graphviz digraph, so a reviewer sees the same
 * structure the engine will execute. Edges are the {@code requires} relation; a guard is a dashed
 * edge from its branch; a compensation is a dotted edge to an undo node. Output is deterministic
 * and every label is escaped for its target syntax, although validated identifiers cannot contain
 * special characters.
 */
public final class DefinitionGraph {
  private DefinitionGraph() {}

  public static String mermaid(Definition definition) {
    Map<String, String> alias = new HashMap<>();
    int i = 0;
    for (Node node : definition.nodes()) alias.put(node.id(), "n" + i++);
    StringBuilder out = new StringBuilder("flowchart TD\n");
    for (Node node : definition.nodes()) {
      String id = alias.get(node.id());
      String label = quote(label(node, "<br/>"));
      out.append("  ")
          .append(
              switch (node.kind()) {
                case ACTIVITY -> id + "[" + label + "]";
                case TIMER -> id + "([" + label + "])";
                case SIGNAL -> id + "[/" + label + "/]";
                case BRANCH -> id + "{" + label + "}";
                case PARALLEL_ALL -> id + "{{" + label + "}}";
              })
          .append('\n');
      if (node.compensation() != null)
        out.append("  ")
            .append(id)
            .append("c([")
            .append(quote("undo: " + node.compensation()))
            .append("])\n");
    }
    for (Node node : definition.nodes()) {
      String id = alias.get(node.id());
      for (String parent : node.requires())
        out.append("  ").append(alias.get(parent)).append(" --> ").append(id).append('\n');
      if (node.when() != null) {
        String[] guard = node.when().split("==");
        out.append("  ")
            .append(alias.get(guard[0]))
            .append(" -. ")
            .append(quote("when " + guard[1]))
            .append(" .-> ")
            .append(id)
            .append('\n');
      }
      if (node.compensation() != null)
        out.append("  ").append(id).append(" -.-> ").append(id).append("c\n");
    }
    return out.toString();
  }

  public static String dot(Definition definition) {
    StringBuilder out =
        new StringBuilder("digraph ")
            .append(dotQuote(definition.name() + " v" + definition.version()))
            .append(" {\n  rankdir=TB;\n  node [fontname=\"Helvetica\"];\n");
    for (Node node : definition.nodes()) {
      out.append("  ")
          .append(dotQuote(node.id()))
          .append(" [shape=")
          .append(
              switch (node.kind()) {
                case ACTIVITY -> "box";
                case TIMER -> "ellipse";
                case SIGNAL -> "parallelogram";
                case BRANCH -> "diamond";
                case PARALLEL_ALL -> "hexagon";
              })
          .append(" label=")
          .append(dotQuote(label(node, "\n")))
          .append("];\n");
      if (node.compensation() != null)
        out.append("  ")
            .append(dotQuote("undo:" + node.id()))
            .append(" [shape=ellipse style=dashed label=")
            .append(dotQuote("undo: " + node.compensation()))
            .append("];\n");
    }
    for (Node node : definition.nodes()) {
      for (String parent : node.requires())
        out.append("  ")
            .append(dotQuote(parent))
            .append(" -> ")
            .append(dotQuote(node.id()))
            .append(";\n");
      if (node.when() != null) {
        String[] guard = node.when().split("==");
        out.append("  ")
            .append(dotQuote(guard[0]))
            .append(" -> ")
            .append(dotQuote(node.id()))
            .append(" [style=dashed label=")
            .append(dotQuote("when " + guard[1]))
            .append("];\n");
      }
      if (node.compensation() != null)
        out.append("  ")
            .append(dotQuote(node.id()))
            .append(" -> ")
            .append(dotQuote("undo:" + node.id()))
            .append(" [style=dotted];\n");
    }
    return out.append("}\n").toString();
  }

  private static String label(Node node, String lineBreak) {
    String detail =
        switch (node.kind()) {
          case ACTIVITY -> node.activity() + " · " + node.safety();
          case TIMER -> "timer " + node.delaySeconds() + " s";
          case SIGNAL -> "signal " + node.signal() + " · timeout " + node.delaySeconds() + " s";
          case BRANCH -> node.expression();
          case PARALLEL_ALL -> "join";
        };
    return node.id() + lineBreak + detail;
  }

  private static String quote(String text) {
    return "\"" + text.replace("\"", "#quot;") + "\"";
  }

  private static String dotQuote(String text) {
    return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
  }
}
