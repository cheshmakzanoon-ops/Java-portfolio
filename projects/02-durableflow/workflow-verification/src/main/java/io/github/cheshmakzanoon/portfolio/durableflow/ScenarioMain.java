package io.github.cheshmakzanoon.portfolio.durableflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cheshmakzanoon.portfolio.durableflow.Definition.*;
import io.github.cheshmakzanoon.portfolio.durableflow.Interpreter.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public final class ScenarioMain {
  private ScenarioMain() {}

  static final ObjectMapper JSON = new ObjectMapper();
  static final List<Process> CHILDREN = new CopyOnWriteArrayList<>();

  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  static WorkflowStore store() {
    return new WorkflowStore(
        System.getenv("DB_URL"), System.getenv("DB_USER"), System.getenv("DB_PASSWORD"));
  }

  static Process child(String main, String... args) throws Exception {
    List<String> command =
        new ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx128m",
                "-Dloader.main=" + main,
                "-cp",
                System.getProperty("java.class.path"),
                "org.springframework.boot.loader.launch.PropertiesLauncher"));
    command.addAll(List.of(args));
    Process process =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(
                ProcessBuilder.Redirect.appendTo(
                    Path.of("target", "scenario-process.log").toFile()))
            .start();
    CHILDREN.add(process);
    return process;
  }

  static void kill(Process process) throws Exception {
    process.destroyForcibly();
    check(process.waitFor(10, TimeUnit.SECONDS), "process failed to stop");
  }

  static void waitFile(Path file, Process process) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (!Files.exists(file) && process.isAlive() && System.nanoTime() < until) Thread.sleep(20);
    check(
        Files.exists(file),
        "worker failed before effect boundary; inspect target/scenario-process.log");
  }

  static long effects(UUID instance, String task) throws Exception {
    Path log = Path.of(System.getenv("EFFECT_LOG"));
    if (!Files.exists(log)) return 0;
    try (var lines = Files.lines(log)) {
      return lines.filter(l -> l.startsWith("EFFECT\t" + instance + ":" + task + "\t")).count();
    }
  }

  static WorkflowStore.Claim ready(WorkflowStore store, String tenant) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < until) {
      store.tick(256);
      List<WorkflowStore.Claim> claims = store.claim(tenant, "demo-worker", 1, 60);
      if (!claims.isEmpty()) return claims.getFirst();
      Thread.sleep(100);
    }
    throw new AssertionError("expected ready activity");
  }

  public static void main(String[] args) throws Exception {
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  CHILDREN.stream().filter(Process::isAlive).forEach(Process::destroyForcibly);
                  for (Process child : CHILDREN) {
                    try {
                      child.waitFor(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                      Thread.currentThread().interrupt();
                      break;
                    }
                  }
                },
                "durableflow-demo-cleanup"));
    Files.createDirectories(Path.of("target"));
    WorkflowStore store = store();
    String tenant = "scenario-" + UUID.randomUUID();
    List<Map<String, Object>> timeline = new ArrayList<>();
    long started = System.nanoTime();
    URI provider = URI.create(System.getenv("PROVIDER_URL"));
    Definition order = Fixtures.order(1, Safety.IDEMPOTENT_WITH_KEY);
    String hash = store.deploy(tenant, order);
    UUID id = store.start(tenant, hash, "order-start", Map.of("order", "synthetic-42"));
    check(
        id.equals(store.start(tenant, hash, "order-start", Map.of("order", "synthetic-42"))),
        "duplicate start changed identity");
    WorkflowStore.Claim first = store.claim(tenant, "killed-worker", 1, 30).getFirst();
    Path marker = Path.of("target", "effect-" + id);
    Process worker =
        child(
            ScenarioWorker.class.getName(),
            Base64.getEncoder().encodeToString(JSON.writeValueAsBytes(first)),
            marker.toAbsolutePath().toString());
    waitFile(marker, worker);
    kill(worker);
    long recoveryStart = System.nanoTime();
    check(effects(id, "reserve") == 1, "independent log has no effect");
    Thread.sleep(31000);
    store.tick(256);
    long version = store.get(tenant, id).version();
    boolean rejected = false;
    try {
      store.complete(first, "stale");
    } catch (IllegalStateException stale) {
      rejected = stale.getMessage().equals("STALE_GENERATION");
    }
    check(
        rejected && version == store.get(tenant, id).version(), "stale completion mutated history");
    try (Worker retry = new Worker(store, provider)) {
      WorkflowStore.Claim successor = ready(store, tenant);
      store.complete(successor, retry.execute(successor, "duplicate"));
    }
    check(effects(id, "reserve") == 1, "idempotent recovery duplicated external effect");
    check(store.get(tenant, id).state() == State.WAITING, "order should wait for approval");
    Path schedulerReady = Path.of("target", "scheduler-ready-" + id).toAbsolutePath();
    Process scheduler = child(RuntimeMain.class.getName(), "scheduler", schedulerReady.toString());
    waitFile(schedulerReady, scheduler);
    kill(scheduler);
    Files.delete(schedulerReady);
    scheduler = child(RuntimeMain.class.getName(), "scheduler", schedulerReady.toString());
    waitFile(schedulerReady, scheduler);
    try {
      check(hash.equals(store.get(tenant, id).definitionHash()), "restart changed pin");
      String upgraded = store.deploy(tenant, Fixtures.order(2, Safety.IDEMPOTENT_WITH_KEY));
      check(
          !hash.equals(upgraded) && hash.equals(store.get(tenant, id).definitionHash()),
          "definition upgrade mutated running execution");
      check(store.signal(tenant, id, "approval", "approval", "yes"), "signal not accepted");
      check(
          store.signal(tenant, id, "approval", "approval", "yes"),
          "duplicate signal not accepted idempotently");
      try (Worker resume = new Worker(store, provider)) {
        WorkflowStore.Claim ship = ready(store, tenant);
        store.complete(ship, resume.execute(ship, "normal"));
      }
      check(store.get(tenant, id).state() == State.COMPLETED, "order did not finish");
    } finally {
      kill(scheduler);
    }
    timeline.add(
        Map.of(
            "event",
            "killed-worker-and-restarted-scheduler-idempotent-recovery",
            "elapsedSeconds",
            (System.nanoTime() - started) / 1e9,
            "effectCount",
            effects(id, "reserve"),
            "recoveryDelaySeconds",
            (System.nanoTime() - recoveryStart) / 1e9));
    String unknownTenant = tenant + "-u";
    Definition unsafe =
        new Definition(
            "uncertain",
            1,
            List.of(
                Fixtures.node(
                    "charge", Kind.ACTIVITY, List.of(), "charge", Safety.NON_IDEMPOTENT, null, 0)));
    UUID unsafeId =
        store.start(unknownTenant, store.deploy(unknownTenant, unsafe), "unknown", Map.of());
    WorkflowStore.Claim uncertain = store.claim(unknownTenant, "unsafe-worker", 1, 30).getFirst();
    Path unsafeMarker = Path.of("target", "effect-" + unsafeId);
    Process unsafeWorker =
        child(
            ScenarioWorker.class.getName(),
            Base64.getEncoder().encodeToString(JSON.writeValueAsBytes(uncertain)),
            unsafeMarker.toAbsolutePath().toString());
    waitFile(unsafeMarker, unsafeWorker);
    kill(unsafeWorker);
    Thread.sleep(31000);
    store.tick(256);
    check(
        store.get(unknownTenant, unsafeId).state() == State.MANUAL_REVIEW,
        "non-idempotent ambiguity hidden");
    check(
        store.claim(unknownTenant, "automatic", 1, 10).isEmpty(),
        "non-idempotent automatically repeated");
    check(effects(unsafeId, "charge") == 1, "unsafe call repeated");
    store.resolve(
        unknownTenant,
        unsafeId,
        "charge",
        "APPLIED",
        "demo-operator",
        "independent receipt confirmed",
        JSON.readValue(
            Files.readString(unsafeMarker),
            new com.fasterxml.jackson.core.type.TypeReference<java.util.List<Data.Value>>() {}));
    check(
        store.history(unknownTenant, unsafeId).getLast().type() == EventType.OPERATOR_RESOLVED,
        "operator decision not appended");
    timeline.add(
        Map.of(
            "event",
            "unknown-outcome-paused-and-audited-resolution",
            "elapsedSeconds",
            (System.nanoTime() - started) / 1e9,
            "effectCount",
            effects(unsafeId, "charge")));
    String undoTenant = tenant + "-c";
    UUID undoId =
        store.start(
            undoTenant,
            store.deploy(undoTenant, order),
            "compensate",
            Map.of("order", "synthetic-cancel"));
    try (Worker action = new Worker(store, provider)) {
      WorkflowStore.Claim reserve = ready(store, undoTenant);
      store.complete(reserve, action.execute(reserve, "normal"));
      store.cancel(undoTenant, undoId);
      for (int n = 0; n < 4; n++) {
        WorkflowStore.Claim undo = ready(store, undoTenant);
        try {
          action.execute(undo, "reject");
          throw new AssertionError("provider should reject compensation");
        } catch (Worker.ProviderRejectedException expected) {
          store.fail(undo, true, "provider rejected compensation");
        }
      }
    }
    check(
        store.get(undoTenant, undoId).state() == State.COMPENSATION_FAILED,
        "failed compensation invisible");
    timeline.add(
        Map.of(
            "event",
            "compensation-failed-visibly",
            "elapsedSeconds",
            (System.nanoTime() - started) / 1e9));
    // Media fixture demonstrates bounded parallel-all joining actual compiled activities.
    String mediaTenant = tenant + "-m";
    UUID media =
        store.start(mediaTenant, store.deploy(mediaTenant, Fixtures.media()), "media", Map.of());
    try (Worker action = new Worker(store, provider)) {
      while (store.get(mediaTenant, media).state() != State.COMPLETED) {
        for (WorkflowStore.Claim q : store.claim(mediaTenant, "media", 4, 60))
          store.complete(q, action.execute(q, "normal"));
      }
    }
    Map<String, Object> result =
        Map.of(
            "schemaVersion",
            1,
            "project",
            "durableflow",
            "workload",
            "asserted-fault-demo-v1",
            "timestamp",
            Instant.now().toString(),
            "assertions",
            Map.of("failed", 0, "scenarios", 5),
            "timeline",
            timeline,
            "orderId",
            id.toString(),
            "unsafeId",
            unsafeId.toString(),
            "compensationId",
            undoId.toString(),
            "mediaId",
            media.toString());
    JSON.writerWithDefaultPrettyPrinter()
        .writeValue(Path.of("target", "demo-result.json").toFile(), result);
    System.out.println(JSON.writeValueAsString(result));
  }
}
