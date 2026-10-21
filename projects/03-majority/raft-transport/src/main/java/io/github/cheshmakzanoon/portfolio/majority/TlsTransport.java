package io.github.cheshmakzanoon.portfolio.majority;

import static io.github.cheshmakzanoon.portfolio.majority.Binary.*;
import static io.github.cheshmakzanoon.portfolio.majority.Model.*;

import io.netty.bootstrap.*;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.*;
import io.netty.handler.codec.*;
import io.netty.handler.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import javax.net.ssl.SSLParameters;

/** Loopback-only TLS transport with bounded frames, channels, and outbound watermarks. */
public final class TlsTransport implements AutoCloseable {
  public record Settings(Path directory, int basePort, Properties properties) {
    public Settings {
      Objects.requireNonNull(directory);
      if (basePort < 1 || basePort > 65533) throw new IllegalArgumentException("THREE_PORT_RANGE");
      properties = (Properties) properties.clone();
    }

    @Override
    public Properties properties() {
      return (Properties) properties.clone();
    }

    public static Settings load(Path directory, int basePort) throws IOException {
      var p = new Properties();
      try (var in = Files.newInputStream(directory.resolve("auth.properties"))) {
        p.load(in);
      }
      for (String key : List.of("peer.0", "peer.1", "peer.2", "client", "admin"))
        if (p.getProperty(key, "").length() < 32)
          throw new IOException("AUTH_CONFIGURATION: " + key);
      return new Settings(directory, basePort, p);
    }

    public String token(int source) {
      return properties.getProperty(
          source < 3 ? "peer." + source : source == 3 ? "client" : "admin");
    }
  }

  public interface Receiver {
    void receive(Envelope envelope, Consumer<Result> response);
  }

  private final int id;
  private final Settings settings;
  private final SslContext serverTls, clientTls;
  private final EventLoopGroup acceptors, workers;
  private final Map<Integer, Channel> peers = new ConcurrentHashMap<>();
  private final Set<Integer> connecting = ConcurrentHashMap.newKeySet();
  private final AtomicInteger open = new AtomicInteger();
  private final Receiver receiver;
  private Channel listener;

  public TlsTransport(int id, Settings settings, Receiver receiver) throws Exception {
    if (id < 0 || id > 2) throw new IllegalArgumentException("MEMBER");
    this.id = id;
    this.settings = settings;
    this.receiver = receiver;
    serverTls =
        SslContextBuilder.forServer(
                settings.directory.resolve("server.crt").toFile(),
                settings.directory.resolve("server.key").toFile())
            .sslProvider(SslProvider.JDK)
            .protocols("TLSv1.3")
            .build();
    clientTls = clientContext(settings);
    acceptors = new NioEventLoopGroup(1);
    workers = new NioEventLoopGroup(2);
  }

  public static SslContext clientContext(Settings settings) throws Exception {
    return SslContextBuilder.forClient()
        .sslProvider(SslProvider.JDK)
        .trustManager(settings.directory.resolve("server.crt").toFile())
        .protocols("TLSv1.3")
        .build();
  }

  public static SslHandler handler(SslContext context, Channel channel, int port) {
    SslHandler h = context.newHandler(channel.alloc(), "localhost", port);
    SSLParameters p = h.engine().getSSLParameters();
    p.setEndpointIdentificationAlgorithm("HTTPS");
    h.engine().setSSLParameters(p);
    h.setHandshakeTimeoutMillis(3000);
    return h;
  }

  public static void frames(ChannelPipeline pipeline) {
    pipeline.addLast(new LengthFieldBasedFrameDecoder(MAX_FRAME + 4, 0, 4, 0, 4));
    pipeline.addLast(new LengthFieldPrepender(4));
  }

