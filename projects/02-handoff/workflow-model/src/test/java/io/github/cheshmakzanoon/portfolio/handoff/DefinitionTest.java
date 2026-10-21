package io.github.cheshmakzanoon.portfolio.handoff;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.handoff.Definition.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DefinitionTest {
  Node n(String id, List<String> parents) {
    return new Node(
        id,
        Kind.ACTIVITY,
        parents,
        "reserve",
        Safety.IDEMPOTENT_WITH_KEY,
        "release",
        Type.TEXT,
        Type.TEXT,
        0,
        null,
        null,
        null);
  }

  @Test
  void validHashIsStableAndVersionChangesHash() {
    Definition d = new Definition("order", 1, List.of(n("root", List.of())));
    assertSame(d, d.validate());
    assertEquals(64, d.contentHash().length());
    assertEquals(d.contentHash(), new Definition("order", 1, d.nodes()).contentHash());
    assertNotEquals(d.contentHash(), new Definition("order", 2, d.nodes()).contentHash());
  }

  @Test
  void rejectsMalformedGraphs() {
    List<Definition> invalid = new ArrayList<>();
    invalid.add(new Definition("!", 1, List.of(n("root", List.of()))));
    invalid.add(new Definition("x", 0, List.of(n("root", List.of()))));
    invalid.add(new Definition("x", 1, List.of()));
    invalid.add(new Definition("x", 1, List.of(n("root", List.of()), n("root", List.of()))));
    invalid.add(new Definition("x", 1, List.of(n("root", List.of()), n("a", List.of("missing")))));
    invalid.add(
        new Definition(
            "x", 1, List.of(n("root", List.of()), n("a", List.of("b")), n("b", List.of("a")))));
    invalid.add(new Definition("x", 1, List.of(n("root", List.of()), n("second", List.of()))));
    for (Definition d : invalid) assertThrows(IllegalArgumentException.class, d::validate);
  }

  @Test
  void rejectsExecutableAndUntypedInputs() {
    Node root = n("root", List.of());
    for (Node bad :
        List.of(
            new Node(
                "bad",
                Kind.ACTIVITY,
                List.of("root"),
                "java.lang.Runtime",
                Safety.PURE,
                null,
                Type.TEXT,
                Type.TEXT,
                0,
                null,
                null,
                null),
            new Node(
                "bad",
                Kind.TIMER,
                List.of("root"),
                null,
                null,
                null,
                Type.INTEGER,
                Type.TEXT,
                0,
                null,
                null,
                null),
            new Node(
                "bad",
                Kind.BRANCH,
                List.of("root"),
                null,
                null,
                null,
                Type.TEXT,
                Type.BOOLEAN,
                0,
                null,
                "T(java.lang.Runtime)",
                null),
            new Node(
                "bad",
                Kind.SIGNAL,
                List.of("root"),
                null,
                null,
                null,
                Type.TEXT,
                Type.TEXT,
                0,
                "!",
                null,
                null),
            new Node(
                "bad",
                Kind.TIMER,
                List.of("root"),
                null,
                null,
                null,
                Type.TEXT,
                Type.TEXT,
                -1,
                null,
                null,
                null),
            new Node(
                "bad",
                Kind.ACTIVITY,
                List.of("root"),
                "pure",
                Safety.PURE,
                "exec",
                Type.TEXT,
                Type.TEXT,
                0,
                null,
                null,
                null),
            new Node(
                "bad",
                Kind.ACTIVITY,
                List.of("root"),
                "pure",
                Safety.PURE,
                null,
                Type.TEXT,
                Type.TEXT,
                0,
                null,
                null,
                "root==true")))
      assertThrows(
          IllegalArgumentException.class,
          () -> new Definition("x", 1, List.of(root, bad)).validate());
  }

  @Test
  void checksEveryStructuralBoundAndValidGuard() {
    List<Node> huge = new ArrayList<>();
    for (int i = 0; i < 129; i++) huge.add(n("n" + i, i == 0 ? List.of() : List.of("n" + (i - 1))));
    assertThrows(IllegalArgumentException.class, () -> new Definition("huge", 1, huge).validate());
    List<Node> fanout = new ArrayList<>();
    fanout.add(n("root", List.of()));
    for (int i = 0; i < 17; i++) fanout.add(n("n" + i, List.of("root")));
    assertThrows(
        IllegalArgumentException.class, () -> new Definition("wide", 1, fanout).validate());
    assertThrows(
        IllegalArgumentException.class,
        () -> new Definition(null, 1, List.of(n("root", List.of()))).validate());
    List<Node> malformed =
        List.of(
            new Node(
                null,
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
                null),
            new Node(
                "!",
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
                null),
            new Node(
                "root",
                null,
                List.of(),
                "pure",
                Safety.PURE,
                null,
                Type.TEXT,
                Type.TEXT,
                0,
                null,
                null,
                null),
            new Node(
                "root",
                Kind.ACTIVITY,
                List.of(),
                "pure",
                null,
                null,
                Type.TEXT,
                Type.TEXT,
                0,
                null,
                null,
                null),
            new Node(
                "root",
                Kind.ACTIVITY,
                List.of(),
                "pure",
                Safety.PURE,
                null,
                null,
                Type.TEXT,
                0,
                null,
                null,
                null),
            new Node(
                "root",
                Kind.ACTIVITY,
                List.of(),
                "pure",
                Safety.PURE,
                null,
                Type.TEXT,
                null,
                0,
                null,
                null,
                null),
            new Node(
                "root",
                Kind.ACTIVITY,
                List.of("x", "x"),
                "pure",
                Safety.PURE,
                null,
                Type.TEXT,
                Type.TEXT,
                0,
                null,
                null,
                null),
            new Node(
                "root",
                Kind.ACTIVITY,
                java.util.stream.IntStream.range(0, 17).mapToObj(i -> "n" + i).toList(),
                "pure",
                Safety.PURE,
                null,
                Type.TEXT,
                Type.TEXT,
                0,
                null,
                null,
                null),
            new Node(
                "root",
                Kind.TIMER,
                List.of(),
                null,
                null,
                null,
                Type.TEXT,
                Type.TEXT,
                604801,
                null,
                null,
                null),
            new Node(
                "root",
                Kind.BRANCH,
                List.of(),
                null,
                null,
                null,
                Type.TEXT,
                Type.BOOLEAN,
                0,
                null,
                null,
                null),
            new Node(
                "root",
                Kind.BRANCH,
                List.of(),
                null,
                null,
                null,
                Type.TEXT,
                Type.TEXT,
                0,
                null,
                "input.ok==yes",
                null),
            new Node(
                "root",
                Kind.SIGNAL,
                List.of(),
                null,
                null,
                null,
                Type.TEXT,
                Type.TEXT,
                0,
                null,
                null,
                null),
            new Node(
                "root",
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
                "bad-expression"),
            new Node(
                "root",
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
                "missing==true"));
    for (Node bad : malformed)
      assertThrows(
          IllegalArgumentException.class, () -> new Definition("bad", 1, List.of(bad)).validate());
    Node branch =
        new Node(
            "root",
            Kind.BRANCH,
            List.of(),
            null,
            null,
            null,
            Type.TEXT,
            Type.BOOLEAN,
            0,
            null,
            "input.ok==yes",
            null);
    Node child =
        new Node(
            "child",
            Kind.ACTIVITY,
            List.of("root"),
            "reserve",
            Safety.IDEMPOTENT_WITH_KEY,
            null,
            Type.BOOLEAN,
            Type.TEXT,
            0,
            null,
            null,
            "root==true");
    assertNotNull(
        new Definition("valid", 1, List.of(branch, child), Map.of("ok", Type.TEXT)).validate());
  }

  @Test
  void compensationIsOnlyAnActivityAndIdsCannotAliasUndo() {
    Node invalidTimer =
        new Node(
            "wait",
            Kind.TIMER,
            List.of(),
            null,
            null,
            "refund",
            Type.TEXT,
            Type.TEXT,
            1,
            null,
            null,
            null);
    assertThrows(
        IllegalArgumentException.class,
        () -> new Definition("bad", 1, List.of(invalidTimer)).validate());
    assertThrows(
        IllegalArgumentException.class,
        () -> new Definition("bad", 1, List.of(n("comp-root", List.of()))).validate());
  }
}
