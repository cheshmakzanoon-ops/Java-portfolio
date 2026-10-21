package io.github.cheshmakzanoon.portfolio.faultline.report;

import io.github.cheshmakzanoon.portfolio.faultline.bytecode.InstructionId;
import io.github.cheshmakzanoon.portfolio.faultline.input.BoundedJarReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Lossless identities while retaining established hashes for ordinary JVM names. */
final class StableIdentity {
  private StableIdentity() {}

  static String method(String method) {
    return ordinary(method)
        ? BoundedJarReader.sha(method.getBytes(StandardCharsets.UTF_8))
        : structured("method", method);
  }

  static String finding(String rule, InstructionId source, InstructionId sink) {
    if (ordinary(rule) && ordinary(source.method()) && ordinary(sink.method()))
      return BoundedJarReader.sha(
          (rule + "|" + source + "|" + sink).getBytes(StandardCharsets.UTF_8));
    return structured(
        "finding",
        rule,
        source.method(),
        Integer.toString(source.ordinal()),
        sink.method(),
        Integer.toString(sink.ordinal()));
  }

  private static boolean ordinary(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '|') return false;
      if (Character.isHighSurrogate(c)) {
        if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) return false;
      } else if (Character.isLowSurrogate(c)) return false;
    }
    return true;
  }

  private static String structured(String domain, String... fields) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream output = new DataOutputStream(bytes);
      // No legacy finding starts with NUL; domains also separate method/report identities.
      output.writeByte(0);
      output.writeUTF("faultline/structured/v1/" + domain);
      output.writeInt(fields.length);
      for (String field : fields) {
        output.writeInt(field.length());
        for (int i = 0; i < field.length(); i++) output.writeChar(field.charAt(i));
      }
      return BoundedJarReader.sha(bytes.toByteArray());
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
