package io.github.cheshmakzanoon.portfolio.handoff;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefinitionToolTest {
  private static final Path EXAMPLES = Path.of("..", "examples");
  @TempDir Path temp;

  private record Result(int exit, String out, String err) {}

  private static Result run(String... args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
    int exit =
        DefinitionTool.run(
            args,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
    return new Result(
        exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }

  private static String example(String name) {
    return EXAMPLES.resolve(name).toString();
  }

  @Test
  void theOrderExampleIsFlaggedForItsTimeoutTrap() {
    Result result = run("lint", example("order-fulfilment.json"));
    assertEquals(1, result.exit(), "warnings exit 1");
    assertTrue(result.out().startsWith("order-fulfilment v1\n"), result.out());
    assertTrue(
        result
            .out()
            .contains(
                "3 nodes, 2 edges: 2 activities (2 external effects, 2 compensable), 1 signals, 0 timers, 0 branches, 0 joins"),
        result.out());
    assertTrue(result.out().contains("longest wait 3600 s"));
    assertTrue(result.out().contains("WARNING TIMEOUT_LEAVES_EFFECT_APPLIED"), result.out());
    assertTrue(result.out().contains("[approval]"));
    assertTrue(result.out().endsWith("0 error(s), 1 warning(s), 0 note(s)\n"), result.out());
    assertEquals("", result.err());
  }

  @Test
  void theRepairedOrderExampleIsClean() {
    Result result = run("lint", example("order-fulfilment-v2.json"));
    assertEquals(0, result.exit(), result.out());
    assertTrue(result.out().endsWith("0 error(s), 0 warning(s), 0 note(s)\n"), result.out());
  }

  @Test
  void theMediaExampleWarnsThatTheThumbnailCannotBeUndoneIfPublishingFails() {
    Result result = run("lint", example("media-processing.json"));
    assertEquals(1, result.exit(), result.out());
    assertTrue(result.out().contains("WARNING EFFECT_NEVER_UNDONE"), result.out());
    assertTrue(
        result.out().contains("[thumbnail] 'thumbnail' has no compensation, so if 'publish' fails"),
        result.out());
    assertTrue(
        result
            .out()
            .contains(
                "5 nodes, 5 edges: 4 activities (3 external effects, 2 compensable), 0 signals, 0 timers, 0 branches, 1 joins"));
    assertTrue(
        result
            .out()
            .contains("longest chain 4, widest fan-out 2, widest fan-in 2, longest wait 0 s"));
  }

  @Test
  void lintJsonIsStructured() throws Exception {
    Result result = run("lint", example("order-fulfilment.json"), "--format", "json");
    assertEquals(1, result.exit());
    JsonNode json = new ObjectMapper().readTree(result.out());
    assertTrue(json.get("valid").asBoolean());
    assertEquals(3, json.at("/metrics/nodes").asInt());
    assertEquals(3600, json.at("/metrics/longestWaitSeconds").asLong());
    assertEquals("TIMEOUT_LEAVES_EFFECT_APPLIED", json.at("/findings/0/code").asText());
    assertEquals("WARNING", json.at("/findings/0/severity").asText());
    assertEquals("approval", json.at("/findings/0/node").asText());
  }

  @Test
  void anInvalidDefinitionExitsWithTwoAndNamesTheEnginesReason() throws Exception {
    Path bad = temp.resolve("bad.json");
    Files.writeString(
        bad,
        Files.readString(EXAMPLES.resolve("order-fulfilment.json"))
            .replace("\"order-fulfilment\"", "\"Bad Name\""));
    Result result = run("lint", bad.toString());
    assertEquals(2, result.exit());
    assertTrue(result.out().contains("ERROR   INVALID_DEFINITION"), result.out());
    assertTrue(result.out().contains("definition bounds"), result.out());
    assertTrue(result.out().endsWith("1 error(s), 0 warning(s), 0 note(s)\n"));
    assertEquals(2, run("graph", bad.toString()).exit(), "an invalid definition cannot be drawn");
    assertEquals(2, run("simulate", bad.toString()).exit());
  }

  @Test
  void unreadableMalformedUnknownAndOversizedFilesAreRejectedCleanly() throws Exception {
    Path malformed = temp.resolve("malformed.json");
    Files.writeString(malformed, "{\"name\": ");
    Result broken = run("lint", malformed.toString());
    assertEquals(2, broken.exit());
    assertTrue(
        broken.err().startsWith("handoff: malformed.json is not a valid Definition"), broken.err());

    Path unknown = temp.resolve("unknown.json");
    Files.writeString(unknown, "{\"name\":\"x\",\"version\":1,\"nodes\":[],\"surprise\":true}");
    assertEquals(2, run("lint", unknown.toString()).exit());

    assertEquals(2, run("lint", temp.resolve("absent.json").toString()).exit());
    assertEquals(2, run("lint", temp.toString()).exit(), "a directory is not a file");

    Path link = temp.resolve("link.json");
    Files.createSymbolicLink(link, EXAMPLES.resolve("order-fulfilment.json").toAbsolutePath());
    assertEquals(2, run("lint", link.toString()).exit(), "links are refused");

    Path huge = temp.resolve("huge.json");
    Files.write(huge, new byte[(int) DefinitionTool.MAXIMUM_FILE_BYTES + 1]);
    Result large = run("lint", huge.toString());
    assertEquals(2, large.exit());
    assertTrue(large.err().contains("exceeds 1 MiB"));
  }

  @Test
  void graphsRenderInBothSyntaxesAndRejectOtherFormats() {
    Result mermaid = run("graph", example("order-fulfilment-v2.json"));
    assertEquals(0, mermaid.exit());
    assertTrue(mermaid.out().startsWith("flowchart TD\n"));
    assertTrue(
        mermaid.out().contains("n3{\"timed-out<br/>status.approval==TIMED_OUT\"}"), mermaid.out());
    assertTrue(mermaid.out().contains("-. \"when true\" .->"), mermaid.out());
    Result dot = run("graph", example("order-fulfilment-v2.json"), "--format", "dot");
    assertEquals(0, dot.exit());
    assertTrue(dot.out().startsWith("digraph \"order-fulfilment v2\" {"), dot.out());
    assertEquals(2, run("graph", example("order-fulfilment.json"), "--format", "svg").exit());
  }

  @Test
  void theUnscriptedOrderShowsTheTrapTheLinterPredicted() {
    Result result = run("simulate", example("order-fulfilment.json"));
    assertEquals(0, result.exit());
    assertTrue(
        result
            .out()
            .matches("(?s).*\\n  t\\+3600s\\s+#4\\s+TIMER_FIRED\\s+approval\\s+timeout\\n.*"),
        result.out());
    assertTrue(result.out().contains("t+3600s"), result.out());
    assertTrue(result.out().contains("final state: COMPLETED"));
    assertTrue(
        result.out().contains("node outcomes: reserve=VALUE approval=TIMED_OUT ship=SKIPPED"),
        result.out());
    assertTrue(
        result.out().contains("effects completed whose compensation did not run: reserve"),
        result.out());
  }

  @Test
  void theRepairedOrderReleasesTheReservationExplicitlyOnTimeout() {
    Result result = run("simulate", example("order-fulfilment-v2.json"));
    assertEquals(0, result.exit());
    assertTrue(
        result.out().matches("(?s).*t\\+3600s\\s+#6\\s+COMPLETED\\s+release-on-timeout\\s+ok\\n.*"),
        result.out());
    // The explicit release is not recognised as the declared compensation: stated, not hidden.
    assertTrue(result.out().contains("compensation did not run: reserve"), result.out());
    assertTrue(
        result.out().contains("ship=SKIPPED timed-out=VALUE release-on-timeout=VALUE"),
        result.out());
  }

  @Test
  void scenarioFilesDriveFailureUnknownOutcomeAndCancellation() {
    Result failure =
        run(
            "simulate",
            example("order-fulfilment.json"),
            example("order-ship-fails.scenario.json"));
    assertTrue(failure.out().contains("final state: FAILED"), failure.out());
    assertTrue(failure.out().contains("compensated (in order): reserve"), failure.out());
    assertFalse(failure.out().contains("did not run"));

    Result lost =
        run(
            "simulate",
            example("order-fulfilment.json"),
            example("order-lost-acknowledgement.scenario.json"));
    assertTrue(lost.out().contains("OPERATOR_RESOLVED"), lost.out());
    assertTrue(lost.out().contains("final state: COMPLETED"));

    Result cancelled =
        run("simulate", example("order-fulfilment.json"), example("order-cancelled.scenario.json"));
    assertTrue(cancelled.out().contains("final state: CANCELLED"), cancelled.out());
    assertTrue(cancelled.out().contains("compensated (in order): reserve"));

    Result media = run("simulate", example("media-processing.json"));
    assertTrue(media.out().contains("final state: COMPLETED"), media.out());
    assertTrue(
        media
            .out()
            .contains(
                "node outcomes: upload=VALUE transcode=VALUE thumbnail=VALUE join=VALUE publish=VALUE"),
        media.out());
  }

  @Test
  void simulationJsonCarriesTheSameFacts() throws Exception {
    Result result =
        run(
            "simulate",
            example("order-fulfilment.json"),
            example("order-ship-fails.scenario.json"),
            "--format",
            "json");
    assertEquals(0, result.exit());
    JsonNode json = new ObjectMapper().readTree(result.out());
    assertEquals("FAILED", json.get("finalState").asText());
    assertEquals("3", json.get("semantics").asText());
    assertEquals(64, json.get("definitionHash").asText().length());
    assertEquals("STARTED", json.at("/steps/0/type").asText());
    assertEquals(0, json.at("/steps/0/atSeconds").asInt());
    assertEquals("reserve", json.at("/compensationOrder/0").asText());
    assertEquals("VALUE", json.at("/outcomes/reserve").asText());
    assertTrue(json.get("uncompensatedEffects").isEmpty());
  }

  @Test
  void badScenariosAndUsageAreExitTwo() throws Exception {
    Path bad = temp.resolve("bad.scenario.json");
    Files.writeString(bad, "{\"unknown\":{\"reserve\":\"MAYBE\"}}");
    assertEquals(2, run("simulate", example("order-fulfilment.json"), bad.toString()).exit());
    Files.writeString(bad, "{\"extra\":1}");
    assertEquals(2, run("simulate", example("order-fulfilment.json"), bad.toString()).exit());
    Files.writeString(bad, "{\"context\":{\"nope\":\"x\"}}");
    assertEquals(2, run("simulate", example("order-fulfilment.json"), bad.toString()).exit());
    for (String[] args :
        new String[][] {
          {},
          {"lint"},
          {"unknown", "x.json"},
          {"lint", "a.json", "b.json"},
          {"lint", "a.json", "--format"},
          {"simulate", "a.json", "b.json", "c.json"},
          {"lint", example("order-fulfilment.json"), "--format", "yaml"},
          {"simulate", example("order-fulfilment.json"), "--format", "xml"}
        }) assertEquals(2, run(args).exit(), String.join(" ", args));
    assertTrue(run().err().contains("lint definition.json"));
  }
}
