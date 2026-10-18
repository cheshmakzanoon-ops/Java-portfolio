package io.github.cheshmakzanoon.portfolio.routeweaver;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.*;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
    packages = "io.github.cheshmakzanoon.portfolio.routeweaver",
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
              "..clearledger..",
              "..durableflow..",
              "..quorumkv..",
              "..pulsestream..",
              "..tenantshield..",
              "..matchcore..",
              "..indexforge..",
              "..threadlens..",
              "..byteguard..");
}
