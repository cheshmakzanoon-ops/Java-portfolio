package io.github.cheshmakzanoon.portfolio.byteguard.bytecode;

/** Lossless source-label encoding: legal JVM names can contain punctuation used by display IDs. */
public record SourceIdentity(InstructionId instruction, String descriptor) {
  public String label() {
    String method = instruction.method();
    return "S:" + method.length() + ":" + method + ":" + instruction.ordinal() + ":" + descriptor;
  }

  public static SourceIdentity fromLabel(String label) {
    if (!label.startsWith("S:")) throw new IllegalArgumentException("source label required");
    int lengthEnd = label.indexOf(':', 2);
    int methodLength = Integer.parseInt(label.substring(2, lengthEnd));
    int methodStart = lengthEnd + 1;
    int methodEnd = Math.addExact(methodStart, methodLength);
    if (methodLength < 1 || methodEnd >= label.length() || label.charAt(methodEnd) != ':')
      throw new IllegalArgumentException("invalid source method identity");
    int ordinalEnd = label.indexOf(':', methodEnd + 1);
    int ordinal = Integer.parseInt(label.substring(methodEnd + 1, ordinalEnd));
    if (ordinal < 0 || ordinalEnd == label.length() - 1)
      throw new IllegalArgumentException("invalid source instruction identity");
    return new SourceIdentity(
        new InstructionId(label.substring(methodStart, methodEnd), ordinal),
        label.substring(ordinalEnd + 1));
  }
}
