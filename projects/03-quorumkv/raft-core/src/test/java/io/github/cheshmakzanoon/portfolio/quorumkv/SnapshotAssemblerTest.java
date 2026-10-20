package io.github.cheshmakzanoon.portfolio.quorumkv;

import static io.github.cheshmakzanoon.portfolio.quorumkv.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;

class SnapshotAssemblerTest {
  private SnapshotRequest request(int offset, int total, byte[] chunk) {
    return new SnapshotRequest(2, 1, 0, 1, 1, offset, total, chunk);
  }

  private byte[] snapshot() {
    Registers registers = new Registers(Snapshot.empty());
    registers.apply(
        new Command("client", 1, Kind.PUT, new Key(new byte[] {1}), new byte[] {42}, null));
    return Codec.snapshot(registers.snapshot(1, 1));
  }

  @Test
  void malformedSizesCannotAllocateOrAppendAndValidBoundaryChunksStillComplete() throws Exception {
    SnapshotAssembler assembler = new SnapshotAssembler();
    for (SnapshotRequest invalid :
        List.of(
            request(0, 0, new byte[] {1}),
            request(0, -1, new byte[] {1}),
            request(0, MAX_SNAPSHOT + 1, new byte[] {1}),
            request(-1, 10, new byte[] {1}),
            request(0, 10, new byte[0]),
            request(0, MAX_SNAPSHOT, new byte[16385]),
            request(Integer.MAX_VALUE, 10, new byte[] {1}),
            request(9, 10, new byte[] {1, 2})))
      assertThrows(IOException.class, () -> assembler.accept(invalid));
    byte[] bytes = snapshot();
    assertEquals(0, assembler.accept(request(5, bytes.length, new byte[] {1})).nextOffset());
    assertEquals(
        10,
        assembler.accept(request(0, bytes.length, Arrays.copyOfRange(bytes, 0, 10))).nextOffset());
    var completed =
        assembler.accept(request(10, bytes.length, Arrays.copyOfRange(bytes, 10, bytes.length)));
    assertEquals(bytes.length, completed.nextOffset());
    assertEquals(1, completed.completed().index());
    assertArrayEquals(new byte[] {42}, completed.completed().data().get(new Key(new byte[] {1})));
    assembler.clear();
    assertEquals(
        0,
        assembler
            .accept(request(10, bytes.length, Arrays.copyOfRange(bytes, 10, bytes.length)))
            .nextOffset());
  }

  @Test
  void changedTransferIdentityOrOverlappingRangeCannotMergePrefixes() throws Exception {
    byte[] bytes = snapshot();
    SnapshotAssembler assembler = new SnapshotAssembler();
    assembler.accept(request(0, bytes.length, Arrays.copyOfRange(bytes, 0, 10)));
    assertEquals(
        0,
        assembler
            .accept(
                new SnapshotRequest(
                    2, 2, 0, 2, 1, 10, bytes.length, Arrays.copyOfRange(bytes, 10, bytes.length)))
            .nextOffset());
    assertEquals(
        0,
        assembler
            .accept(
                new SnapshotRequest(
                    2, 2, 0, 1, 2, 10, bytes.length, Arrays.copyOfRange(bytes, 10, bytes.length)))
            .nextOffset());
    assertEquals(0, assembler.accept(request(10, bytes.length + 1, new byte[] {1})).nextOffset());
    assertEquals(0, assembler.accept(request(5, bytes.length, new byte[10])).nextOffset());
    assertEquals(0, assembler.accept(request(1, bytes.length, new byte[1])).nextOffset());
    assertNotNull(
        assembler
            .accept(request(10, bytes.length, Arrays.copyOfRange(bytes, 10, bytes.length)))
            .completed());
    byte[] corrupt = bytes.clone();
    corrupt[corrupt.length - 1] ^= 1;
    assertThrows(IOException.class, () -> assembler.accept(request(0, corrupt.length, corrupt)));
    assembler.clear();
    assertThrows(
        IOException.class,
        () -> assembler.accept(new SnapshotRequest(2, 1, 0, 2, 1, 0, bytes.length, bytes)));
    assembler.clear();
    assertNotNull(assembler.accept(request(0, bytes.length, bytes)).completed());
  }
}
