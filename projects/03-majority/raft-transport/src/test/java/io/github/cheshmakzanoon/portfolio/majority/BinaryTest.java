package io.github.cheshmakzanoon.portfolio.majority;

import static io.github.cheshmakzanoon.portfolio.majority.Binary.*;
import static io.github.cheshmakzanoon.portfolio.majority.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class BinaryTest {
  @Test
  void boundedBeforeAllocationAndBadClusterRejected() {
    EmbeddedChannel channel =
        new EmbeddedChannel(new LengthFieldBasedFrameDecoder(MAX_FRAME + 4, 0, 4, 0, 4));
    assertThrows(
        Exception.class, () -> channel.writeInbound(Unpooled.buffer().writeInt(Integer.MAX_VALUE)));
    channel.finishAndReleaseAll();
    byte[] bytes = encode(new Envelope(3, 0, "secret", 1, new Get(new Key(new byte[] {1}))));
    bytes[10] ^= 1;
    assertThrows(IOException.class, () -> decode(bytes));
    assertThrows(IOException.class, () -> decode(new byte[MAX_FRAME + 1]));
  }

  @Test
  void everyRpcRoundTripAndMalformedPayload() {
    List<Rpc> messages =
        List.of(
            new VoteRequest(1, 1, 0, 3, 1),
            new VoteResponse(1, 2, true),
            new AppendRequest(
                2,
                3,
                0,
                0,
                0,
                List.of(
                    new Entry(
                        1,
                        2,
                        new Command(
                            "a", 1, Kind.PUT, new Key(new byte[] {3}), new byte[] {1}, null))),
                1,
                8),
            new AppendResponse(2, 3, true, 1, 8),
            new SnapshotRequest(2, 4, 0, 1, 2, 0, 4, new byte[] {1, 2, 3, 4}),
            new SnapshotResponse(2, 4, 1, 4, true));
    for (Rpc rpc : messages) {
      byte[] original = encode(new Envelope(0, 1, "key", 3, new Peer(rpc)));
      Envelope decoded;
      try {
        decoded = decode(original);
      } catch (IOException e) {
        throw new AssertionError(e);
      }
      assertArrayEquals(original, encode(decoded));
    }
    assertThrows(IOException.class, () -> decode(new byte[3]));
  }

  @Test
  void roleSettingsDoNotChangeWhenCallerMutatesItsProperties() {
    Properties properties = new Properties();
    properties.setProperty("peer.0", "initial");
    var settings = new TlsTransport.Settings(Path.of("unused"), 18300, properties);
    properties.setProperty("peer.0", "changed");
    settings.properties().setProperty("peer.0", "exposed");
    assertEquals("initial", settings.token(0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TlsTransport.Settings(Path.of("unused"), 65534, properties));
  }

  @Test
  void memberAndClientIdentitiesCannotCrossRoles() {
    Properties p = new Properties();
    p.setProperty("peer.0", "a");
    p.setProperty("peer.1", "b");
    p.setProperty("peer.2", "c");
    p.setProperty("client", "d");
    p.setProperty("admin", "e");
    var s = new TlsTransport.Settings(Path.of("unused"), 18300, p);
    assertTrue(
        TlsTransport.authorized(
            new Envelope(0, 1, "a", 0, new Peer(new VoteRequest(1, 1, 0, 0, 0))), 1, s));
    assertFalse(
        TlsTransport.authorized(new Envelope(0, 1, "a", 0, new Admin("partition", 2)), 1, s));
    assertFalse(
        TlsTransport.authorized(
            new Envelope(0, 1, "wrong", 0, new Peer(new VoteRequest(1, 1, 0, 0, 0))), 1, s));
    assertFalse(
        TlsTransport.authorized(new Envelope(3, 1, "d", 0, new Admin("partition", 2)), 1, s));
    assertFalse(
        TlsTransport.authorized(
            new Envelope(3, 1, "d", 0, new Get(new Key(new byte[] {1}))), 2, s));
  }
}
