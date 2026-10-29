package io.github.cheshmakzanoon.portfolio.faultline.cli;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.faultline.rules.RuleModels;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelListingTest {
  @TempDir Path temp;

  private record Run(int exit, String out, String err) {}

  private static Run run(String... args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
    int exit =
        Faultline.execute(
            args,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
    return new Run(
        exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }

  @Test
  void theBuiltInModelListsEveryRuleSourceSinkAndSanitizer() {
    Run run = run("rules");
    assertEquals(0, run.exit(), run.err());
    assertTrue(run.out().startsWith("Faultline rule model 1.1.0 (built-in)"));
    for (String rule : RuleModels.CONTEXTS.keySet()) assertTrue(run.out().contains(rule), rule);
    assertTrue(run.out().contains("sources (17)"));
    assertTrue(run.out().contains("sinks (29)"));
    assertTrue(run.out().contains("sanitizers (7)"));
    assertTrue(
        run.out()
            .matches(
                "(?s).*BG-LDAP-FILTER\\s+arg 1\\s+javax/naming/directory/DirContext\\.search\\(.*"));
    assertTrue(run.out().contains("modeled structurally"));
    assertEquals(run.out(), run("rules").out(), "listing is deterministic");
  }

  @Test
  void aSuppliedDocumentIsListedInsteadOfTheBuiltInModelAndValidatedFirst() throws Exception {
    Run shipped = run("rules", "--rules", "../rules/core.yaml");
    assertEquals(0, shipped.exit(), shipped.err());
    assertTrue(shipped.out().contains("(../rules/core.yaml)"));
    assertEquals(
        run("rules").out().replace("(built-in)", "(../rules/core.yaml)"),
        shipped.out(),
        "the shipped document is exactly the built-in model");

    Path broken = temp.resolve("broken.json");
    Files.writeString(broken, "{\"model\":\"faultline-core\"}");
    Run invalid = run("rules", "--rules", broken.toString());
    assertEquals(2, invalid.exit());
    assertTrue(invalid.err().contains("INVALID/FAILED"));
    assertEquals("", invalid.out());
  }

  @Test
  void aLegacyDocumentShowsTheNewerRulesAsHavingNoSinks() throws Exception {
    String legacy =
        "{\"model\":\"faultline-core\",\"version\":\"1.0.0\",\"rules\":["
            + "{\"id\":\"BG-SQL-TEXT\",\"contextMask\":1},{\"id\":\"BG-SHELL-COMMAND\",\"contextMask\":2},"
            + "{\"id\":\"BG-PATH-ACCESS\",\"contextMask\":4}],\"sources\":[],\"sinks\":["
            + "{\"descriptor\":\"example/Db.run(Ljava/lang/String;)V\",\"rule\":\"BG-SQL-TEXT\",\"mask\":1,\"argument\":0}],"
            + "\"sanitizers\":[]}";
    Path file = temp.resolve("legacy.json");
    Files.writeString(file, legacy);
    Run run = run("rules", "--rules", file.toString());
    assertEquals(0, run.exit(), run.err());
    assertTrue(run.out().matches("(?s).*BG-SQL-TEXT\\s+context 1\\s+sinks 1\\s+sanitizers 0\\n.*"));
    assertTrue(
        run.out().matches("(?s).*BG-URL-FETCH\\s+context 16\\s+sinks 0\\s+sanitizers 0\\n.*"));
    assertTrue(run.out().contains("sources (0)"));
  }
}
