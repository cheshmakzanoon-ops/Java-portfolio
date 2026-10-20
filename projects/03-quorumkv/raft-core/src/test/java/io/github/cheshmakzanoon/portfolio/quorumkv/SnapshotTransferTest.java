package io.github.cheshmakzanoon.portfolio.quorumkv;

import static io.github.cheshmakzanoon.portfolio.quorumkv.Model.*;
import static io.github.cheshmakzanoon.portfolio.quorumkv.Raft.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class SnapshotTransferTest {
  private List<Effect> stable(Raft node, Event event) {
    List<Effect> effects = node.accept(event);
    if (effects.size() == 1 && effects.getFirst() instanceof Persist persist)
      return node.accept(new Persisted(persist.token()));
    return effects;
  }

  private Rpc to(List<Effect> effects, int peer) {
    return effects.stream()
        .filter(Send.class::isInstance)
        .map(Send.class::cast)
        .filter(send -> send.peer() == peer)
        .findFirst()
        .orElseThrow()
        .rpc();
  }

  @Test
  void snapshotProgressSurvivesRepliesSlowerThanHeartbeatAndLostChunkAcknowledgments() {
    Registers registers = new Registers(Snapshot.empty());
    for (int i = 1; i <= 10; i++)
      registers.apply(
          new Command(
              "snapshot", i, Kind.PUT, new Key(new byte[] {(byte) i}), new byte[4096], null));
    Snapshot snapshot = registers.snapshot(10, 1);
    Raft leader = new Raft(0, 1, new Durable(1, -1, 10, snapshot, List.of()));
    Raft follower = new Raft(1, 1, Durable.empty());
    VoteRequest election = (VoteRequest) to(stable(leader, new Tick(1000)), 1);
    AppendRequest append =
        (AppendRequest)
            to(stable(leader, new Receive(1, new VoteResponse(2, election.request(), true))), 1);
    AppendResponse rejected = (AppendResponse) to(stable(follower, new Receive(0, append)), 0);
    SnapshotRequest chunk = (SnapshotRequest) to(stable(leader, new Receive(1, rejected)), 1);
    int chunks = 0;
    long now = 1000;
    while (true) {
      // Each reply takes three heartbeat periods. Retries must retain the outstanding correlation.
      for (int delay = 0; delay < 3; delay++) {
        SnapshotRequest retry = (SnapshotRequest) to(stable(leader, new Tick(now += 50)), 1);
        assertEquals(chunk.request(), retry.request());
        assertEquals(chunk.offset(), retry.offset());
      }
      SnapshotResponse lost = (SnapshotResponse) to(stable(follower, new Receive(0, chunk)), 0);
      SnapshotResponse repeated = (SnapshotResponse) to(stable(follower, new Receive(0, chunk)), 0);
      assertEquals(lost.nextOffset(), repeated.nextOffset());
      assertEquals(lost.done(), repeated.done());
      chunks++;
      List<Effect> progress = stable(leader, new Receive(1, repeated));
      if (repeated.done()) {
        append = (AppendRequest) to(progress, 1);
        break;
      }
      assertEquals(0, follower.applied(), "partial snapshot state must not become active");
      chunk = (SnapshotRequest) to(progress, 1);
      assertEquals(repeated.nextOffset(), chunk.offset());
    }
    assertTrue(chunks >= 3);
    assertEquals(10, follower.applied());
    AppendResponse accepted = (AppendResponse) to(stable(follower, new Receive(0, append)), 0);
    stable(leader, new Receive(1, accepted));
    assertEquals(11, leader.commit(), "recovered follower must rejoin current-term commitment");
    assertEquals(snapshot.data().keySet(), follower.view().data().keySet());
  }

  @Test
  void repeatedMiddleChunkIsIdempotentAndConflictingBytesNeverActivate() throws Exception {
    Registers registers = new Registers(Snapshot.empty());
    for (int i = 1; i <= 10; i++)
      registers.apply(
          new Command("large", i, Kind.PUT, new Key(new byte[] {(byte) i}), new byte[4096], null));
    byte[] bytes = Codec.snapshot(registers.snapshot(10, 1));
    SnapshotAssembler assembler = new SnapshotAssembler();
    for (int offset = 0; offset < bytes.length; offset += 16384) {
      byte[] payload = Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + 16384));
      SnapshotRequest request =
          new SnapshotRequest(2, offset + 1, 0, 10, 1, offset, bytes.length, payload);
      var progress = assembler.accept(request);
      if (progress.completed() != null) {
        assertEquals(10, progress.completed().index());
        assertEquals(10, progress.completed().data().size());
      } else {
        assertEquals(progress.nextOffset(), assembler.accept(request).nextOffset());
        byte[] conflicting = payload.clone();
        conflicting[0] ^= 1;
        assertThrows(
            java.io.IOException.class,
            () ->
                assembler.accept(
                    new SnapshotRequest(
                        2, 9, 0, 10, 1, request.offset(), bytes.length, conflicting)));
      }
    }
  }
}
