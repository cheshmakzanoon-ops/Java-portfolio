package io.github.cheshmakzanoon.portfolio.byteguard.bytecode;

/** Lossless source-label encoding: legal JVM names can contain punctuation used by display IDs. */
public record SourceIdentity(InstructionId instruction, String descriptor) {
  public String label() {
    String method = instruction.method();
    return "S:" + method.length() + ":" + method + ":" + instruction.ordinal() + ":" + descriptor;
  }

  public static SourceIdentity fromLabel(String label) {
    if (label == null || label.length() > 262_144 || !label.startsWith("S:"))
      throw new IllegalArgumentException("source label required");
    int lengthEnd = label.indexOf(':', 2);
    if (lengthEnd <= 2) throw new IllegalArgumentException("invalid source method length");
    String encodedLength = label.substring(2, lengthEnd);
    int methodLength = Integer.parseInt(encodedLength);
    if (methodLength < 1 || !Integer.toString(methodLength).equals(encodedLength))
      throw new IllegalArgumentException("noncanonical source method length");
    int methodStart = lengthEnd + 1;
    if (methodLength >= label.length() - methodStart)
      throw new IllegalArgumentException("source method length exceeds payload");
    int methodEnd = methodStart + methodLength;
    if (label.charAt(methodEnd) != ':')
      throw new IllegalArgumentException("invalid source method separator");
    int ordinalEnd = label.indexOf(':', methodEnd + 1);
    if (ordinalEnd <= methodEnd + 1 || ordinalEnd == label.length() - 1)
      throw new IllegalArgumentException("invalid source instruction separator");
    String ordinalText = label.substring(methodEnd + 1, ordinalEnd);
    int ordinal = Integer.parseInt(ordinalText);
    if (ordinal < 0 || !Integer.toString(ordinal).equals(ordinalText))
      throw new IllegalArgumentException("noncanonical source instruction identity");
    return new SourceIdentity(
        new InstructionId(label.substring(methodStart, methodEnd), ordinal),
        label.substring(ordinalEnd + 1));
  }
}
