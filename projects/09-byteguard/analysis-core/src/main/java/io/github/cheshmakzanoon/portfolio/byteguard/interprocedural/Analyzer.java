package io.github.cheshmakzanoon.portfolio.byteguard.interprocedural;

import io.github.cheshmakzanoon.portfolio.byteguard.bytecode.*;
import io.github.cheshmakzanoon.portfolio.byteguard.input.*;
import io.github.cheshmakzanoon.portfolio.byteguard.rules.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class Analyzer {
  public record Result(
      InputManifest manifest,
      SortedMap<String, ClassFactsReader.ClassFacts> classes,
      SummaryWorklist.Result analysis) {}

  public Result scan(
      List<Path> input,
      List<Path> classpath,
      int release,
      InputLimits limits,
      AnalysisBudget budget)
      throws IOException {
    return scan(input, classpath, release, limits, budget, new RuleModels());
  }

  public Result scan(
      List<Path> input,
      List<Path> classpath,
      int release,
      InputLimits limits,
      AnalysisBudget budget,
      RuleModels models)
      throws IOException {
    InputManifest manifest = new BoundedJarReader(limits, release).read(input, classpath);
    SortedMap<String, ClassFactsReader.ClassFacts> classes;
    try {
      classes = new ClassFactsReader().read(manifest, budget);
    } catch (ClassFactsReader.Incomplete incomplete) {
      return new Result(
          manifest,
          incomplete.completed,
          new SummaryWorklist.Result(
              new TreeMap<>(),
              new TreeSet<>(Set.of("RESOURCE_LIMIT " + incomplete.getMessage())),
              true,
              incomplete.unfinished,
              budget.work(),
              Map.of(),
              List.of(),
              new TreeMap<>()));
    }
    try {
      return new Result(
          manifest, classes, new SummaryWorklist().analyze(classes, budget, false, models));
    } catch (RuntimeException e) {
      throw new InputFailure("invalid/unsupported bytecode: " + e.getMessage());
    }
  }
}
