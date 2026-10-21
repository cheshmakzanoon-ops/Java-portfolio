package io.github.cheshmakzanoon.portfolio.majority;

import static io.github.cheshmakzanoon.portfolio.majority.Model.*;

import java.io.IOException;
import java.util.Arrays;

/**
 * One bounded incoming transfer. Duplicate chunks acknowledge bytes without restarting assembly.
 */
final class SnapshotAssembler {
  record Progress(int nextOffset, Snapshot completed) {}

  private byte[] content;
  private long index, term;
  private int written, lastOffset = -1, lastLength;

  Progress accept(SnapshotRequest request) throws IOException {
    byte[] chunk = request.chunk();
    if (request.total() < 1
        || request.total() > MAX_SNAPSHOT
        || request.offset() < 0
        || chunk.length < 1
        || chunk.length > 16384
        || request.offset() > request.total() - chunk.length)
      throw new IOException("SNAPSHOT_CHUNK_BOUNDS");
    boolean same =
        content != null
            && index == request.index()
            && term == request.snapshotTerm()
            && content.length == request.total();
    if (!same && request.offset() == 0) {
      content = new byte[request.total()];
      index = request.index();
      term = request.snapshotTerm();
      written = 0;
      lastOffset = -1;
      same = true;
    }
    int end = request.offset() + chunk.length;
    if (!same || request.offset() > written || request.offset() < written && end > written)
      return new Progress(0, null);
    if (request.offset() < written) {
      if (request.offset() != lastOffset || chunk.length != lastLength)
        return new Progress(0, null);
      if (!Arrays.equals(content, request.offset(), end, chunk, 0, chunk.length))
        throw new IOException("SNAPSHOT_CHUNK_CONFLICT");
      return new Progress(end, null);
    }
    System.arraycopy(chunk, 0, content, written, chunk.length);
    lastOffset = request.offset();
    lastLength = chunk.length;
    written = end;
    if (written < content.length) return new Progress(written, null);
    Snapshot snapshot = Codec.snapshot(content);
    if (snapshot.index() != index || snapshot.term() != term)
      throw new IOException("SNAPSHOT_COORDINATE_MISMATCH");
    clear();
    return new Progress(end, snapshot);
  }

  void clear() {
    content = null;
    written = 0;
  }
}
