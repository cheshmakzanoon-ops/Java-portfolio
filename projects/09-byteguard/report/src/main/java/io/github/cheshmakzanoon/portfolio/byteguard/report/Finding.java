package io.github.cheshmakzanoon.portfolio.byteguard.report;

import io.github.cheshmakzanoon.portfolio.byteguard.bytecode.*;
import java.util.*;

public record Finding(
    String rule,
    String ruleVersion,
    String severity,
    String confidence,
    String fingerprint,
    String sourceDescriptor,
    String sinkDescriptor,
    InstructionId source,
    InstructionId sink,
    List<String> callPath,
    boolean provenanceTruncated,
    Location sourceLocation,
    Location sinkLocation,
    List<String> limitations) {
  public record Location(String uri, int line, String method, int instruction) {}

  public Finding {
    callPath = List.copyOf(callPath);
    limitations = List.copyOf(limitations);
  }
}
