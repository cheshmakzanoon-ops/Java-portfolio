package io.github.cheshmakzanoon.portfolio.backtrace.agent;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;

/** Reads class bytes only. Never Class.forName, reflective loading, or target initialization. */
final class SafeHierarchy {
  private static final int MAX_RESOURCE_BYTES = 2 * 1024 * 1024;

  record Type(String name, String parent, String[] interfaces, boolean isInterface) {}

  private final ClassLoader loader;
  private final Map<String, Type> cache = new HashMap<>();

  SafeHierarchy(ClassLoader loader, ClassReader target) {
    this.loader = loader;
    cache.put(target.getClassName(), parse(target));
  }

  private Type parse(ClassReader reader) {
    return new Type(
        reader.getClassName(),
        reader.getSuperName(),
        reader.getInterfaces(),
        (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0);
  }

  private Type get(String name) {
    if (name.startsWith("["))
      throw new IllegalArgumentException("array frame merge requires unsupported metadata");
    Type existing = cache.get(name);
    if (existing != null) return existing;
    if (cache.size() >= 256) throw new IllegalArgumentException("hierarchy metadata budget");
    try (InputStream stream = loader.getResourceAsStream(name + ".class")) {
      if (stream == null) throw new IllegalArgumentException("missing safe hierarchy resource");
      byte[] bytes = stream.readNBytes(MAX_RESOURCE_BYTES + 1);
      if (bytes.length > MAX_RESOURCE_BYTES)
        throw new IllegalArgumentException("hierarchy resource byte budget");
      Type type = parse(new ClassReader(bytes));
      if (!type.name().equals(name))
        throw new IllegalArgumentException("hierarchy resource identity mismatch");
      cache.put(name, type);
      return type;
    } catch (IOException e) {
      throw new IllegalArgumentException("unavailable safe hierarchy resource", e);
    }
  }

  private boolean subtype(String child, String parent, Set<String> visited) {
    if (child.equals(parent)) return true;
    if (!visited.add(child)) return false;
    Type type = get(child);
    if (type.parent() != null && subtype(type.parent(), parent, visited)) return true;
    for (String iface : type.interfaces()) if (subtype(iface, parent, visited)) return true;
    return false;
  }

  String common(String left, String right) {
    if (left.equals(right)) return left;
    if (subtype(left, right, new HashSet<>())) return right;
    if (subtype(right, left, new HashSet<>())) return left;
    if (get(left).isInterface() || get(right).isInterface()) return "java/lang/Object";
    Set<String> ancestors = new HashSet<>();
    String candidate = get(left).parent();
    while (candidate != null) {
      if (!ancestors.add(candidate))
        throw new IllegalArgumentException("cyclic hierarchy metadata");
      if (subtype(right, candidate, new HashSet<>())) return candidate;
      candidate = get(candidate).parent();
    }
    return "java/lang/Object";
  }
}
