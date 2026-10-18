package io.github.cheshmakzanoon.portfolio.clearledger;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;

import com.tngtech.archunit.core.importer.*;
import org.junit.jupiter.api.Test;

class DomainArchitectureTest {
  @Test
  void domainHasNoAdapters() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework..", "java.sql..", "jakarta.persistence..")
        .check(
            new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("io.github.cheshmakzanoon.portfolio.clearledger"));
  }
}
