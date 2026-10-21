package io.github.cheshmakzanoon.portfolio.faultline.bytecode;

/** Linear, allocation-free descriptor validation before ASM argument arrays or entry symbols. */
public final class MethodDescriptor {
  private MethodDescriptor() {}

  public static int parameterSlots(String descriptor, boolean isStatic) {
    if (descriptor == null || descriptor.length() < 3 || descriptor.charAt(0) != '(')
      throw invalid();
    int cursor = 1, slots = isStatic ? 0 : 1;
    while (cursor < descriptor.length() && descriptor.charAt(cursor) != ')') {
      char start = descriptor.charAt(cursor);
      cursor = typeEnd(descriptor, cursor, false);
      slots += start == 'J' || start == 'D' ? 2 : 1;
      if (slots > 255)
        throw new IllegalArgumentException("method parameters exceed JVM 255-slot limit");
    }
    if (cursor >= descriptor.length() || descriptor.charAt(cursor++) != ')') throw invalid();
    if (typeEnd(descriptor, cursor, true) != descriptor.length()) throw invalid();
    return slots;
  }

  public static void field(String descriptor) {
    if (descriptor == null || typeEnd(descriptor, 0, false) != descriptor.length()) throw invalid();
  }

  private static int typeEnd(String descriptor, int cursor, boolean allowVoid) {
    if (cursor >= descriptor.length()) throw invalid();
    char type = descriptor.charAt(cursor++);
    if (type == '[') {
      int dimensions = 1;
      while (cursor < descriptor.length() && descriptor.charAt(cursor) == '[') {
        cursor++;
        if (++dimensions > 255) throw invalid();
      }
      return typeEnd(descriptor, cursor, false);
    }
    if (type == 'L') {
      int end = descriptor.indexOf(';', cursor);
      if (end <= cursor) throw invalid();
      int componentStart = cursor;
      for (int i = cursor; i < end; i++) {
        char c = descriptor.charAt(i);
        if (c == '.' || c == '[') throw invalid();
        if (c == '/') {
          if (i == componentStart) throw invalid();
          componentStart = i + 1;
        }
      }
      if (componentStart == end) throw invalid();
      return end + 1;
    }
    if ("BCDFIJSZ".indexOf(type) >= 0 || (type == 'V' && allowVoid)) return cursor;
    throw invalid();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("invalid method descriptor");
  }
}
