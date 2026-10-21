package io.github.cheshmakzanoon.portfolio.handoff;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.*;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
  @Test
  void interpreterAndModelHaveNoAdapterDependencies() {
    noClasses()
        .that()
        .haveSimpleName("Interpreter")
        .or()
        .haveSimpleName("Definition")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework..", "java.sql..", "java.net..", "org.flywaydb..")
        .check(
            new ClassFileImporter()
                .importPackages("io.github.cheshmakzanoon.portfolio.handoff"));
    assertNotNull(Fixtures.media().validate());
    assertNotNull(Fixtures.order(1, Definition.Safety.PURE).validate());
  }
}
