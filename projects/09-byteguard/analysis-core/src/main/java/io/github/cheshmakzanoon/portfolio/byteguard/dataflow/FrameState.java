package io.github.cheshmakzanoon.portfolio.byteguard.dataflow;

import java.util.*;

public final class FrameState {
  public final AbstractValue[] locals;
  public final ArrayList<AbstractValue> stack;

  public FrameState(int n) {
    locals = new AbstractValue[n];
    Arrays.fill(locals, AbstractValue.TOP);
    stack = new ArrayList<>();
  }

  private FrameState(AbstractValue[] l, ArrayList<AbstractValue> s) {
    locals = l;
    stack = s;
  }

  public FrameState copy() {
    return new FrameState(locals.clone(), new ArrayList<>(stack));
  }

  public void push(AbstractValue v) {
    if (v.kind() == AbstractValue.Kind.TOP) throw new IllegalArgumentException("TOP on stack");
    stack.add(v);
  }

  public AbstractValue pop() {
    if (stack.isEmpty()) throw new IllegalArgumentException("stack underflow");
    return stack.removeLast();
  }

  public void store(int slot, AbstractValue value) {
    if (slot < 0 || slot + value.size() > locals.length)
      throw new IllegalArgumentException("local out of bounds");
    if (slot > 0 && locals[slot - 1].size() == 2) locals[slot - 1] = AbstractValue.TOP;
    locals[slot] = value;
    if (value.size() == 2) locals[slot + 1] = AbstractValue.TOP;
  }

  public boolean merge(FrameState other) {
    if (stack.size() != other.stack.size() || locals.length != other.locals.length)
      throw new IllegalArgumentException("incompatible verifier frame shape");
    boolean changed = false;
    for (int i = 0; i < locals.length; i++) {
      // A local that holds different computational types along two predecessors is
      // unusable at their join. Unlike an operand-stack mismatch, this is legal when
      // subsequent code does not load that slot (ordinary disjoint lexical scopes).
      AbstractValue v =
          locals[i].kind() == other.locals[i].kind()
              ? locals[i].join(other.locals[i])
              : AbstractValue.TOP;
      if (!v.equals(locals[i])) {
        locals[i] = v;
        changed = true;
      }
    }
    for (int i = 0; i < stack.size(); i++) {
      AbstractValue v = stack.get(i).join(other.stack.get(i));
      if (!v.equals(stack.get(i))) {
        stack.set(i, v);
        changed = true;
      }
    }
    return changed;
  }

  public Taint stackTaint() {
    Taint t = Taint.BOTTOM;
    for (AbstractValue v : stack) t = t.join(v.taint());
    return t;
  }

  public int slots() {
    return stack.stream().mapToInt(AbstractValue::size).sum();
  }
}
