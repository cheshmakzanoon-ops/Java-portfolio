package io.github.cheshmakzanoon.portfolio.faultline.interprocedural;

import io.github.cheshmakzanoon.portfolio.faultline.bytecode.InstructionId;

/** Exactly one caller instruction; no unbounded call strings are retained in lattice state. */
public record CallContext(String callee, InstructionId callSite)
    implements Comparable<CallContext> {
  public int compareTo(CallContext other) {
    int c = callee.compareTo(other.callee);
    return c == 0 ? callSite.compareTo(other.callSite) : c;
  }
}
