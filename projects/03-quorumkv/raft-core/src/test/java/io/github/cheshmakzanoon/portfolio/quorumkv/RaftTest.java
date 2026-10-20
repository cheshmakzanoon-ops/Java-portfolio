package io.github.cheshmakzanoon.portfolio.quorumkv;

import static io.github.cheshmakzanoon.portfolio.quorumkv.Model.*;
import static io.github.cheshmakzanoon.portfolio.quorumkv.Raft.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class RaftTest {
  private List<Effect> stable(Raft n, Event e) {
    List<Effect> effects = n.accept(e);
    if (effects.size() == 1 && effects.getFirst() instanceof Persist p)
      return n.accept(new Persisted(p.token()));
    return effects;
  }

  @Test
  void everyFixedMemberCanReplicateCommitAndReusePeerCorrelationsAfterStepDown() {
    for (int id = 0; id < 3; id++) {
      Raft node = new Raft(id, 1, Durable.empty());
      Set<Integer> peers = new HashSet<>(Set.of(0, 1, 2));
      peers.remove(id);
      List<Effect> election = stable(node, new Tick(1000));
      assertEquals(
          peers,
          election.stream()
              .map(Send.class::cast)
              .map(Send::peer)
              .collect(java.util.stream.Collectors.toSet()));
      Send request = (Send) election.getFirst();
      List<Effect> replication =
          stable(
              node,
              new Receive(request.peer(), new VoteResponse(1, request.rpc().request(), true)));
      assertEquals(Role.LEADER, node.role());
      assertEquals(
          peers,
          replication.stream()
              .map(Send.class::cast)
              .map(Send::peer)
              .collect(java.util.stream.Collectors.toSet()));
      for (Effect effect : replication) {
        Send send = (Send) effect;
        AppendRequest append = (AppendRequest) send.rpc();
        stable(
            node,
            new Receive(
                send.peer(),
                new AppendResponse(
                    1,
                    append.request(),
                    true,
                    append.previousIndex() + append.entries().size(),
                    0)));
      }
      assertEquals(1, node.commit());
      assertEquals(1, node.applied());
      stable(node, new Receive(request.peer(), new VoteRequest(2, 99, request.peer(), 1, 1)));
      assertEquals(Role.FOLLOWER, node.role());
      Send reelection = (Send) stable(node, new Tick(3000)).getFirst();
      List<Effect> renewed =
          stable(
              node,
              new Receive(
                  reelection.peer(), new VoteResponse(3, reelection.rpc().request(), true)));
      assertEquals(Role.LEADER, node.role());
      assertEquals(
          peers,
          renewed.stream()
              .map(Send.class::cast)
              .map(Send::peer)
              .collect(java.util.stream.Collectors.toSet()));
    }
  }

  @Test
  void memberIdsOutsideFixedConfigurationAreRejected() {
    for (int id : new int[] {-1, 3, Integer.MIN_VALUE, Integer.MAX_VALUE})
      assertThrows(IllegalArgumentException.class, () -> new Raft(id, 1, Durable.empty()));
  }

  @Test
  void voteWaitsForFlushAndRestrictsLog() {
    Raft n = new Raft(0, 1, Durable.empty());
    List<Effect> e = n.accept(new Receive(1, new VoteRequest(1, 9, 1, 0, 0)));
    assertInstanceOf(Persist.class, e.getFirst());
    assertFalse(e.stream().anyMatch(Send.class::isInstance));
    assertTrue(
        ((VoteResponse)
                ((Send) n.accept(new Persisted(((Persist) e.getFirst()).token())).getFirst()).rpc())
            .granted());
    List<Effect> no = stable(n, new Receive(2, new VoteRequest(1, 10, 2, 0, 0)));
    assertFalse(((VoteResponse) ((Send) no.getFirst()).rpc()).granted());
  }

  @Test
  void heartbeatNeverTruncatesAndConflictingSuffixWaitsForDisk() {
    var e1 = new Entry(1, 1, null);
    var e2 = new Entry(2, 1, null);
    Raft n = new Raft(0, 1, new Durable(2, -1, 0, Snapshot.empty(), List.of(e1, e2)));
    stable(n, new Receive(1, new AppendRequest(2, 1, 1, 0, 0, List.of(), 0, 0)));
    assertEquals(2, n.durable().log().size());
    List<Effect> effects =
        n.accept(
            new Receive(1, new AppendRequest(2, 2, 1, 1, 1, List.of(new Entry(2, 2, null)), 0, 0)));
    assertInstanceOf(Persist.class, effects.getFirst());
    assertEquals(2, n.durable().log().get(1).term());
  }

  @Test
  void oldTermEntriesCannotCommitWithoutCurrentTermMarker() {
    Raft n =
        new Raft(0, 1, new Durable(1, -1, 0, Snapshot.empty(), List.of(new Entry(1, 1, null))));
    var election = stable(n, new Tick(1000));
    Send vote = (Send) election.getFirst();
    var messages =
        stable(n, new Receive(vote.peer(), new VoteResponse(2, vote.rpc().request(), true)));
    assertEquals(Role.LEADER, n.role());
    assertEquals(0, n.commit());
    AppendRequest append = (AppendRequest) ((Send) messages.getFirst()).rpc();
    stable(n, new Receive(1, new AppendResponse(2, append.request(), true, 1, 0)));
    assertEquals(0, n.commit(), "old-term majority coordinate must not advance commit");
    var heartbeat = stable(n, new Tick(1100));
    Send send = (Send) heartbeat.getFirst();
    AppendRequest marker = (AppendRequest) send.rpc();
    stable(
        n,
        new Receive(
            send.peer(),
            new AppendResponse(
                2, marker.request(), true, marker.previousIndex() + marker.entries().size(), 0)));
    assertEquals(2, n.commit());
  }

  @Test
  void delayedRejectionCannotUndoSuccessfulProgress() {
    Raft n = new Raft(0, 1, Durable.empty());
    Send vote = (Send) stable(n, new Tick(1000)).getFirst();
    Send replication =
        (Send)
            stable(n, new Receive(vote.peer(), new VoteResponse(1, vote.rpc().request(), true)))
                .getFirst();
    AppendRequest first = (AppendRequest) replication.rpc();
    stable(n, new Receive(replication.peer(), new AppendResponse(1, first.request(), true, 1, 0)));
    long before = n.commit();
    stable(n, new Receive(replication.peer(), new AppendResponse(1, first.request(), false, 0, 0)));
    assertEquals(before, n.commit());
  }

  @Test
  void sessionsCasAndBounds() {
    Registers kv = new Registers(Snapshot.empty());
    Key key = new Key(new byte[] {1});
    Command put = new Command("c", 1, Kind.PUT, key, new byte[] {7}, null);
    assertEquals("OK", kv.apply(put).code());
    assertEquals("OK", kv.apply(put).code());
    assertEquals(
        "IDENTITY_CONFLICT", kv.apply(new Command("c", 1, Kind.DELETE, key, null, null)).code());
    assertEquals(
        "SEQUENCE_GAP", kv.apply(new Command("c", 3, Kind.DELETE, key, null, null)).code());
    Result failed = kv.apply(new Command("c", 2, Kind.CAS, key, new byte[] {8}, new byte[] {9}));
    assertEquals("CAS_MISMATCH", failed.code());
    assertArrayEquals(new byte[] {7}, failed.value());
    Registers recovered = new Registers(kv.snapshot(2, 1));
    assertEquals(
        failed,
        recovered.apply(new Command("c", 2, Kind.CAS, key, new byte[] {8}, new byte[] {9})));
    assertEquals("STALE_SEQUENCE", recovered.apply(put).code());
    assertThrows(IllegalArgumentException.class, () -> new Key(new byte[257]));
  }

  @Test
  void persistenceBarrierDrainsAllDeferredEventsInOrder() {
    Raft n = new Raft(0, 1, Durable.empty());
    Persist p = (Persist) n.accept(new Receive(1, new VoteRequest(1, 11, 1, 0, 0))).getFirst();
    n.accept(new Read(101, new Key(new byte[] {1})));
    n.accept(new Read(102, new Key(new byte[] {2})));
    n.accept(new Tick(10));
    List<Effect> released = n.accept(new Persisted(p.token()));
    assertEquals(
        List.of(101L, 102L),
        released.stream()
            .filter(Reply.class::isInstance)
            .map(Reply.class::cast)
            .map(Reply::request)
            .toList());
    assertFalse(n.waitingForDisk());
  }

  @Test
  void commitApplicationSurvivesCrashOnlyAfterDiskAck() {
    Raft n = new Raft(0, 1, Durable.empty());
    List<Effect> effect =
        n.accept(
            new Receive(1, new AppendRequest(1, 2, 1, 0, 0, List.of(new Entry(1, 1, null)), 1, 0)));
    assertEquals(0, n.applied());
    Persist p = (Persist) effect.getFirst();
    n.accept(new Persisted(p.token()));
    assertEquals(1, n.applied());
    assertEquals(1, new Raft(0, 2, p.durable()).applied());
  }

  @Test
  void queueLimitRejectsMutationExplicitly() {
    Raft n = new Raft(0, 1, Durable.empty());
    n.accept(new Receive(1, new VoteRequest(1, 1, 1, 0, 0)));
    for (int i = 0; i < MAX_PENDING; i++) n.accept(new Read(i, new Key(new byte[] {1})));
    var result =
        n.accept(
            new Mutate(999, new Command("c", 1, Kind.DELETE, new Key(new byte[] {1}), null, null)));
    assertEquals("RESOURCE_EXHAUSTED", ((Reply) result.getFirst()).result().code());
  }

  @Test
  void rejectedVoteDoesNotPostponeElectionForever() {
    Raft n =
        new Raft(0, 1, new Durable(1, -1, 0, Snapshot.empty(), List.of(new Entry(1, 1, null))));
    boolean elected = false;
    for (int time = 100; time <= 600; time += 100) {
      stable(n, new Receive(1, new VoteRequest(time / 100 + 1, time, 1, 0, 0)));
      stable(n, new Tick(time));
      if (n.role() == Role.CANDIDATE) {
        elected = true;
        break;
      }
    }
    assertTrue(elected);
  }

  @Test
  void chunkedSnapshotRejectsIncompleteMismatchedAndCorruptTransfers() throws Exception {
    Registers registers = new Registers(Snapshot.empty());
    for (int i = 1; i <= 6; i++)
      registers.apply(
          new Command("snap", i, Kind.PUT, new Key(new byte[] {(byte) i}), new byte[4096], null));
    byte[] bytes = Codec.snapshot(registers.snapshot(7, 2));
    Raft follower = new Raft(0, 1, Durable.empty());
    stable(
        follower,
        new Receive(
            1,
            new SnapshotRequest(
                3, 1, 1, 7, 2, 0, bytes.length, Arrays.copyOfRange(bytes, 0, 16384))));
    assertEquals(0, follower.applied());
    assertEquals(
        0,
        ((SnapshotResponse)
                ((Send)
                        stable(
                                follower,
                                new Receive(
                                    1,
                                    new SnapshotRequest(
                                        3, 2, 1, 7, 2, 100, bytes.length, new byte[] {1})))
                            .getFirst())
                    .rpc())
            .nextOffset());
    List<Effect> effects =
        follower.accept(
            new Receive(
                1,
                new SnapshotRequest(
                    3,
                    3,
                    1,
                    7,
                    2,
                    16384,
                    bytes.length,
                    Arrays.copyOfRange(bytes, 16384, bytes.length))));
    assertInstanceOf(Persist.class, effects.getFirst());
    assertEquals(0, follower.applied());
    Persist p = (Persist) effects.getFirst();
    follower.accept(new Persisted(p.token()));
    assertEquals(7, follower.applied());
    assertEquals(6, follower.view().data().size());
    Raft other = new Raft(2, 1, Durable.empty());
    byte[] corrupt = bytes.clone();
    corrupt[corrupt.length - 1] ^= 1;
    stable(other, new Receive(1, new SnapshotRequest(3, 4, 1, 7, 2, 0, corrupt.length, corrupt)));
    assertEquals(0, other.applied());
    Raft mismatched = new Raft(2, 2, Durable.empty());
    stable(mismatched, new Receive(1, new SnapshotRequest(3, 5, 1, 8, 2, 0, bytes.length, bytes)));
    assertEquals(0, mismatched.applied());
  }

  @Test
  void impossibleSnapshotCoordinatesDoNotActivateStateOrPreventSubsequentRecovery() {
    Registers registers = new Registers(Snapshot.empty());
    Key key = new Key(new byte[] {1});
    registers.apply(new Command("snapshot-client", 1, Kind.PUT, key, new byte[] {7}, null));
    byte[] futureTerm = Codec.snapshot(registers.snapshot(7, 4));
    Raft follower = new Raft(0, 1, Durable.empty());
    for (long[] coordinates : List.of(new long[] {7, 4}, new long[] {-1, 2}, new long[] {7, 0})) {
      List<Effect> effects =
          stable(
              follower,
              new Receive(
                  1,
                  new SnapshotRequest(
                      3, 1, 1, coordinates[0], coordinates[1], 0, futureTerm.length, futureTerm)));
      assertTrue(effects.stream().noneMatch(e -> e instanceof Send));
      assertEquals(0, follower.applied());
      assertEquals(0, follower.durable().snapshot().index());
    }
    byte[] valid = Codec.snapshot(registers.snapshot(7, 2));
    stable(follower, new Receive(1, new SnapshotRequest(3, 2, 1, 7, 2, 0, valid.length, valid)));
    assertEquals(7, follower.applied());
    assertEquals(2, follower.durable().snapshot().term());
    assertArrayEquals(new byte[] {7}, follower.view().data().get(key));
  }

  @Test
  void compactionRestartsChunkTransferAtZeroForNewSnapshot() {
    Registers registers = new Registers(Snapshot.empty());
    for (int i = 1; i <= 6; i++)
      registers.apply(
          new Command("big", i, Kind.PUT, new Key(new byte[] {(byte) i}), new byte[4096], null));
    Raft n = new Raft(0, 1, new Durable(1, -1, 1, registers.snapshot(1, 1), List.of()));
    Send vote = (Send) stable(n, new Tick(1000)).getFirst();
    List<Effect> election =
        stable(n, new Receive(vote.peer(), new VoteResponse(2, vote.rpc().request(), true)));
    Send peer1 =
        election.stream()
            .filter(Send.class::isInstance)
            .map(Send.class::cast)
            .filter(e -> e.peer() == 1)
            .findFirst()
            .orElseThrow();
    Send peer2 =
        election.stream()
            .filter(Send.class::isInstance)
            .map(Send.class::cast)
            .filter(e -> e.peer() == 2)
            .findFirst()
            .orElseThrow();
    List<Effect> first =
        stable(n, new Receive(1, new AppendResponse(2, peer1.rpc().request(), false, 0, 0)));
    SnapshotRequest chunk = (SnapshotRequest) ((Send) first.getFirst()).rpc();
    List<Effect> second =
        stable(
            n,
            new Receive(
                1, new SnapshotResponse(2, chunk.request(), 1, chunk.chunk().length, false)));
    SnapshotRequest secondChunk = (SnapshotRequest) ((Send) second.getFirst()).rpc();
    assertEquals(16384, secondChunk.offset());
    stable(n, new Receive(2, new AppendResponse(2, peer2.rpc().request(), true, 2, 0)));
    stable(n, new TakeSnapshot());
    List<Effect> later = stable(n, new Tick(1100));
    SnapshotRequest restarted =
        (SnapshotRequest)
            later.stream()
                .filter(Send.class::isInstance)
                .map(Send.class::cast)
                .filter(e -> e.peer() == 1)
                .findFirst()
                .orElseThrow()
                .rpc();
    assertEquals(2, restarted.index());
    assertEquals(0, restarted.offset());
    assertTrue(
        stable(
                n,
                new Receive(
                    1,
                    new SnapshotResponse(
                        2,
                        secondChunk.request(),
                        1,
                        secondChunk.offset() + secondChunk.chunk().length,
                        false)))
            .isEmpty());
  }

  @Test
  void interruptedSnapshotFollowerRequestsRestartAndLeaderAcceptsAlreadyInstalledAck() {
    Registers data = new Registers(Snapshot.empty());
    for (int i = 1; i <= 6; i++)
      data.apply(
          new Command("large", i, Kind.PUT, new Key(new byte[] {(byte) i}), new byte[4096], null));
    Snapshot snapshot = data.snapshot(1, 1);
    byte[] bytes = Codec.snapshot(snapshot);
    Raft follower = new Raft(1, 1, Durable.empty());
    stable(
        follower,
        new Receive(
            0,
            new SnapshotRequest(
                2, 1, 0, 1, 1, 0, bytes.length, Arrays.copyOfRange(bytes, 0, 16384))));
    follower = new Raft(1, 2, follower.durable());
    List<Effect> reset =
        stable(
            follower,
            new Receive(
                0,
                new SnapshotRequest(
                    2,
                    2,
                    0,
                    1,
                    1,
                    16384,
                    bytes.length,
                    Arrays.copyOfRange(bytes, 16384, bytes.length))));
    assertEquals(0, ((SnapshotResponse) ((Send) reset.getFirst()).rpc()).nextOffset());
    stable(
        follower,
        new Receive(
            0,
            new SnapshotRequest(
                2, 3, 0, 1, 1, 0, bytes.length, Arrays.copyOfRange(bytes, 0, 16384))));
    stable(
        follower,
        new Receive(
            0,
            new SnapshotRequest(
                2,
                4,
                0,
                1,
                1,
                16384,
                bytes.length,
                Arrays.copyOfRange(bytes, 16384, bytes.length))));
    assertEquals(1, follower.applied());
    Raft leader = new Raft(0, 1, new Durable(1, -1, 1, snapshot, List.of()));
    Send vote = (Send) stable(leader, new Tick(1000)).getFirst();
    List<Effect> elected =
        stable(leader, new Receive(vote.peer(), new VoteResponse(2, vote.rpc().request(), true)));
    Send append =
        elected.stream()
            .filter(Send.class::isInstance)
            .map(Send.class::cast)
            .filter(e -> e.peer() == 1)
            .findFirst()
            .orElseThrow();
    Send first =
        (Send)
            stable(
                    leader,
                    new Receive(1, new AppendResponse(2, append.rpc().request(), false, 0, 0)))
                .getFirst();
    SnapshotRequest chunk = (SnapshotRequest) first.rpc();
    Send second =
        (Send)
            stable(
                    leader,
                    new Receive(1, new SnapshotResponse(2, chunk.request(), 1, 16384, false)))
                .getFirst();
    SnapshotRequest partial = (SnapshotRequest) second.rpc();
    Send restarted =
        (Send)
            stable(leader, new Receive(1, new SnapshotResponse(2, partial.request(), 1, 0, false)))
                .getFirst();
    SnapshotRequest restart = (SnapshotRequest) restarted.rpc();
    assertEquals(0, restart.offset());
    List<Effect> done =
        stable(
            leader,
            new Receive(1, new SnapshotResponse(2, restart.request(), 1, restart.total(), true)));
    assertInstanceOf(AppendRequest.class, ((Send) done.getFirst()).rpc());
  }

  @Test
  void actualOldTermMajorityCannotCommitWithoutCurrentTermMarker() {
    List<Entry> prior = new ArrayList<>();
    for (int i = 1; i <= 40; i++) prior.add(new Entry(i, 1, null));
    Raft n = new Raft(0, 1, new Durable(1, -1, 0, Snapshot.empty(), prior));
    Send vote = (Send) stable(n, new Tick(1000)).getFirst();
    List<Effect> elected =
        stable(n, new Receive(vote.peer(), new VoteResponse(2, vote.rpc().request(), true)));
    Send replication =
        elected.stream()
            .filter(Send.class::isInstance)
            .map(Send.class::cast)
            .filter(e -> e.peer() == 1)
            .findFirst()
            .orElseThrow();
    AppendRequest message = (AppendRequest) replication.rpc();
    while (message.previousIndex() > 0) {
      List<Effect> repair =
          stable(n, new Receive(1, new AppendResponse(2, message.request(), false, 0, 0)));
      message = (AppendRequest) ((Send) repair.getFirst()).rpc();
    }
    assertEquals(32, message.entries().size());
    List<Effect> afterOldMajority =
        stable(n, new Receive(1, new AppendResponse(2, message.request(), true, 32, 0)));
    assertEquals(
        0, n.commit(), "a majority now stores32 prior-term entries, but marker41 is absent");
    AppendRequest marker = (AppendRequest) ((Send) afterOldMajority.getFirst()).rpc();
    assertEquals(41, marker.entries().getLast().index());
    stable(n, new Receive(1, new AppendResponse(2, marker.request(), true, 41, 0)));
    assertEquals(41, n.commit());
  }
}
