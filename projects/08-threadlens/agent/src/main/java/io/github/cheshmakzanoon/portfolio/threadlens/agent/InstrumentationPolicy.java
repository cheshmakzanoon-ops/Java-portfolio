package io.github.cheshmakzanoon.portfolio.threadlens.agent;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.objectweb.asm.Opcodes;

public final class InstrumentationPolicy {
  private final List<String> packages;
  private final Set<String> methods;
  private final ConcurrentHashMap<String, LongAdder> skips = new ConcurrentHashMap<>();

  public InstrumentationPolicy(List<String> packages) {
    this(packages, List.of());
  }

  public InstrumentationPolicy(List<String> packages, List<String> methods) {
    this.packages = packages.stream().map(s -> s.replace('.', '/') + "/").toList();
    this.methods = Set.copyOf(methods);
  }

  public boolean selectClass(Module module, ClassLoader loader, String name) {
    if (loader != ClassLoader.getSystemClassLoader()) return skip("unsupportedLoader");
    if (module != null && module.isNamed()) return skip("namedModule");
    if (name == null
        || name.startsWith("java/")
        || name.startsWith("javax/")
        || name.startsWith("jdk/")
        || name.startsWith("sun/")
        || name.startsWith("org/objectweb/asm/")
        || (name.startsWith("io/github/cheshmakzanoon/portfolio/threadlens/")
            && !name.startsWith("io/github/cheshmakzanoon/portfolio/threadlens/workloads/")))
      return skip("toolOrJdk");
    if (packages.stream().noneMatch(name::startsWith)) return skip("outsideAllowlist");
    return true;
  }

  public boolean selectMethod(int access, String name) {
    if (name.equals("<init>") || name.equals("<clinit>")) return skip("constructorOrInitializer");
    if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0)
      return skip("nativeOrAbstract");
    if (!methods.isEmpty() && !methods.contains(name)) return skip("outsideMethodSelection");
    return true;
  }

  public boolean skip(String reason) {
    skips.computeIfAbsent(reason, k -> new LongAdder()).increment();
    return false;
  }

  public Map<String, Long> reasons() {
    Map<String, Long> result = new TreeMap<>();
    skips.forEach((k, v) -> result.put(k, v.sum()));
    return result;
  }
}
