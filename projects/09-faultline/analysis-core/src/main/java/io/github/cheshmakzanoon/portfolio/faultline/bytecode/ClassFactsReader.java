package io.github.cheshmakzanoon.portfolio.faultline.bytecode;

import io.github.cheshmakzanoon.portfolio.faultline.input.*;
import io.github.cheshmakzanoon.portfolio.faultline.interprocedural.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class ClassFactsReader {
  public record ClassFacts(
      String name,
      String superName,
      List<String> interfaces,
      int access,
      boolean application,
      SortedMap<String, MethodIr> methods) {}

  public static final class Incomplete extends RuntimeException {
    public final SortedMap<String, ClassFacts> completed;
    public final List<String> unfinished;

    Incomplete(String message, SortedMap<String, ClassFacts> completed, List<String> unfinished) {
      super(message);
      this.completed = new TreeMap<>(completed);
      this.unfinished = List.copyOf(unfinished);
    }
  }

  public SortedMap<String, ClassFacts> read(InputManifest manifest) throws InputFailure {
    return read(manifest, AnalysisBudget.defaults());
  }

  public SortedMap<String, ClassFacts> read(InputManifest manifest, AnalysisBudget budget)
      throws InputFailure {
    TreeMap<String, ClassFacts> classes = new TreeMap<>();
    for (var entry : manifest.classes().values()) {
      try {
        ClassNode node = new ClassNode();
        new ClassReader(entry.bytes())
            .accept(new BoundedFactsVisitor(node, budget), ClassReader.SKIP_FRAMES);
        TreeMap<String, MethodIr> methods = new TreeMap<>();
        for (MethodNode m : node.methods) {
          MethodIr ir = new MethodIr(node.name, node.sourceFile, m);
          methods.put(ir.id, ir);
        }
        classes.put(
            node.name,
            new ClassFacts(
                node.name,
                node.superName,
                List.copyOf(node.interfaces),
                node.access,
                entry.application(),
                methods));
      } catch (AnalysisBudget.Exhausted exhausted) {
        List<String> frontier = new ArrayList<>();
        boolean unfinished = false;
        for (String name : manifest.classes().keySet()) {
          if (name.equals(entry.logicalName())) unfinished = true;
          if (unfinished) frontier.add("parser:" + name);
        }
        throw new Incomplete(exhausted.getMessage(), classes, frontier);
      } catch (StackOverflowError tooDeep) {
        throw new InputFailure(
            "class structure nesting exceeds parser capacity: " + entry.logicalName());
      } catch (RuntimeException e) {
        throw new InputFailure(
            "malformed class structure: "
                + entry.logicalName()
                + ": "
                + e.getClass().getSimpleName());
      }
    }
    return classes;
  }
}
