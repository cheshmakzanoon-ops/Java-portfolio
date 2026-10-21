package io.github.cheshmakzanoon.portfolio.majority;

import static io.github.cheshmakzanoon.portfolio.majority.Binary.*;
import static io.github.cheshmakzanoon.portfolio.majority.HistoryChecker.*;
import static io.github.cheshmakzanoon.portfolio.majority.Model.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Assertions are active regardless of -ea. Output records observations, never placeholders. */
public final class ClusterTool {
  private ClusterTool() {}

  public static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  public static Map<String, String> status(KvClient client, int node) throws Exception {
    Result r = client.admin(node, "status", -1);
    require(r.code().equals("OK"), "status unavailable " + node);
    Map<String, String> map = new HashMap<>();
    for (String field : new String(r.value(), StandardCharsets.UTF_8).split(" ")) {
      String[] pair = field.split("=", 2);
      map.put(pair[0], pair[1]);
    }
    return map;
  }

  public static int leader(KvClient client, int excluded) throws Exception {
    long until = System.nanoTime() + 20_000_000_000L;
    while (System.nanoTime() < until) {
      for (int n = 0; n < 3; n++)
        if (n != excluded) {
          try {
            Map<String, String> s = status(client, n);
            if (s.get("role").equals("LEADER") && Long.parseLong(s.get("commit")) > 0) return n;
          } catch (Exception | AssertionError ignored) {
          }
        }
      Thread.sleep(30);
    }
    throw new AssertionError("NO_LEADER_WITHIN_20_SECONDS");
  }

  public static void converge(KvClient client) throws Exception {
    long until = System.nanoTime() + 15_000_000_000L;
    while (System.nanoTime() < until) {
      try {
        String digest = status(client, 0).get("digest");
        if (digest.equals(status(client, 1).get("digest"))
            && digest.equals(status(client, 2).get("digest"))) return;
      } catch (Exception | AssertionError ignored) {
      }
      Thread.sleep(30);
    }
    throw new AssertionError("STATE_DIGESTS_DIVERGED");
  }

  private static void awaitSnapshots(KvClient client) throws Exception {
    long until = System.nanoTime() + 10_000_000_000L;
    while (System.nanoTime() < until) {
      boolean complete = true;
      for (int n = 0; n < 3; n++) {
        var state = status(client, n);
        complete &=
            state.get("snapshot").equals(state.get("applied")) && state.get("disk").equals("false");
      }
      if (complete) return;
      Thread.sleep(20);
    }
    throw new AssertionError("SNAPSHOT_NOT_DURABLE");
  }

