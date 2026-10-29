package io.github.cheshmakzanoon.portfolio.majority;

import static io.github.cheshmakzanoon.portfolio.majority.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class SimulationTest {
  @Test
  void recordedSeededFaultSchedules() throws Exception {
    int seeds = Integer.getInteger("majority.seeds", 30);
    for (Raft.Options options : List.of(Raft.Options.CLASSIC, Raft.Options.HARDENED)) {
      String mode = options.preVote() ? "hardened" : "classic";
      for (int seed = 0; seed < seeds; seed++) {
        Simulator s = new Simulator(seed, options);
        try {
          s.faultSchedule(180);
        } catch (Throwable error) {
          Path p = Path.of("target", "failure-" + mode + "-seed-" + seed + ".trace");
          Files.createDirectories(p.getParent());
          Files.writeString(p, s.trace());
          throw new AssertionError(mode + " seed=" + seed + " trace=" + p, error);
        }
      }
    }
    Files.createDirectories(Path.of("target"));
    Files.writeString(
        Path.of("target", "simulation-seeds.txt"),
        "seeds=0.."
            + (seeds - 1)
            + " actions=180 modes=classic,hardened all invariants checked after every event\n");
  }

  @Test
  void isolatedLeaderCannotReadOrCommitAndSnapshotFollowerCatchesUp() {
    Simulator s = new Simulator(88);
    s.reliable();
    s.run(1000);
    int leader = s.leader();
    assertTrue(leader >= 0);
    long write =
        s.mutate(
            leader, new Command("x", 1, Kind.PUT, new Key(new byte[] {1}), new byte[] {9}, null));
    s.run(500);
    assertEquals("OK", s.replies.get(write).code());
    s.partition(leader, true);
    long read = s.read(leader, new Key(new byte[] {1}));
    long unknown =
        s.mutate(
            leader, new Command("x", 2, Kind.PUT, new Key(new byte[] {1}), new byte[] {8}, null));
    s.run(800);
    assertTrue(s.replies.get(read) == null || !s.replies.get(read).code().equals("OK"));
    assertNull(s.replies.get(unknown));
    s.partition(leader, false);
    s.run(1500);
    for (int i = 0; i < 3; i++) assertEquals(s.node(0).commit(), s.node(i).commit());
  }

  @Test
  void isolatedReadQuotaExpiresAndHealingResumesReads() {
    Simulator s = new Simulator(88);
    s.reliable();
    s.run(1000);
    int leader = s.leader();
    s.partition(leader, true);
    for (int i = 0; i < MAX_PENDING + 10; i++) s.read(leader, new Key(new byte[] {1}));
    s.run(1100);
    assertTrue(s.replies.values().stream().anyMatch(r -> r.code().equals("UNAVAILABLE")));
    s.partition(leader, false);
    s.run(1500);
    int healed = s.leader();
    long read = s.read(healed, new Key(new byte[] {1}));
    s.run(300);
    assertEquals("OK", s.replies.get(read).code());
  }

  @Test
  void seed27CrashBetweenCommitAndDurabilityAck() {
    Simulator s = new Simulator(27);
    s.faultSchedule(180);
  }

  @Test
  void seed417StaleCandidateCannotResetRejectedVoteTimers() {
    Simulator s = new Simulator(417);
    s.faultSchedule(180);
  }
}