  public void start() throws InterruptedException {
    listener =
        new ServerBootstrap()
            .group(acceptors, workers)
            .channel(NioServerSocketChannel.class)
            .childOption(
                ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(32768, 262144))
            .childHandler(
                new ChannelInitializer<SocketChannel>() {
                  protected void initChannel(SocketChannel ch) {
                    SslHandler handshake = serverTls.newHandler(ch.alloc());
                    handshake.setHandshakeTimeoutMillis(3000);
                    ch.pipeline().addLast(handshake);
                    frames(ch.pipeline());
                    ch.pipeline()
                        .addLast(
                            new SimpleChannelInboundHandler<ByteBuf>() {
                              private boolean counted, authenticated;
                              private ScheduledFuture<?> authenticationDeadline;

                              @Override
                              public void channelActive(ChannelHandlerContext ctx) {
                                counted = true;
                                if (open.incrementAndGet() > 64) ctx.close();
                                else
                                  authenticationDeadline =
                                      ctx.executor()
                                          .schedule(
                                              () -> {
                                                if (!authenticated) ctx.close();
                                              },
                                              3,
                                              TimeUnit.SECONDS);
                              }

                              @Override
                              public void channelInactive(ChannelHandlerContext ctx) {
                                if (counted) open.decrementAndGet();
                                if (authenticationDeadline != null)
                                  authenticationDeadline.cancel(false);
                              }

                              @Override
                              protected void channelRead0(ChannelHandlerContext ctx, ByteBuf frame)
                                  throws Exception {
                                byte[] data = new byte[frame.readableBytes()];
                                frame.readBytes(data);
                                Envelope e = decode(data);
                                if (!authorized(e, id, settings)) {
                                  ctx.close();
                                  return;
                                }
                                authenticated = true;
                                if (authenticationDeadline != null)
                                  authenticationDeadline.cancel(false);
                                receiver.receive(
                                    e,
                                    result -> {
                                      if (ctx.channel().isActive() && ctx.channel().isWritable())
                                        ctx.writeAndFlush(
                                            Unpooled.wrappedBuffer(
                                                encode(
                                                    new Envelope(
                                                        id,
                                                        e.source(),
                                                        settings.token(id),
                                                        e.correlation(),
                                                        new Response(result)))));
                                    });
                              }

                              @Override
                              public void exceptionCaught(
                                  ChannelHandlerContext ctx, Throwable error) {
                                ctx.close();
                              }
                            });
                  }
                })
            .bind(InetAddress.getLoopbackAddress(), settings.basePort + id)
            .sync()
            .channel();
  }

  public static boolean authorized(Envelope e, int destination, Settings s) {
    if (e.destination() != destination || e.source() < 0 || e.source() > 4) return false;
    if (!MessageDigest.isEqual(
        e.token().getBytes(java.nio.charset.StandardCharsets.UTF_8),
        s.token(e.source()).getBytes(java.nio.charset.StandardCharsets.UTF_8))) return false;
    return e.source() < 3
        ? e.body() instanceof Peer
        : e.source() == 3
            ? e.body() instanceof Mutation || e.body() instanceof Get
            : e.body() instanceof Admin;
  }

  public void send(int peer, Rpc rpc) {
    Channel channel = peers.get(peer);
    if (channel != null && channel.isActive()) {
      if (channel.isWritable())
        channel.writeAndFlush(
            Unpooled.wrappedBuffer(
                encode(new Envelope(id, peer, settings.token(id), rpc.request(), new Peer(rpc)))));
      return;
    }
    if (!connecting.add(peer)) return;
    new Bootstrap()
        .group(workers)
        .channel(NioSocketChannel.class)
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 500)
        .option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(32768, 262144))
        .handler(
            new ChannelInitializer<SocketChannel>() {
              protected void initChannel(SocketChannel ch) {
                ch.pipeline().addLast(handler(clientTls, ch, settings.basePort + peer));
                frames(ch.pipeline());
                ch.pipeline()
                    .addLast(
                        new SimpleChannelInboundHandler<ByteBuf>() {
                          protected void channelRead0(ChannelHandlerContext ctx, ByteBuf frame) {
                            /* Peer replies use authenticated reverse channels. */
                          }

                          @Override
                          public void exceptionCaught(ChannelHandlerContext ctx, Throwable error) {
                            ctx.close();
                          }
                        });
              }
            })
        .connect("localhost", settings.basePort + peer)
        .addListener(
            (ChannelFutureListener)
                future -> {
                  connecting.remove(peer);
                  if (future.isSuccess()) {
                    peers.put(peer, future.channel());
                    send(peer, rpc);
                  }
                });
  }

  @Override
  public void close() {
    if (listener != null) listener.close().awaitUninterruptibly();
    peers.values().forEach(c -> c.close().awaitUninterruptibly());
    acceptors.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly();
    workers.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly();
  }
}
