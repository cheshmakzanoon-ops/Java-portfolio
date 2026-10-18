package io.github.cheshmakzanoon.portfolio.quorumkv;

import static io.github.cheshmakzanoon.portfolio.quorumkv.Model.*;

import java.io.*;
import java.util.*;

/** Versioned bounded wire grammar, authenticated inside TLS before dispatch. */
public final class Binary {
  public static final int MAX_FRAME = 512 * 1024;

  public sealed interface Body permits Peer, Mutation, Get, Admin, Response {}

  public record Peer(Rpc rpc) implements Body {}

  public record Mutation(Command command) implements Body {}

  public record Get(Key key) implements Body {}

  public record Admin(String action, int peer) implements Body {}

  public record Response(Result result) implements Body {}

  public record Envelope(int source, int destination, String token, long correlation, Body body) {}

  private Binary() {}

  public static byte[] encode(Envelope e) {
    byte[] b =
        Codec.encode(
            out -> {
              out.writeInt(0x514b5631);
              out.writeInt(1);
              out.writeUTF(CLUSTER);
              out.writeInt(e.source());
              out.writeInt(e.destination());
              out.writeUTF(e.token());
              out.writeLong(e.correlation());
              switch (e.body()) {
                case Peer p -> {
                  out.writeByte(1);
                  rpc(out, p.rpc());
                }
                case Mutation m -> {
                  out.writeByte(2);
                  Codec.writeCommand(out, m.command());
                }
                case Get g -> {
                  out.writeByte(3);
                  Codec.bytes(out, g.key().bytes());
                }
                case Admin a -> {
                  out.writeByte(4);
                  out.writeUTF(a.action());
                  out.writeInt(a.peer());
                }
                case Response r -> {
                  out.writeByte(5);
                  Codec.result(out, r.result());
                }
              }
            });
    if (b.length > MAX_FRAME) throw new IllegalArgumentException("FRAME_LIMIT");
    return b;
  }

  public static Envelope decode(byte[] bytes) throws IOException {
    if (bytes.length > MAX_FRAME) throw new IOException("FRAME_LIMIT");
    try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
      if (in.readInt() != 0x514b5631 || in.readInt() != 1) throw new IOException("WIRE_VERSION");
      if (!CLUSTER.equals(in.readUTF())) throw new IOException("CLUSTER");
      int source = in.readInt(), destination = in.readInt();
      String token = in.readUTF();
      if (token.length() > 128) throw new IOException("TOKEN_LIMIT");
      long correlation = in.readLong();
      Body body =
          switch (in.readUnsignedByte()) {
            case 1 -> new Peer(rpc(in));
            case 2 -> new Mutation(Objects.requireNonNull(Codec.readCommand(in)));
            case 3 -> new Get(new Key(Objects.requireNonNull(Codec.bytes(in, MAX_KEY))));
            case 4 -> {
              String action = in.readUTF();
              if (action.length() > 32) throw new IOException("ACTION_LIMIT");
              yield new Admin(action, in.readInt());
            }
            case 5 -> new Response(Codec.result(in));
            default -> throw new IOException("MESSAGE_TYPE");
          };
      if (in.available() != 0) throw new IOException("TRAILING_BYTES");
      return new Envelope(source, destination, token, correlation, body);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new IOException("INVALID_MESSAGE", e);
    }
  }

  private static void rpc(DataOutputStream out, Rpc rpc) throws IOException {
    out.writeByte(
        switch (rpc) {
          case VoteRequest ignored -> 1;
          case VoteResponse ignored -> 2;
          case AppendRequest ignored -> 3;
          case AppendResponse ignored -> 4;
          case SnapshotRequest ignored -> 5;
          case SnapshotResponse ignored -> 6;
        });
    out.writeLong(rpc.term());
    out.writeLong(rpc.request());
    switch (rpc) {
      case VoteRequest r -> {
        out.writeInt(r.candidate());
        out.writeLong(r.lastIndex());
        out.writeLong(r.lastTerm());
      }
      case VoteResponse r -> out.writeBoolean(r.granted());
      case AppendRequest r -> {
        out.writeInt(r.leader());
        out.writeLong(r.previousIndex());
        out.writeLong(r.previousTerm());
        out.writeInt(r.entries().size());
        for (Entry entry : r.entries()) Codec.entry(out, entry);
        out.writeLong(r.commit());
        out.writeLong(r.read());
      }
      case AppendResponse r -> {
        out.writeBoolean(r.success());
        out.writeLong(r.match());
        out.writeLong(r.read());
      }
      case SnapshotRequest r -> {
        out.writeInt(r.leader());
        out.writeLong(r.index());
        out.writeLong(r.snapshotTerm());
        out.writeInt(r.offset());
        out.writeInt(r.total());
        Codec.bytes(out, r.chunk());
      }
      case SnapshotResponse r -> {
        out.writeLong(r.index());
        out.writeInt(r.nextOffset());
        out.writeBoolean(r.done());
      }
    }
  }

  private static Rpc rpc(DataInputStream in) throws IOException {
    int tag = in.readUnsignedByte();
    long term = in.readLong(), request = in.readLong();
    if (term < 0) throw new IOException("TERM");
    return switch (tag) {
      case 1 -> new VoteRequest(term, request, in.readInt(), in.readLong(), in.readLong());
      case 2 -> new VoteResponse(term, request, in.readBoolean());
      case 3 -> {
        int leader = in.readInt();
        long previous = in.readLong(), previousTerm = in.readLong();
        int n = Codec.count(in, 32);
        var entries = new ArrayList<Entry>();
        for (int i = 0; i < n; i++) entries.add(Codec.entry(in));
        yield new AppendRequest(
            term, request, leader, previous, previousTerm, entries, in.readLong(), in.readLong());
      }
      case 4 -> new AppendResponse(term, request, in.readBoolean(), in.readLong(), in.readLong());
      case 5 ->
          new SnapshotRequest(
              term,
              request,
              in.readInt(),
              in.readLong(),
              in.readLong(),
              in.readInt(),
              in.readInt(),
              Objects.requireNonNull(Codec.bytes(in, 16384)));
      case 6 -> new SnapshotResponse(term, request, in.readLong(), in.readInt(), in.readBoolean());
      default -> throw new IOException("RPC_TYPE");
    };
  }
}
