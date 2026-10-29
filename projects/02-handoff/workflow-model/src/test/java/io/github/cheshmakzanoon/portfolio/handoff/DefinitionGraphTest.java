package io.github.cheshmakzanoon.portfolio.handoff;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.handoff.Definition.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DefinitionGraphTest {
  private static Definition guarded() {
    Node reserve =
        new Node(
            "reserve",
            Kind.ACTIVITY,
            List.of(),
            "reserve",
            Safety.IDEMPOTENT_WITH_KEY,
            "release",
            Type.TEXT,
            Type.TEXT,
            0,
            null,
            null,
            null);
    Node approval =
        new Node(
            "approval",
            Kind.SIGNAL,
            List.of("reserve"),
            null,
            null,
            null,
            Type.TEXT,
            Type.TEXT,
            3600,
            "approval",
            null,
            null);
    Node decide =
        new Node(
            "timed-out",
            Kind.BRANCH,
            List.of("approval"),
            null,
            null,
            null,
            Type.TEXT,
            Type.BOOLEAN,
            0,
            null,
            "status.approval==TIMED_OUT",
            null);
    Node cleanup =
        new Node(
            "cleanup",
            Kind.ACTIVITY,
            List.of("timed-out"),
            "release",
            Safety.IDEMPOTENT_WITH_KEY,
            null,
            Type.BOOLEAN,
            Type.TEXT,
            0,
            null,
            null,
            "timed-out==true");
    return new Definition("order-fulfilment", 3, List.of(reserve, approval, decide, cleanup));
  }

  @Test
  void mermaidShowsKindsGuardsAndCompensations() {
    assertEquals(
        """
        flowchart TD
          n0["reserve<br/>reserve · IDEMPOTENT_WITH_KEY"]
          n0c(["undo: release"])
          n1[/"approval<br/>signal approval · timeout 3600 s"/]
          n2{"timed-out<br/>status.approval==TIMED_OUT"}
          n3["cleanup<br/>release · IDEMPOTENT_WITH_KEY"]
          n0 -.-> n0c
          n0 --> n1
          n1 --> n2
          n2 --> n3
          n2 -. "when true" .-> n3
        """,
        DefinitionGraph.mermaid(guarded()));
  }

  @Test
  void dotUsesDistinctShapesAndStylesForGuardsAndUndo() {
    assertEquals(
        """
        digraph "order-fulfilment v3" {
          rankdir=TB;
          node [fontname="Helvetica"];
          "reserve" [shape=box label="reserve\\nreserve · IDEMPOTENT_WITH_KEY"];
          "undo:reserve" [shape=ellipse style=dashed label="undo: release"];
          "approval" [shape=parallelogram label="approval\\nsignal approval · timeout 3600 s"];
          "timed-out" [shape=diamond label="timed-out\\nstatus.approval==TIMED_OUT"];
          "cleanup" [shape=box label="cleanup\\nrelease · IDEMPOTENT_WITH_KEY"];
          "reserve" -> "undo:reserve" [style=dotted];
          "reserve" -> "approval";
          "approval" -> "timed-out";
          "timed-out" -> "cleanup";
          "timed-out" -> "cleanup" [style=dashed label="when true"];
        }
        """,
        DefinitionGraph.dot(guarded()));
  }

  @Test
  void timersAndJoinsHaveTheirOwnShapes() {
    Node a =
        new Node(
            "a",
            Kind.ACTIVITY,
            List.of(),
            "pure",
            Safety.PURE,
            null,
            Type.TEXT,
            Type.TEXT,
            0,
            null,
            null,
            null);
    Node t =
        new Node(
            "t",
            Kind.TIMER,
            List.of("a"),
            null,
            null,
            null,
            Type.TEXT,
            Type.TEXT,
            30,
            null,
            null,
            null);
    Node u =
        new Node(
            "u",
            Kind.TIMER,
            List.of("a"),
            null,
            null,
            null,
            Type.TEXT,
            Type.TEXT,
            60,
            null,
            null,
            null);
    Node j =
        new Node(
            "j",
            Kind.PARALLEL_ALL,
            List.of("t", "u"),
            null,
            null,
            null,
            Type.TEXT,
            Type.TEXT,
            0,
            null,
            null,
            null);
    Definition definition = new Definition("fan", 1, List.of(a, t, u, j));
    String mermaid = DefinitionGraph.mermaid(definition);
    assertTrue(mermaid.contains("n1([\"t<br/>timer 30 s\"])"), mermaid);
    assertTrue(mermaid.contains("n3{{\"j<br/>join\"}}"), mermaid);
    String dot = DefinitionGraph.dot(definition);
    assertTrue(dot.contains("\"t\" [shape=ellipse label=\"t\\ntimer 30 s\"];"), dot);
    assertTrue(dot.contains("\"j\" [shape=hexagon label=\"j\\njoin\"];"), dot);
    assertTrue(dot.contains("\"t\" -> \"j\";") && dot.contains("\"u\" -> \"j\";"));
  }

  @Test
  void hostileIdentifiersCannotBreakOutOfEitherSyntax() {
    Node bad =
        new Node(
            "a\"b\\c\nd",
            Kind.ACTIVITY,
            List.of(),
            "pure",
            Safety.PURE,
            null,
            Type.TEXT,
            Type.TEXT,
            0,
            null,
            null,
            null);
    Definition definition = new Definition("n\"ame", 1, List.of(bad));
    String dot = DefinitionGraph.dot(definition);
    assertTrue(dot.contains("digraph \"n\\\"ame v1\""), dot);
    assertTrue(dot.contains("\"a\\\"b\\\\c\\nd\""), dot);
    String mermaid = DefinitionGraph.mermaid(definition);
    assertFalse(
        mermaid.substring(mermaid.indexOf('\n')).replace("#quot;", "").contains("\"a\"b"), mermaid);
    assertTrue(mermaid.contains("a#quot;b"), mermaid);
  }

  @Test
  void rendersAreDeterministic() {
    assertEquals(DefinitionGraph.mermaid(guarded()), DefinitionGraph.mermaid(guarded()));
    assertEquals(DefinitionGraph.dot(guarded()), DefinitionGraph.dot(guarded()));
  }
}
