package io.github.cheshmakzanoon.portfolio.majority;

import static io.github.cheshmakzanoon.portfolio.majority.Model.*;
import static io.github.cheshmakzanoon.portfolio.majority.Raft.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Pre-vote, check-quorum and leadership transfer at the single-node transition boundary. */
class RaftHardeningTest {
  private static final Command WRITE =
      new Command("c", 1, Kind.PUT, new Key(new byte[] {1}), new byte[] {1}, null);

  private static List<Effect> stable(Raft n, Event e) {
    List<Effect> effects = n.accept(e);
    if (effects.size() == 1 && effects.getFirst() instanceof Persist p)
      return n.accept(new Persisted(p.token()));
    return effects;
  }

  private static List<Send> sends(List<Effect> effects) {
    return effects.stream().filter(Send.class::isInstance).map(Send.class::cast).toList();
  }

  private static List<Reply> replies(List<Effect> effects) {
    return effects.stream().filter(Reply.class::isInstance).map(Reply.class::cast).toList();
  }

  private static <T extends Rpc> T to(List<Effect> effects, int peer, Class<T> type) {
    return sends(effects).stream()
        .filter(s -> s.peer() == peer && type.isInstance(s.rpc()))
        .map(s -> type.cast(s.rpc()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no " + type.getSimpleName() + " for " + peer));
  }

  private static boolean persists(List<Effect> effects) {
    return effects.stream().anyMatch(Persist.class::isInstance);
  }

  private static Raft hardened(int id, Durable durable) {
    return new Raft(id, 1, durable, Options.HARDENED);
  }

  private static Durable atTerm(long term) {
    return new Durable(term, -1, 0, Snapshot.empty(), List.of());
  }

  /** Runs a hardened node through pre-vote and vote, returning its first replication round. */
  private static List<Effect> elect(Raft n, long at) {
    List<Effect> probes = stable(n, new Tick(at));
    Send probe = sends(probes).getFirst();
    PreVoteRequest pre = (PreVoteRequest) probe.rpc();
    List<Effect> votes =
        stable(n, new Receive(probe.peer(), new PreVoteResponse(n.term(), pre.request(), true)));
    Send vote = sends(votes).getFirst();
    VoteRequest request = (VoteRequest) vote.rpc();
    List<Effect> lead =
        stable(
            n, new Receive(vote.peer(), new VoteResponse(request.term(), request.request(), true)));
    assertEquals(Role.LEADER, n.role());
    return lead;
  }

  /** Acknowledges every append in {@code round}, committing the leader's marker. */
  private static void acknowledge(Raft n, List<Effect> round) {
    for (Send s : sends(round)) {
      AppendRequest a = (AppendRequest) s.rpc();
      stable(
          n,
          new Receive(
              s.peer(),
              new AppendResponse(
                  a.term(), a.request(), true, a.previousIndex() + a.entries().size(), 0)));
    }
  }

  /** One heartbeat round that every peer answers, so check-quorum stays satisfied. */
  private static void beat(Raft n, long at) {
    acknowledge(n, stable(n, new Tick(at)));
  }

  private static Raft caughtUpLeader(long at) {
    Raft n = hardened(0, atTerm(1));
    acknowledge(n, elect(n, at));
    assertEquals(n.durable().log().size(), n.commit());
    return n;
  }

  // ---- pre-vote: the candidate side ------------------------------------------------------

  @Test
  void timeoutAsksForPreVotesWithoutChangingTermVoteOrDisk() {
    Raft n = hardened(0, atTerm(3));
    List<Effect> effects = n.accept(new Tick(1000));
    assertFalse(persists(effects));
    assertEquals(2, effects.size());
    for (int peer : new int[] {1, 2}) {
      PreVoteRequest r = to(effects, peer, PreVoteRequest.class);
      assertEquals(4, r.term(), "campaign term is proposed, not adopted");
      assertEquals(0, r.candidate());
    }
    assertEquals(3, n.term());
    assertEquals(Role.FOLLOWER, n.role());
    assertEquals(-1, n.durable().vote());
  }

  @Test
  void classicTimeoutStillStartsARealElection() {
    Raft n = new Raft(0, 1, atTerm(3));
    List<Effect> effects = n.accept(new Tick(1000));
    assertTrue(persists(effects));
    assertEquals(4, n.term());
    assertEquals(Role.CANDIDATE, n.role());
  }

  @Test
  void aGrantedPreVoteMajorityStartsTheRealElectionInTheNextTerm() {
    Raft n = hardened(0, atTerm(3));
    Send probe = sends(n.accept(new Tick(1000))).getFirst();
    List<Effect> held =
        n.accept(new Receive(probe.peer(), new PreVoteResponse(3, probe.rpc().request(), true)));
    assertInstanceOf(Persist.class, held.getFirst(), "the term bump waits for the disk");
    assertEquals(1, held.size(), "no vote request escapes before the barrier");
    List<Effect> released = n.accept(new Persisted(((Persist) held.getFirst()).token()));
    for (int peer : new int[] {1, 2}) assertEquals(4, to(released, peer, VoteRequest.class).term());
    assertEquals(4, n.term());
    assertEquals(0, n.durable().vote());
    assertEquals(Role.CANDIDATE, n.role());
  }

  @Test
  void rejectedStaleAndRepeatedPreVotesNeverStartAnElection() {
    Raft n = hardened(0, atTerm(3));
    List<Effect> first = n.accept(new Tick(1000));
    PreVoteRequest one = to(first, 1, PreVoteRequest.class);
    PreVoteRequest two = to(first, 2, PreVoteRequest.class);
    assertTrue(n.accept(new Receive(1, new PreVoteResponse(3, one.request(), false))).isEmpty());
    assertTrue(
        n.accept(new Receive(2, new PreVoteResponse(3, two.request() + 1, true))).isEmpty(),
        "a grant for another request is ignored");
    assertTrue(
        n.accept(new Receive(1, new PreVoteResponse(3, two.request(), true))).isEmpty(),
        "a grant is bound to the peer it was asked of");
    assertEquals(3, n.term());
    // A new round invalidates grants for the previous one.
    List<Effect> second = n.accept(new Tick(2000));
    assertEquals(2, sends(second).size());
    assertTrue(n.accept(new Receive(2, new PreVoteResponse(3, two.request(), true))).isEmpty());
    assertEquals(3, n.term());
    assertEquals(-1, n.durable().vote());
  }

  @Test
  void aPreVoteRefusalFromANewerTermIsAdoptedAndEndsTheProbe() {
    Raft n = hardened(0, atTerm(3));
    PreVoteRequest one = to(n.accept(new Tick(1000)), 1, PreVoteRequest.class);
    List<Effect> adopted = n.accept(new Receive(1, new PreVoteResponse(9, one.request(), false)));
    assertTrue(persists(adopted), "adopting a newer term is durable");
    n.accept(new Persisted(((Persist) adopted.getFirst()).token()));
    assertEquals(9, n.term());
    assertEquals(Role.FOLLOWER, n.role());
    // Even a late grant for the abandoned probe cannot resurrect it.
    stable(n, new Receive(2, new PreVoteResponse(9, one.request(), true)));
    assertEquals(9, n.term());
    assertEquals(-1, n.durable().vote());
  }

  @Test
  void aLeaderMessageAbandonsAnOutstandingProbe() {
    Raft n = hardened(0, atTerm(3));
    Send probe = sends(n.accept(new Tick(1000))).getFirst();
    stable(n, new Receive(2, new AppendRequest(3, 5, 2, 0, 0, List.of(), 0, 0)));
    assertEquals(2, n.leader());
    assertTrue(
        n.accept(new Receive(probe.peer(), new PreVoteResponse(3, probe.rpc().request(), true)))
            .isEmpty());
    assertEquals(3, n.term());
    assertEquals(Role.FOLLOWER, n.role());
  }

  @Test
  void aFailedRealElectionRetriesWithAFreshProbeNotAnotherUnprovenTerm() {
    Raft n = hardened(0, atTerm(3));
    Send probe = sends(n.accept(new Tick(1000))).getFirst();
    stable(n, new Receive(probe.peer(), new PreVoteResponse(3, probe.rpc().request(), true)));
    assertEquals(4, n.term());
    assertEquals(Role.CANDIDATE, n.role());
    List<Effect> retry = n.accept(new Tick(2000));
    assertFalse(persists(retry), "timing out as a candidate asks again before bumping the term");
    assertEquals(5, to(retry, 1, PreVoteRequest.class).term());
    assertEquals(4, n.term());
    assertEquals(Role.FOLLOWER, n.role());
  }

  // ---- pre-vote: the voter side ----------------------------------------------------------

  private static PreVoteResponse answer(Raft n, int from, PreVoteRequest request) {
    List<Effect> effects = n.accept(new Receive(from, request));
    assertFalse(persists(effects), "a pre-vote never writes");
    assertEquals(1, effects.size());
    Send send = (Send) effects.getFirst();
    assertEquals(from, send.peer());
    return (PreVoteResponse) send.rpc();
  }

  @Test
  void aPreVoteIsGrantedWithoutAdoptingTheTermVotingOrRestartingTheTimer() {
    Raft n = hardened(0, atTerm(3));
    PreVoteResponse response = answer(n, 1, new PreVoteRequest(100, 7, 1, 0, 0));
    assertTrue(response.granted());
    assertEquals(3, response.term(), "the reply reports the responder's own term");
    assertEquals(7, response.request());
    assertEquals(3, n.term());
    assertEquals(-1, n.durable().vote());
    // The election deadline is unchanged by the probe: the same node, probed at clock 100,
    // campaigns on exactly the same tick as an unprobed twin.
    long unprobed = firstCampaign(hardened(0, atTerm(3)), r -> {});
    long probed =
        firstCampaign(
            hardened(0, atTerm(3)),
            r -> assertTrue(answer(r, 1, new PreVoteRequest(4, 8, 1, 0, 0)).granted()));
    assertEquals(unprobed, probed);
  }

  private static long firstCampaign(Raft n, Consumer<Raft> interference) {
    for (long t = 1; t < 2000; t++) {
      if (t == 100) interference.accept(n);
      if (sends(n.accept(new Tick(t))).stream().anyMatch(s -> s.rpc() instanceof PreVoteRequest))
        return t;
    }
    throw new AssertionError("never campaigned");
  }

  @Test
  void aPreVoteIsDeniedForAnOutdatedTermAWrongIdentityOrAStaleLog() {
    Raft n = hardened(0, new Durable(2, -1, 0, Snapshot.empty(), List.of(new Entry(1, 2, null))));
    assertFalse(answer(n, 1, new PreVoteRequest(2, 1, 1, 1, 2)).granted(), "not a newer term");
    assertFalse(answer(n, 1, new PreVoteRequest(1, 2, 1, 1, 2)).granted(), "an older term");
    assertFalse(answer(n, 1, new PreVoteRequest(3, 3, 2, 1, 2)).granted(), "candidate is not peer");
    assertFalse(answer(n, 1, new PreVoteRequest(3, 4, 1, 0, 0)).granted(), "empty log is behind");
    assertTrue(answer(n, 1, new PreVoteRequest(3, 6, 1, 1, 2)).granted(), "equal log is enough");
    assertTrue(answer(n, 1, new PreVoteRequest(3, 7, 1, 1, 3)).granted(), "newer last term wins");
    assertTrue(answer(n, 1, new PreVoteRequest(3, 8, 1, 5, 2)).granted(), "longer log wins");
    assertFalse(answer(n, 1, new PreVoteRequest(3, 9, 1, 0, 2)).granted(), "shorter log loses");
    assertEquals(2, n.term());
  }

  @Test
  void aFollowerThatRecentlyHeardALeaderRefusesToHelpDepose() {
    Raft n = hardened(0, atTerm(3));
    n.accept(new Tick(100));
    stable(n, new Receive(1, new AppendRequest(3, 5, 1, 0, 0, List.of(), 0, 0)));
    PreVoteRequest probe = new PreVoteRequest(4, 6, 2, 0, 0);
    n.accept(new Tick(100 + LEADER_LEASE_MS - 1));
    assertFalse(answer(n, 2, probe).granted(), "inside the lease");
    n.accept(new Tick(100 + LEADER_LEASE_MS));
    assertTrue(answer(n, 2, new PreVoteRequest(4, 7, 2, 0, 0)).granted(), "lease expired");
  }

  @Test
  void aLeaderNeverGrantsAPreVote() {
    Raft n = hardened(0, atTerm(1));
    elect(n, 1000);
    PreVoteResponse response = answer(n, 1, new PreVoteRequest(50, 9, 1, 100, 100));
    assertFalse(response.granted());
    assertEquals(2, response.term());
    assertEquals(Role.LEADER, n.role());
  }

  @Test
  void aPreVoteWithAnAbsurdTermCannotMoveAnyNode() {
    Raft n = hardened(0, atTerm(3));
    PreVoteResponse response = answer(n, 1, new PreVoteRequest(Long.MAX_VALUE, 1, 1, 0, 0));
    assertTrue(response.granted());
    assertEquals(3, n.term());
    assertEquals(Role.FOLLOWER, n.role());
  }

  // ---- check-quorum ----------------------------------------------------------------------

  @Test
  void aLeaderWithoutAMajorityStepsDownWithoutBumpingItsTerm() {
    Raft n = hardened(0, atTerm(1));
    elect(n, 1000);
    assertEquals(Role.LEADER, n.role());
    assertEquals(2, n.term());
    assertEquals(Role.LEADER, stableRole(n, 1000 + QUORUM_WINDOW_MS - 1));
    List<Effect> expired = n.accept(new Tick(1000 + QUORUM_WINDOW_MS));
    assertEquals(Role.FOLLOWER, n.role());
    assertEquals(2, n.term());
    assertFalse(persists(expired));
    assertEquals(-1, n.leader());
  }

  private static Role stableRole(Raft n, long at) {
    stable(n, new Tick(at));
    return n.role();
  }

  @Test
  void aSteppedDownLeaderFailsItsPendingClientsAsUnknown() {
    Raft n = hardened(0, atTerm(1));
    elect(n, 1000);
    List<Effect> accepted = n.accept(new Mutate(5, WRITE));
    n.accept(new Persisted(((Persist) accepted.getFirst()).token()));
    List<Effect> expired = n.accept(new Tick(1000 + QUORUM_WINDOW_MS));
    assertEquals(
        List.of("UNKNOWN_OUTCOME"), replies(expired).stream().map(r -> r.result().code()).toList());
    assertEquals(5, replies(expired).getFirst().request());
  }

  @Test
  void anyMajorityAcknowledgementKeepsTheLeaderAndOldOnesStopCounting() {
    Raft n = hardened(0, atTerm(1));
    elect(n, 1000);
    List<Effect> beat = stable(n, new Tick(1499));
    AppendRequest heartbeat = to(beat, 1, AppendRequest.class);
    // Even a rejection proves peer 1 is alive and in this term.
    stable(n, new Receive(1, new AppendResponse(2, heartbeat.request(), false, 0, 0)));
    assertEquals(Role.LEADER, stableRole(n, 1500), "peer 1 spoke at 1499");
    assertEquals(Role.LEADER, stableRole(n, 1499 + QUORUM_WINDOW_MS - 1));
    assertEquals(Role.FOLLOWER, stableRole(n, 1499 + QUORUM_WINDOW_MS));
  }

  @Test
  void snapshotAcknowledgementsAlsoCountAsContact() {
    Registers data = new Registers(Snapshot.empty());
    data.apply(new Command("s", 1, Kind.PUT, new Key(new byte[] {1}), new byte[] {1}, null));
    Raft n = hardened(0, new Durable(1, -1, 1, data.snapshot(1, 1), List.of()));
    List<Effect> lead = elect(n, 1000);
    // Peer 1 is too far behind for entries, so it is sent a snapshot.
    AppendRequest first = to(lead, 1, AppendRequest.class);
    List<Effect> snapshot =
        stable(n, new Receive(1, new AppendResponse(2, first.request(), false, 0, 0)));
    SnapshotRequest chunk = to(snapshot, 1, SnapshotRequest.class);
    stable(n, new Tick(1400));
    stable(n, new Receive(1, new SnapshotResponse(2, chunk.request(), 1, chunk.total(), true)));
    assertEquals(Role.LEADER, stableRole(n, 1400 + QUORUM_WINDOW_MS - 1));
  }

  @Test
  void classicLeadersKeepTheirLabelWithoutAnyPeer() {
    Raft n = new Raft(0, 1, atTerm(1));
    Send vote = sends(stable(n, new Tick(1000))).getFirst();
    stable(n, new Receive(vote.peer(), new VoteResponse(2, vote.rpc().request(), true)));
    assertEquals(Role.LEADER, n.role());
    for (long t = 1100; t <= 20_000; t += 100) stable(n, new Tick(t));
    assertEquals(Role.LEADER, n.role());
  }

  @Test
  void aDeposedLeaderWaitsOutAFreshElectionTimeoutBeforeCampaigning() {
    for (Options options : List.of(Options.CLASSIC, Options.HARDENED)) {
      Raft n = new Raft(0, 1, atTerm(1), options);
      if (options.preVote()) elect(n, 1000);
      else {
        Send vote = sends(stable(n, new Tick(1000))).getFirst();
        stable(n, new Receive(vote.peer(), new VoteResponse(2, vote.rpc().request(), true)));
      }
      assertEquals(Role.LEADER, n.role());
      // Leading for a long time leaves the follower-era deadline far in the past.
      for (long t = 1400; t <= 5000; t += 400) beat(n, t);
      stable(n, new Receive(1, new AppendResponse(4, 1, false, 0, 0)));
      assertEquals(Role.FOLLOWER, n.role());
      assertEquals(4, n.term());
      assertTrue(stable(n, new Tick(5001)).isEmpty(), options + " campaigned at once");
      assertTrue(stable(n, new Tick(5000 + 249)).isEmpty(), options + " ignored the timeout");
      assertFalse(
          stable(n, new Tick(5000 + 500)).isEmpty(), options + " never campaigned after waiting");
    }
  }

  // ---- leadership transfer ---------------------------------------------------------------

  @Test
  void aCaughtUpTargetIsToldToCampaignAtOnce() {
    Raft n = caughtUpLeader(1000);
    List<Effect> effects = n.accept(new Transfer(77, 1));
    assertEquals(1, effects.size());
    TimeoutNow now = to(effects, 1, TimeoutNow.class);
    assertEquals(2, now.term());
    assertEquals(0, now.leader());
    assertEquals(Role.LEADER, n.role(), "the old leader stays until a newer term appears");
  }

  @Test
  void aLaggingTargetIsBroughtUpToDateBeforeItIsToldToCampaign() {
    Raft n = caughtUpLeader(1000);
    List<Effect> appended = n.accept(new Mutate(5, WRITE));
    n.accept(new Persisted(((Persist) appended.getFirst()).token()));
    List<Effect> effects = n.accept(new Transfer(77, 1));
    AppendRequest catchUp = to(effects, 1, AppendRequest.class);
    assertTrue(sends(effects).stream().noneMatch(s -> s.rpc() instanceof TimeoutNow));
    long last = catchUp.previousIndex() + catchUp.entries().size();
    assertEquals(n.durable().log().getLast().index(), last);
    List<Effect> done =
        stable(n, new Receive(1, new AppendResponse(2, catchUp.request(), true, last, 0)));
    assertEquals(0, to(done, 1, TimeoutNow.class).leader());
  }

  @Test
  void aLostTimeoutNowIsOfferedAgainOnTheNextHeartbeatReply() {
    Raft n = caughtUpLeader(1000);
    n.accept(new Transfer(77, 1));
    List<Effect> beat = stable(n, new Tick(1100));
    AppendRequest heartbeat = to(beat, 1, AppendRequest.class);
    long last = n.durable().log().getLast().index();
    List<Effect> reply =
        stable(n, new Receive(1, new AppendResponse(2, heartbeat.request(), true, last, 0)));
    assertEquals(2, to(reply, 1, TimeoutNow.class).term());
  }

  @Test
  void writesPauseWhileAHandoffIsPendingAndResumeWhenItEnds() {
    Raft n = caughtUpLeader(1000);
    n.accept(new Transfer(77, 1));
    Reply refused = (Reply) n.accept(new Mutate(6, WRITE)).getFirst();
    assertEquals("UNAVAILABLE", refused.result().code());
    assertEquals(1, refused.result().leader(), "clients are pointed at the incoming leader");
    beat(n, 1400);
    beat(n, 1800);
    List<Effect> expired = n.accept(new Tick(1000 + TRANSFER_TIMEOUT_MS));
    assertEquals(77, replies(expired).getFirst().request());
    assertEquals("TRANSFER_TIMEOUT", replies(expired).getFirst().result().code());
    assertInstanceOf(Persist.class, n.accept(new Mutate(7, WRITE)).getFirst());
  }

  @Test
  void transfersAreRefusedUnlessLeaderWithAValidDistinctTargetAndNoneInFlight() {
    Raft follower = hardened(0, atTerm(1));
    assertEquals(
        "NOT_LEADER", ((Reply) follower.accept(new Transfer(1, 1)).getFirst()).result().code());
    Raft n = caughtUpLeader(1000);
    for (int target : new int[] {-1, 0, 3, Integer.MAX_VALUE})
      assertEquals(
          "INVALID_TRANSFER",
          ((Reply) n.accept(new Transfer(2, target)).getFirst()).result().code(),
          "target " + target);
    n.accept(new Transfer(3, 1));
    Reply busy = (Reply) n.accept(new Transfer(4, 2)).getFirst();
    assertEquals("TRANSFER_IN_PROGRESS", busy.result().code());
    assertEquals(4, busy.request());
  }

  @Test
  void theOperatorLearnsOfSuccessWhenTheTargetBecomesLeader() {
    Raft n = caughtUpLeader(1000);
    n.accept(new Transfer(77, 1));
    // The target campaigns in term 3 and the old leader grants it its vote.
    List<Effect> grant = stable(n, new Receive(1, new VoteRequest(3, 40, 1, 2, 2)));
    assertTrue(((VoteResponse) to(grant, 1, VoteResponse.class)).granted());
    assertEquals(Role.FOLLOWER, n.role());
    List<Effect> settled =
        stable(n, new Receive(1, new AppendRequest(3, 41, 1, 1, 2, List.of(), 0, 0)));
    Reply reply = replies(settled).getFirst();
    assertEquals(77, reply.request());
    assertEquals("OK", reply.result().code());
    assertEquals(1, reply.result().leader());
    assertEquals(1, n.leader());
    // Settled exactly once.
    assertTrue(
        replies(stable(n, new Receive(1, new AppendRequest(3, 42, 1, 1, 2, List.of(), 0, 0))))
            .isEmpty());
  }

  @Test
  void theOperatorIsToldWhenAnotherMemberWinsInstead() {
    Raft n = caughtUpLeader(1000);
    n.accept(new Transfer(77, 1));
    stable(n, new Receive(2, new VoteRequest(3, 40, 2, 2, 2)));
    List<Effect> settled =
        stable(n, new Receive(2, new AppendRequest(3, 41, 2, 1, 2, List.of(), 0, 0)));
    Reply reply = replies(settled).getFirst();
    assertEquals("TRANSFER_SUPERSEDED", reply.result().code());
    assertEquals(2, reply.result().leader());
  }

  @Test
  void aFollowerObeysTimeoutNowOnlyFromItsCurrentLeaderInTheCurrentTerm() {
    for (Consumer<Raft> setup :
        List.<Consumer<Raft>>of(
            r -> {}, // no leader known yet
            r -> stable(r, new Receive(2, new AppendRequest(3, 1, 2, 0, 0, List.of(), 0, 0))))) {
      Raft n = hardened(0, atTerm(3));
      setup.accept(n);
      assertTrue(
          n.accept(new Receive(1, new TimeoutNow(3, 9, 1))).isEmpty(), "not the known leader");
      assertEquals(3, n.term());
    }
    Raft n = hardened(0, atTerm(3));
    stable(n, new Receive(1, new AppendRequest(3, 1, 1, 0, 0, List.of(), 0, 0)));
    assertTrue(n.accept(new Receive(1, new TimeoutNow(2, 9, 1))).isEmpty(), "stale term");
    assertTrue(n.accept(new Receive(1, new TimeoutNow(3, 9, 2))).isEmpty(), "claimed leader");
    assertEquals(3, n.term());
    assertEquals(Role.FOLLOWER, n.role());
  }

  @Test
  void timeoutNowSkipsThePreVoteAndCampaignsInTheNextTerm() {
    Raft n = hardened(0, atTerm(3));
    stable(n, new Receive(1, new AppendRequest(3, 1, 1, 0, 0, List.of(), 0, 0)));
    List<Effect> held = n.accept(new Receive(1, new TimeoutNow(3, 9, 1)));
    assertInstanceOf(Persist.class, held.getFirst());
    List<Effect> released = n.accept(new Persisted(((Persist) held.getFirst()).token()));
    assertEquals(4, to(released, 1, VoteRequest.class).term());
    assertEquals(4, to(released, 2, VoteRequest.class).term());
    assertTrue(sends(released).stream().noneMatch(s -> s.rpc() instanceof PreVoteRequest));
    assertEquals(Role.CANDIDATE, n.role());
    assertEquals(0, n.durable().vote());
  }

  @Test
  void aCandidateIgnoresTimeoutNow() {
    Raft n = new Raft(0, 1, atTerm(3), Options.CLASSIC);
    stable(n, new Tick(1000));
    assertEquals(Role.CANDIDATE, n.role());
    long term = n.term();
    assertTrue(n.accept(new Receive(1, new TimeoutNow(term, 9, 1))).isEmpty());
    assertEquals(term, n.term());
  }
}
