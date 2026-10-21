package io.github.cheshmakzanoon.portfolio.faultline.bytecode;

public record InstructionId(String method, int ordinal) implements Comparable<InstructionId> {
  public String toString() {
    return method + "@" + ordinal;
  }

  public int compareTo(InstructionId other) {
    int c = method.compareTo(other.method);
    return c == 0 ? Integer.compare(ordinal, other.ordinal) : c;
  }
}
