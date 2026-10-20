package io.github.cheshmakzanoon.portfolio.durableflow;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cheshmakzanoon.portfolio.durableflow.Data.*;
import io.github.cheshmakzanoon.portfolio.durableflow.Definition.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Exercises the legal maximum graph arity/fan-in with near-cap escaped predecessor results. */
final class WorkerEnvelopeFixture {
  static void verify(
      WorkflowStore store, HttpClient client, String base, String token, String tenant)
      throws Exception {
    ObjectMapper json = new ObjectMapper();
    String text = "";
    while (json.writeValueAsBytes(Collections.nCopies(8, Value.text(text + "\""))).length
        <= WorkflowLimits.RESULT_BYTES) text += "\"";
    assertTrue(json.writeValueAsBytes(Collections.nCopies(8, Value.text(text))).length > 4000);
    List<Node> nodes = new ArrayList<>();
    nodes.add(Fixtures.node("root", Kind.ACTIVITY, List.of(), "pure", Safety.PURE, null, 0));
    List<String> first = new ArrayList<>(), second = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      String id = "first-" + i;
      first.add(id);
      nodes.add(Fixtures.node(id, Kind.ACTIVITY, List.of("root"), "pure", Safety.PURE, null, 0));
    }
    nodes.add(Fixtures.node("join", Kind.PARALLEL_ALL, first, null, null, null, 0));
    for (int i = 0; i < 16; i++) {
      String id = "second-" + i;
      second.add(id);
      nodes.add(Fixtures.node(id, Kind.ACTIVITY, List.of("join"), "pure", Safety.PURE, null, 0));
    }
    nodes.add(
        Fixtures.node(
            "finish", Kind.ACTIVITY, second, "reserve", Safety.IDEMPOTENT_WITH_KEY, null, 0));
    Definition definition =
        new Definition(
            "maximum-worker-envelope", 1, nodes, Map.of("first", Type.TEXT, "second", Type.TEXT));
    int padding = 0;
    ExecutionInput input;
    while (true) {
      input =
          new ExecutionInput(
              Value.text(text),
              Map.of(
                  "first",
                  Value.text("\"".repeat(1024)),
                  "second",
                  Value.text("\"".repeat(padding))));
      if (json.writeValueAsBytes(input).length > WorkflowLimits.EXECUTION_INPUT_BYTES) {
        padding--;
        break;
      }
      padding++;
    }
    input =
        new ExecutionInput(
            Value.text(text),
            Map.of(
                "first",
                Value.text("\"".repeat(1024)),
                "second",
                Value.text("\"".repeat(padding))));
    assertTrue(json.writeValueAsBytes(input).length > 4000);
    UUID id = store.start(tenant, store.deploy(tenant, definition), "maximum-envelope", input);
    WorkflowStore.Claim finalClaim = null;
    try (Worker pure = new Worker(store, URI.create("http://127.0.0.1:1/effects"))) {
      for (int round = 0; round < 8 && finalClaim == null; round++) {
        var claims = store.claim(tenant, "maximum-worker", 16, 60);
        assertFalse(claims.isEmpty());
        for (var claim : claims) {
          if (!claim.instance().equals(id)) continue;
          if (claim.task().equals("finish")) finalClaim = claim;
          else store.complete(claim, pure.execute(claim, "normal"));
        }
      }
    }
    assertNotNull(finalClaim);
    TaskInput parameters = json.readValue(finalClaim.parameters(), TaskInput.class);
    assertEquals(128, parameters.arguments().size());
    assertEquals(16, parameters.predecessors().size());
    assertTrue(
        parameters.predecessors().values().stream()
            .allMatch(output -> output.values().size() == 8));
    byte[] claimBody = json.writeValueAsBytes(finalClaim);
    assertTrue(claimBody.length > WorkflowLimits.PUBLIC_REQUEST_BYTES);
    assertTrue(claimBody.length < WorkflowLimits.WORKER_REQUEST_BYTES);
    var tampered =
        new WorkflowStore.Claim(
            finalClaim.instance(),
            finalClaim.tenant(),
            finalClaim.task(),
            finalClaim.activity(),
            finalClaim.safety(),
            finalClaim.parameters() + " ",
            finalClaim.generation(),
            finalClaim.attempt(),
            finalClaim.worker(),
            finalClaim.credential(),
            finalClaim.semantics(),
            finalClaim.outputType());
    long before = store.get(tenant, id).version();
    assertEquals(
        409,
        post(client, base + "/v1/worker/dispatch", token, json.writeValueAsBytes(tampered))
            .statusCode());
    assertEquals(before, store.get(tenant, id).version());
    assertEquals(200, post(client, base + "/v1/worker/dispatch", token, claimBody).statusCode());
    assertEquals(200, post(client, base + "/v1/worker/heartbeat", token, claimBody).statusCode());
    Path directory = Files.createTempDirectory("durableflow-envelope-provider-");
    Path log = directory.resolve("effects.log");
    String receipt;
    try (FakeProvider provider = new FakeProvider(0, log);
        ProviderClient transport =
            new ProviderClient(URI.create("http://127.0.0.1:" + provider.port() + "/effects"))) {
      String providerBody = finalClaim.activity() + ":" + finalClaim.parameters();
      assertTrue(providerBody.getBytes(StandardCharsets.UTF_8).length > 4096);
      String key = finalClaim.instance() + ":" + finalClaim.task();
      receipt = transport.execute(key, finalClaim.safety(), "normal", providerBody);
      assertEquals(1, provider.effectCount());
      assertEquals(receipt, transport.execute(key, finalClaim.safety(), "duplicate", providerBody));
      assertEquals(1, provider.effectCount());
    } finally {
      Files.deleteIfExists(log);
      Files.deleteIfExists(directory);
    }
    byte[] result =
        json.writeValueAsBytes(Map.of("claim", finalClaim, "result", List.of(Value.text(receipt))));
    assertEquals(200, post(client, base + "/v1/worker/complete", token, result).statusCode());
    assertEquals(Interpreter.State.COMPLETED, store.get(tenant, id).state());
    assertEquals(
        Output.of(List.of(Value.text(receipt))), store.get(tenant, id).values().get("finish"));
    assertEquals(
        413,
        post(
                client,
                base + "/v1/worker/dispatch",
                token,
                new byte[WorkflowLimits.WORKER_REQUEST_BYTES + 1])
            .statusCode());
  }

  private static HttpResponse<String> post(HttpClient client, String uri, String token, byte[] body)
      throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(uri))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofInputStream(
                    () -> new java.io.ByteArrayInputStream(body)))
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }
}
