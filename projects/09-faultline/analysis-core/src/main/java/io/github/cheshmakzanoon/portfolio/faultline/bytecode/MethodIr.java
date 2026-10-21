package io.github.cheshmakzanoon.portfolio.faultline.bytecode;

import java.util.*;
import org.objectweb.asm.tree.*;

public final class MethodIr {
  public final String owner, id, sourceFile, scope;
  public final int parameterSlots;
  public final MethodNode node;
  public final List<AbstractInsnNode> instructions;
  public final Map<AbstractInsnNode, Integer> ordinals;
  private final int[] lines;
  private final Map<AbstractInsnNode, Integer> targets;

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
    IdentityHashMap<AbstractInsnNode, Integer> targetMap = new IdentityHashMap<>();
    int next = ins.size();
    for (AbstractInsnNode n = node.instructions.getLast(); n != null; n = n.getPrevious()) {
      if (n.getOpcode() >= 0) next = map.get(n);
      targetMap.put(n, next);
    }
    targets = Collections.unmodifiableMap(targetMap);
    lines = ls.stream().mapToInt(Integer::intValue).toArray();
  }

  public int target(AbstractInsnNode label) {
    Integer target = targets.get(label);
    if (target == null) throw new IllegalArgumentException("label is outside this method");
    return target;
  }

  public InstructionId at(int ordinal) {
    return new InstructionId(id, ordinal);
  }

  public int line(int ordinal) {
    return ordinal >= 0 && ordinal < lines.length ? lines[ordinal] : -1;
  }
}