  public static void demo(Path directory, int port) throws Exception {
    try (ProcessCluster cluster = new ProcessCluster(directory, port)) {
      cluster.start();
      try (KvClient client = new KvClient(cluster.settings(), "checked");
          KvClient durable = new KvClient(cluster.settings(), "restart-dedup")) {
        int old = leader(client, -1);
        Key key = new Key("register".getBytes(StandardCharsets.UTF_8));
        List<Call> history = new ArrayList<>();
        long start = System.nanoTime();
        Result put = client.mutate(Kind.PUT, key, "one".getBytes(StandardCharsets.UTF_8), null);
        long end = System.nanoTime();
        require(put.code().equals("OK"), "initial PUT");
        history.add(new Call(start, end, Operation.PUT, "one", null, null, put.code()));
        start = System.nanoTime();
        Result failed =
            client.mutate(
                Kind.CAS,
                key,
                "bad".getBytes(StandardCharsets.UTF_8),
                "wrong".getBytes(StandardCharsets.UTF_8));
        end = System.nanoTime();
        require(failed.code().equals("CAS_MISMATCH"), "failed CAS result");
        history.add(
            new Call(
                start,
                end,
                Operation.CAS,
                "bad",
                "wrong",
                new String(failed.value(), StandardCharsets.UTF_8),
                failed.code()));
        start = System.nanoTime();
        Result cas =
            client.mutate(
                Kind.CAS,
                key,
                "two".getBytes(StandardCharsets.UTF_8),
                "one".getBytes(StandardCharsets.UTF_8));
        end = System.nanoTime();
        require(cas.code().equals("OK"), "CAS success");
        history.add(
            new Call(
                start,
                end,
                Operation.CAS,
                "two",
                "one",
                new String(cas.value(), StandardCharsets.UTF_8),
                cas.code()));
        long failureStart = System.nanoTime();
        for (int n = 0; n < 3; n++)
          if (n != old) {
            client.admin(old, "partition", n);
            client.admin(n, "partition", old);
          }
        Result isolatedRead = client.direct(old, new Get(key), 1600);
        require(!isolatedRead.code().equals("OK"), "isolated leader served read");
        Command unknown =
            new Command(
                "unknown", 1, Kind.PUT, key, "unknown".getBytes(StandardCharsets.UTF_8), null);
        long unknownStart = System.nanoTime();
        Result timeout = client.direct(old, new Mutation(unknown), 1600);
        long unknownEnd = System.nanoTime();
        require(!timeout.code().equals("OK"), "isolated leader acknowledged mutation");
        history.add(
            new Call(unknownStart, unknownEnd, Operation.PUT, "unknown", null, null, "TIMEOUT"));
        int replacement = leader(client, old);
        long recoveryStart = System.nanoTime();
        Result majority =
            durable.mutate(
                Kind.PUT,
                new Key("survivor".getBytes(StandardCharsets.UTF_8)),
                "acknowledged".getBytes(StandardCharsets.UTF_8),
                null);
        require(majority.code().equals("OK"), "majority write");
        long recoveryMs = (System.nanoTime() - failureStart) / 1_000_000;
        for (int n = 0; n < 3; n++) client.admin(n, "heal", -1);
        converge(client);
        long getStart = System.nanoTime();
        Result value = client.get(key);
        long getEnd = System.nanoTime();
        require(
            value.code().equals("OK")
                && Arrays.equals(value.value(), "two".getBytes(StandardCharsets.UTF_8)),
            "healed value");
        history.add(new Call(getStart, getEnd, Operation.GET, null, null, "two", value.code()));
        Verdict verdict = new HistoryChecker(1_000_000).check(history, null);
        require(verdict == Verdict.PASS, "history " + verdict);
        long snapStart = System.nanoTime();
        for (int n = 0; n < 3; n++) client.admin(n, "snapshot", -1);
        awaitSnapshots(client);
        long snapshotMs = (System.nanoTime() - snapStart) / 1_000_000;
        converge(client);
        String beforeRestart = status(client, 0).get("digest");
        for (int n = 0; n < 3; n++) cluster.kill(n);
        cluster.start();
        leader(client, -1);
        Result restored = client.get(new Key("survivor".getBytes(StandardCharsets.UTF_8)));
        require(
            restored.code().equals("OK")
                && Arrays.equals(restored.value(), "acknowledged".getBytes(StandardCharsets.UTF_8)),
            "acknowledged value lost after all-process restart");
        try (KvClient retry = new KvClient(cluster.settings(), "restart-dedup")) {
          require(
              retry
                  .mutate(
                      Kind.PUT,
                      new Key("survivor".getBytes(StandardCharsets.UTF_8)),
                      "acknowledged".getBytes(StandardCharsets.UTF_8),
                      null)
                  .equals(majority),
              "dedup after snapshot restart");
        }
        converge(client);
        require(
            beforeRestart.equals(status(client, 0).get("digest")),
            "full state prefix changed across restart");
        require(
            Arrays.equals(client.get(key).value(), "two".getBytes(StandardCharsets.UTF_8)),
            "register prefix lost");
        String json =
            "{\"schemaVersion\":1,\"project\":\"majority\",\"processes\":3,\"tls\":\"TLSv1.3\",\"oldLeader\":"
                + old
                + ",\"replacementLeader\":"
                + replacement
                + ",\"isolatedRead\":\""
                + isolatedRead.code()
                + "\",\"isolatedMutation\":\""
                + timeout.code()
                + "\",\"history\":\""
                + verdict
                + "\",\"historyOperations\":"
                + history.size()
                + ",\"recoveryWriteMs\":"
                + recoveryMs
                + ",\"snapshotWaitMs\":"
                + snapshotMs
                + ",\"restartRead\":\"OK\",\"dedupRestart\":\"OK\",\"digest\":\""
                + status(client, 0).get("digest")
                + "\"}";
        Files.writeString(directory.resolve("demo-result.json"), json + "\n");
        Files.writeString(directory.resolve("history.txt"), history.toString() + "\n");
        System.out.println(json);
      }
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 2) throw new IllegalArgumentException("action directory [basePort]");
    Path directory = Path.of(args[1]);
    int port = args.length > 2 ? Integer.parseInt(args[2]) : 18300;
    switch (args[0]) {
      case "up" -> {
        ProcessCluster cluster = new ProcessCluster(directory, port);
        boolean ready = false;
        try {
          cluster.start();
          try (KvClient client = new KvClient(cluster.settings(), "health")) {
            System.out.println("leader=" + leader(client, -1));
          }
          ready = true;
        } finally {
          if (!ready) cluster.close();
        }
      }
      case "down" -> {
        try (ProcessCluster cluster = ProcessCluster.controller(directory, port)) {}
      }
      case "demo" -> demo(directory, port);
      case "benchmark" ->
          Workload.run(
              directory,
              port,
              args.length > 3 ? Integer.parseInt(args[3]) : 100000,
              args.length > 4 ? Integer.parseInt(args[4]) : 3);
      default -> throw new IllegalArgumentException("unknown action");
    }
  }
}
