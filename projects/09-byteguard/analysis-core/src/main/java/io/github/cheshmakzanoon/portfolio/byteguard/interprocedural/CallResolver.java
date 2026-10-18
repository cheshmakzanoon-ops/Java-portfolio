package io.github.cheshmakzanoon.portfolio.byteguard.interprocedural;

import io.github.cheshmakzanoon.portfolio.byteguard.bytecode.*;
import java.util.*;

public final class CallResolver {
  private final TypeHierarchy hierarchy;

  public CallResolver(TypeHierarchy hierarchy) {
    this.hierarchy = hierarchy;
  }

  public List<String> resolve(String owner, String name, String desc, int opcode) {
    return hierarchy.targets(
        owner,
        name,
        desc,
        opcode == org.objectweb.asm.Opcodes.INVOKEVIRTUAL
            || opcode == org.objectweb.asm.Opcodes.INVOKEINTERFACE);
  }
}
