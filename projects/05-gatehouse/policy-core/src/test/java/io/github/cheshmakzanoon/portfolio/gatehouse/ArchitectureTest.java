package io.github.cheshmakzanoon.portfolio.gatehouse;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
  @Test
  void policyDomainHasNoFrameworkOrPersistenceDependency() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework..", "java.sql..", "com.zaxxer..", "org.flywaydb..")
        .check(
            new ClassFileImporter()
                .importPackages("io.github.cheshmakzanoon.portfolio.gatehouse"));
  }
}
