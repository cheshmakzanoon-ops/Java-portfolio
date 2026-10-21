package io.github.cheshmakzanoon.portfolio.switchback;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.*;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
    packages = "io.github.cheshmakzanoon.portfolio.switchback",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
  @ArchTest
  static final ArchRule immutableDomainHasNoSolverOrFramework =
      noClasses()
          .that()
          .haveNameMatching(".*Model(\\$.*)?")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("org.springframework..", "ai.timefold..", "java.sql..");

  @ArchTest
  static final ArchRule projectsRemainIndependent =
      noClasses()
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..daybook..",
              "..handoff..",
              "..majority..",
              "..latearrival..",
              "..gatehouse..",
              "..thepit..",
              "..stacks..",
              "..backtrace..",
              "..faultline..");
}
