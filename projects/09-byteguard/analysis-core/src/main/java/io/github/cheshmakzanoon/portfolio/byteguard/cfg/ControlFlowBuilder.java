package io.github.cheshmakzanoon.portfolio.byteguard.cfg;

import io.github.cheshmakzanoon.portfolio.byteguard.bytecode.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class ControlFlowBuilder {
  private record ExceptionRange(int start, int end, int handler, String type) {}

  /** Whether normal execution also continues at the immediately following instruction. */
  public static boolean fallsThrough(int opcode) {
    return !(opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN)
        && opcode != Opcodes.ATHROW
        && opcode != Opcodes.GOTO
        && opcode != Opcodes.JSR
        && opcode != Opcodes.RET
        && opcode != Opcodes.TABLESWITCH
        && opcode != Opcodes.LOOKUPSWITCH;
  }

  private int target(MethodIr method, LabelNode label) {
    try {
      return method.target(label);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("label outside method: " + method.id, invalid);
    }
  }

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
    List<ExceptionRange> ranges = new ArrayList<>();
    // Validate even ranges containing no throwing instruction; edge discovery must not
    // silently erase malformed exception-table metadata.
    for (TryCatchBlockNode block : m.node.tryCatchBlocks) {
      tick.run();
      int start = target(m, block.start),
          end = target(m, block.end),
          handler = target(m, block.handler);
      if (start < 0 || start >= end || end > count || handler < 0 || handler >= count)
        throw new IllegalArgumentException("invalid exception range or handler: " + m.id);
      ranges.add(new ExceptionRange(start, end, handler, block.type));
    }
    for (int i = 0; i < count; i++) {
      tick.run();
      TreeSet<Integer> next = new TreeSet<>();
      AbstractInsnNode n = m.instructions.get(i);
      int op = n.getOpcode();
      if (n instanceof JumpInsnNode j) {
        next.add(target(m, j.label));
        if (op != Opcodes.GOTO && op != Opcodes.JSR && i + 1 < count) next.add(i + 1);
      } else if (n instanceof TableSwitchInsnNode t) {
        next.add(target(m, t.dflt));
        for (LabelNode l : t.labels) next.add(target(m, l));
      } else if (n instanceof LookupSwitchInsnNode l) {
        next.add(target(m, l.dflt));
        for (LabelNode t : l.labels) next.add(target(m, t));
      } else if (fallsThrough(op) && i + 1 < count) next.add(i + 1);
      for (int t : next)
        if (t < 0 || t >= count)
          throw new IllegalArgumentException("invalid branch target " + m.id);
      normal.add(List.copyOf(next));
      TreeSet<Integer> handlers = new TreeSet<>();
      for (ExceptionRange range : ranges) {
        tick.run();
        if (i >= range.start() && i < range.end() && mayThrow(op)) {
          int h = range.handler();
          handlers.add(h);
          edges.add(new ExceptionEdge(i, h, range.type()));
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
