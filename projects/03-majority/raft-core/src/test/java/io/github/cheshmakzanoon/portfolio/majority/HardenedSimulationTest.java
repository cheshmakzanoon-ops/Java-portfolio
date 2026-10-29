package io.github.cheshmakzanoon.portfolio.majority;

import static io.github.cheshmakzanoon.portfolio.majority.Model.*;
import static io.github.cheshmakzanoon.portfolio.majority.Raft.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Whole-cluster scenarios in virtual time. Each hardened scenario has a classic control showing the
 * behaviour the option removes, so the test fails if the option silently stops working.
 */
class HardenedSimulationTest {
  private static Command put(String client, long sequence, int value) {
    return new Command(
        client, sequence, Kind.PUT, new Key(new byte[] {1}), new byte[] {(byte) value}, null);
  }

  private static Simulator settled(long seed, Options options) {
    Simulator s = new Simulator(seed, options);
    s.reliable();
    s.run(1500);
    assertTrue(s.leader() >= 0, "cluster elects a leader");
    return s;
  }

  @Test
  void anIsolatedFollowerKeepsItsTermAndDoesNotDisturbTheClusterWhenItReturns() {
    Simulator s = settled(7, Options.HARDENED);
    int leader = s.leader(), follower = (leader + 1) % 3;
    long term = s.node(leader).term();
    s.partition(follower, true);
    s.run(10_000);
    assertEquals(term, s.node(follower).term(), "probes without answers never bump the term");
    assertEquals(Role.FOLLOWER, s.node(follower).role());
    assertEquals(leader, s.leader());
    s.partition(follower, false);
    s.run(3_000);
    assertEquals(leader, s.leader(), "the returning member joins; it does not depose");
    for (int i = 0; i < 3; i++) assertEquals(term, s.node(i).term());
  }

  @Test
  void classicIsolationInflatesTheTermAndDeposesTheLeaderOnReturn() {
    Simulator s = settled(7, Options.CLASSIC);
    int leader = s.leader(), follower = (leader + 1) % 3;
    long term = s.node(leader).term();
    s.partition(follower, true);
    s.run(10_000);
    assertTrue(s.node(follower).term() > term + 5, "an unreachable member keeps campaigning");
    s.partition(follower, false);
    s.run(3_000);
    assertTrue(s.node(s.leader()).term() > term, "its inflated term forced a needless election");
  }

  @Test
  void anIsolatedLeaderStepsDownAndTheMajorityKeepsItsTermWhenItReturns() {
    Simulator s = settled(21, Options.HARDENED);
    int old = s.leader();
    long oldTerm = s.node(old).term();
    s.partition(old, true);
    s.run(1_200);
    assertEquals(Role.FOLLOWER, s.node(old).role(), "no majority within the window");
    int replacement = s.leader();
    assertNotEquals(old, replacement);
    long term = s.node(replacement).term();
    assertEquals(oldTerm + 1, term, "exactly one election replaced the leader");
    s.run(8_000);
    assertEquals(oldTerm, s.node(old).term(), "the stranded member never raised its term");
    s.partition(old, false);
    s.run(3_000);
    assertEquals(replacement, s.leader());
    for (int i = 0; i < 3; i++) assertEquals(term, s.node(i).term());
  }

  @Test
  void aClassicIsolatedLeaderKeepsBelievingItLeads() {
    Simulator s = settled(21, Options.CLASSIC);
    int old = s.leader();
    s.partition(old, true);
    s.run(1_200);
    assertEquals(Role.LEADER, s.node(old).role());
    int a = (old + 1) % 3, b = (old + 2) % 3;
    assertTrue(s.node(a).role() == Role.LEADER || s.node(b).role() == Role.LEADER);
    assertTrue(s.node(old).term() < Math.max(s.node(a).term(), s.node(b).term()));
  }

  @Test
  void leadershipMovesToACaughtUpFollowerAndWritesContinue() {
    Simulator s = settled(11, Options.HARDENED);
    int leader = s.leader(), target = (leader + 1) % 3;
    long before = s.node(leader).term();
    long write = s.mutate(leader, put("a", 1, 1));
    s.run(300);
    assertEquals("OK", s.replies.get(write).code());
    long transfer = s.transfer(leader, target);
    s.run(1_500);
    assertEquals("OK", s.replies.get(transfer).code());
    assertEquals(target, s.replies.get(transfer).leader());
    assertEquals(target, s.leader());
    for (int i = 0; i < 3; i++) assertEquals(before + 1, s.node(i).term(), "exactly one election");
    long next = s.mutate(target, put("a", 2, 2));
    s.run(500);
    assertEquals("OK", s.replies.get(next).code());
  }

  @Test
  void aTargetThatFellBehindCatchesUpBeforeItTakesOver() {
    Simulator s = settled(13, Options.HARDENED);
    int leader = s.leader(), target = (leader + 1) % 3, other = (leader + 2) % 3;
    s.partition(target, true);
    for (int i = 1; i <= 5; i++) s.mutate(leader, put("w", i, i));
    s.run(600);
    assertTrue(s.node(target).applied() < s.node(leader).applied());
    s.partition(target, false);
    long transfer = s.transfer(leader, target);
    s.run(2_000);
    assertEquals("OK", s.replies.get(transfer).code());
    assertEquals(target, s.leader());
    assertEquals(s.node(other).commit(), s.node(target).commit());
    assertEquals(5, s.node(target).view().sessions().get("w").sequence());
  }

  @Test
  void aTransferToAnUnreachableMemberTimesOutAndTheLeaderResumesWriting() {
    Simulator s = settled(17, Options.HARDENED);
    int leader = s.leader(), target = (leader + 1) % 3;
    s.partition(target, true);
    long transfer = s.transfer(leader, target);
    long paused = s.mutate(leader, put("p", 1, 1));
    s.run(100);
    assertEquals("UNAVAILABLE", s.replies.get(paused).code(), "writes pause during the handoff");
    s.run(1_200);
    assertEquals("TRANSFER_TIMEOUT", s.replies.get(transfer).code());
    assertEquals(leader, s.leader());
    long resumed = s.mutate(leader, put("p", 1, 1));
    s.run(500);
    assertEquals("OK", s.replies.get(resumed).code());
  }

  @Test
  void onlyTheLeaderCanBeAskedToTransfer() {
    Simulator s = settled(19, Options.HARDENED);
    int follower = (s.leader() + 1) % 3;
    long refused = s.transfer(follower, s.leader());
    assertEquals("NOT_LEADER", s.replies.get(refused).code());
    assertEquals(s.leader(), s.replies.get(refused).leader());
  }

  @Test
  void transfersUnderLossStillLeaveExactlyOneLeaderPerTerm() {
    for (int seed = 0; seed < 20; seed++) {
      Simulator s = new Simulator(seed, Options.HARDENED);
      s.run(1_500);
      if (s.leader() < 0) continue;
      s.transfer(s.leader(), (s.leader() + 1) % 3);
      s.run(3_000);
      s.reliable();
      s.run(3_000);
      assertTrue(s.leader() >= 0, "seed " + seed);
    }
  }
}
