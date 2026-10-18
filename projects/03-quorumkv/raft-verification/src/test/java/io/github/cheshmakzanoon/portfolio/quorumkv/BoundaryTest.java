package io.github.cheshmakzanoon.portfolio.quorumkv;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class BoundaryTest {
  @Test
  void coreAndStorageHaveNoNetworkFrameworkOrOtherProjectDependency() {
    var classes =
        new ClassFileImporter()
            .importClasses(Raft.class, Registers.class, Model.class, Journal.class);
    noClasses()
        .that()
        .resideInAPackage("io.github.cheshmakzanoon.portfolio.quorumkv..")
        .should()
        .dependOnClassesThat()
        .resideOutsideOfPackages("java..", "io.github.cheshmakzanoon.portfolio.quorumkv..")
        .check(classes);
  }
}
