package io.github.cheshmakzanoon.portfolio.thepit;

import static io.github.cheshmakzanoon.portfolio.thepit.Model.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The single parser for command-log frames, shared by writer recovery and offline inspection.
 *
 * <p>Scanning never locks, truncates, forces or writes. Inspection therefore cannot alter a journal
 * that a live writer owns; a frame that writer is still appending simply appears as an incomplete
 * tail.
 */
public final class JournalReader {
  private static final ObjectMapper JSON = CommandJson.create();

  private JournalReader() {}

  /**
   * Result of scanning a journal.
   *
   * @param runId run identity recorded in the frames, or {@code null} for an empty journal
   * @param entries every complete, checksummed and validated record in sequence order
   * @param validBytes length of the complete-record prefix
   * @param fileBytes length of the file when scanning finished
   */
  public record Scan(String runId, List<Journal.Entry> entries, long validBytes, long fileBytes) {
    public Scan {
      entries = List.copyOf(entries);
    }

    /** Bytes of an incomplete final frame, which writer recovery would truncate. */
    public long incompleteTailBytes() {
      return fileBytes - validBytes;
    }
  }

  /** Reads a journal without opening it for writing or taking its lock. */
  public static Scan inspect(Path path) throws IOException {
    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
      return scan(channel, null);
    }
  }

  /**
   * Scans from the start of {@code channel}. When {@code expectedRunId} is null the first frame
   * defines the run and every later frame must agree.
   */
  static Scan scan(FileChannel channel, String expectedRunId) throws IOException {
    List<Journal.Entry> entries = new ArrayList<>();
    String run = expectedRunId;
    long position = 0, size = channel.size(), validBytes = size;
    while (position < size) {
      if (entries.size() >= Journal.MAX_ENTRIES) throw new IOException("journal run cap exceeded");
      long start = position;
      if (size - position < Journal.HEADER) {
        validBytes = start;
        break;
      }
      ByteBuffer h = ByteBuffer.allocate(Journal.HEADER);
      readFully(channel, h, position);
      byte[] hb = h.array();
      h.flip();
      int magic = h.getInt(), version = h.getInt();
      long sequence = h.getLong();
      int length = h.getInt(), checksum = h.getInt();
      if (magic != Journal.MAGIC
          || version != Journal.VERSION
          || checksum != Journal.crc(hb, 20)
          || length < 1
          || length > Journal.MAX_PAYLOAD)
        throw new IOException("corrupt frame header at " + start);
      if (sequence != entries.size() + 1L) throw new IOException("journal sequence gap");
      if (size - position < Journal.HEADER + (long) length + 4) {
        validBytes = start;
        break;
      }
      ByteBuffer data = ByteBuffer.allocate(length + 4);
      readFully(channel, data, position + Journal.HEADER);
      data.flip();
      byte[] payload = new byte[length];
      data.get(payload);
      if (data.getInt() != Journal.crc(payload, length))
        throw new IOException("corrupt payload at " + start);
      Journal.Payload p = JSON.readValue(payload, Journal.Payload.class);
      if (p == null || p.runId() == null || (run != null && !run.equals(p.runId())))
        throw new IOException("invalid payload run ID");
      if (run == null) {
        if (!identifier(p.runId())) throw new IOException("invalid payload run ID");
        run = p.runId();
      }
      try {
        p.command().validate();
      } catch (RuntimeException e) {
        throw new IOException("invalid durable command", e);
      }
      entries.add(new Journal.Entry(sequence, p.command()));
      position += Journal.HEADER + (long) length + 4;
    }
    return new Scan(run, entries, validBytes, size);
  }

  private static void readFully(FileChannel channel, ByteBuffer b, long pos) throws IOException {
    while (b.hasRemaining()) {
      int n = channel.read(b, pos + b.position());
      if (n < 0) throw new IOException("unexpected EOF");
    }
  }
}
