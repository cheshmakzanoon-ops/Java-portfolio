package io.github.cheshmakzanoon.portfolio.byteguard.cfg;

import io.github.cheshmakzanoon.portfolio.byteguard.bytecode.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class ControlFlowBuilder {
  public record Graph(
      List<List<Integer>> normal,
      List<List<Integer>> exceptional,
      List<ExceptionEdge> exceptionEdges,
      List<BasicBlock> blocks) {
    public String dot() {
      StringBuilder s = new StringBuilder("digraph cfg {\n");
      for (int i = 0; i < normal.size(); i++) {
        s.append("n").append(i).append(";\n");
        for (int t : normal.get(i)) s.append("n").append(i).append(" -> n").append(t).append(";\n");
        for (int t : exceptional.get(i))
          s.append("n").append(i).append(" -> n").append(t).append(" [style=dashed];\n");
      }
      return s.append("}\n").toString();
    }
  }

  public Graph build(MethodIr m) {
    return build(m, () -> {});
  }

  public Graph build(MethodIr m, Runnable tick) {
    int count = m.instructions.size();
    List<List<Integer>> normal = new ArrayList<>(), exceptional = new ArrayList<>();
    List<ExceptionEdge> edges = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      tick.run();
      TreeSet<Integer> next = new TreeSet<>();
      AbstractInsnNode n = m.instructions.get(i);
      int op = n.getOpcode();
      if (n instanceof JumpInsnNode j) {
        next.add(m.target(j.label));
        if (op != Opcodes.GOTO && op != Opcodes.JSR && i + 1 < count) next.add(i + 1);
      } else if (n instanceof TableSwitchInsnNode t) {
        next.add(m.target(t.dflt));
        for (LabelNode l : t.labels) next.add(m.target(l));
      } else if (n instanceof LookupSwitchInsnNode l) {
        next.add(m.target(l.dflt));
        for (LabelNode t : l.labels) next.add(m.target(t));
      } else if (!(op >= Opcodes.IRETURN && op <= Opcodes.RETURN)
          && op != Opcodes.ATHROW
          && op != Opcodes.RET
          && i + 1 < count) next.add(i + 1);
      for (int t : next)
        if (t < 0 || t >= count)
          throw new IllegalArgumentException("invalid branch target " + m.id);
      normal.add(List.copyOf(next));
      TreeSet<Integer> handlers = new TreeSet<>();
      for (TryCatchBlockNode b : m.node.tryCatchBlocks) {
        tick.run();
        if (i >= m.target(b.start) && i < m.target(b.end) && mayThrow(op)) {
          int h = m.target(b.handler);
          if (h >= count) throw new IllegalArgumentException("invalid handler");
          handlers.add(h);
          edges.add(new ExceptionEdge(i, h, b.type));
        }
      }
      exceptional.add(List.copyOf(handlers));
    }
    TreeSet<Integer> leaders = new TreeSet<>();
    if (count > 0) leaders.add(0);
    for (int i = 0; i < count; i++) {
      if (normal.get(i).size() != 1
          || (!normal.get(i).isEmpty() && normal.get(i).getFirst() != i + 1)) {
        leaders.addAll(normal.get(i));
        if (i + 1 < count) leaders.add(i + 1);
      }
      leaders.addAll(exceptional.get(i));
    }
    List<Integer> starts = new ArrayList<>(leaders);
    List<BasicBlock> blocks = new ArrayList<>();
    for (int i = 0; i < starts.size(); i++) {
      int end = i + 1 < starts.size() ? starts.get(i + 1) - 1 : count - 1;
      blocks.add(new BasicBlock(starts.get(i), end, normal.get(end)));
    }
    return new Graph(
        List.copyOf(normal), List.copyOf(exceptional), List.copyOf(edges), List.copyOf(blocks));
  }

  private boolean mayThrow(int op) {
    return op == Opcodes.LDC
        || op == Opcodes.ATHROW
        || op == Opcodes.IDIV
        || op == Opcodes.LDIV
        || op == Opcodes.IREM
        || op == Opcodes.LREM
        || (op >= Opcodes.IALOAD && op <= Opcodes.SALOAD)
        || (op >= Opcodes.IASTORE && op <= Opcodes.SASTORE)
        || (op >= Opcodes.GETSTATIC && op <= Opcodes.MONITOREXIT)
        || op == Opcodes.MULTIANEWARRAY;
  }
}
