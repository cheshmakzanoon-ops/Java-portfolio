package io.github.cheshmakzanoon.portfolio.byteguard.bytecode;

import java.util.*;
import org.objectweb.asm.tree.*;

public final class MethodIr {
  public final String owner, id, sourceFile, scope;
  public final int parameterSlots;
  public final MethodNode node;
  public final List<AbstractInsnNode> instructions;
  public final Map<AbstractInsnNode, Integer> ordinals;
  private final int[] lines;

  public MethodIr(String owner, String sourceFile, MethodNode node) {
    parameterSlots =
        MethodDescriptor.parameterSlots(
            node.desc, (node.access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0);
    this.owner = owner;
    this.sourceFile = sourceFile;
    this.node = node;
    id = owner + "." + node.name + node.desc;
    scope = MethodScope.of(id);
    List<AbstractInsnNode> ins = new ArrayList<>();
    IdentityHashMap<AbstractInsnNode, Integer> map = new IdentityHashMap<>();
    List<Integer> ls = new ArrayList<>();
    int line = -1;
    for (AbstractInsnNode n : node.instructions) {
      if (n instanceof LineNumberNode l) line = l.line;
      if (n.getOpcode() >= 0) {
        map.put(n, ins.size());
        ins.add(n);
        ls.add(line);
      }
    }
    instructions = List.copyOf(ins);
    ordinals = Map.copyOf(map);
    lines = ls.stream().mapToInt(Integer::intValue).toArray();
  }

  public int target(AbstractInsnNode label) {
    AbstractInsnNode n = label;
    while (n != null && n.getOpcode() < 0) n = n.getNext();
    if (n == null) return instructions.size();
    return ordinals.get(n);
  }

  public InstructionId at(int ordinal) {
    return new InstructionId(id, ordinal);
  }

  public int line(int ordinal) {
    return ordinal >= 0 && ordinal < lines.length ? lines[ordinal] : -1;
  }
}
