package io.github.cheshmakzanoon.portfolio.faultline.dataflow;

import io.github.cheshmakzanoon.portfolio.faultline.bytecode.*;
import io.github.cheshmakzanoon.portfolio.faultline.cfg.*;
import io.github.cheshmakzanoon.portfolio.faultline.interprocedural.*;
import java.util.*;
import org.objectweb.asm.*;

public final class WorklistSolver {
  public record Result(MethodSummary summary, List<FrameState> frames, int reachable) {}

  public Result solve(
      MethodIr method,
      ControlFlowBuilder.Graph graph,
      TransferFunction transfer,
      AnalysisBudget budget,
      boolean reverse) {
    budget.spend();
    if (method.parameterSlots > method.node.maxLocals && !method.instructions.isEmpty())
      throw new IllegalArgumentException("method parameters exceed maxLocals");
    int n = method.instructions.size();
    boolean bodyless = (method.node.access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) != 0;
    if (bodyless) {
      if (n != 0) throw new IllegalArgumentException("native/abstract method has executable code");
      return new Result(MethodSummary.empty(), List.of(), 0);
    }
    if (n == 0) throw new IllegalArgumentException("concrete method has no executable code");
    FrameState[] states = new FrameState[n];
    budget.reserveFrame(method.node.maxLocals);
    FrameState initial = new FrameState(method.node.maxLocals);
    int slot = 0, param = 0;
    if ((method.node.access & Opcodes.ACC_STATIC) == 0) {
      budget.spend();
      initial.store(
          slot++,
          AbstractValue.reference(
              Taint.parameterScope(method.scope, param++), "this:" + method.scope));
    }
    for (Type type : Type.getArgumentTypes(method.node.desc)) {
      budget.spend();
      AbstractValue v =
          AbstractValue.of(AbstractValue.kind(type), Taint.parameterScope(method.scope, param++));
      if (v.kind() == AbstractValue.Kind.REF)
        v = AbstractValue.reference(v.taint(), "parameter:" + method.scope + ":" + slot);
      initial.store(slot, v);
      slot += type.getSize();
    }
    states[0] = initial;
    Deque<Integer> pending = new ArrayDeque<>();
    BitSet queued = new BitSet();
    pending.add(0);
    queued.set(0);
    while (!pending.isEmpty()) {
      int i = reverse ? pending.removeLast() : pending.removeFirst();
      queued.clear(i);
      budget.spend();
      FrameState before = states[i];
      FrameState after = before.copy();
      transfer.apply(i, after);
      if (i == n - 1 && ControlFlowBuilder.fallsThrough(method.instructions.get(i).getOpcode()))
        throw new IllegalArgumentException("reachable fall-through past method end: " + method.id);
      for (int to : graph.normal().get(i)) propagate(to, after, states, pending, queued, budget);
      for (int to : graph.exceptional().get(i)) {
        FrameState handler = before.copy();
        handler.stack.clear();
        handler.push(
            AbstractValue.of(
                AbstractValue.Kind.REF, before.stackTaint().join(Taint.unknownValue())));
        propagate(to, handler, states, pending, queued, budget);
      }
    }
    int reached = 0;
    for (FrameState state : states) if (state != null) reached++;
    return new Result(
        transfer.summary(), Collections.unmodifiableList(Arrays.asList(states)), reached);
  }

  private void propagate(
      int target,
      FrameState frame,
      FrameState[] states,
      Deque<Integer> pending,
      BitSet queued,
      AnalysisBudget budget) {
    boolean changed;
    if (states[target] == null) {
      budget.reserveFrame(frame.locals.length + frame.stack.size());
      states[target] = frame.copy();
      changed = true;
    } else changed = states[target].merge(frame);
    if (changed && !queued.get(target)) {
      pending.addLast(target);
      queued.set(target);
    }
  }
}
