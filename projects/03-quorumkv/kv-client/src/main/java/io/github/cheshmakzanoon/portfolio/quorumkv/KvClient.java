package io.github.cheshmakzanoon.portfolio.quorumkv;

import static io.github.cheshmakzanoon.portfolio.quorumkv.Binary.*;
import static io.github.cheshmakzanoon.portfolio.quorumkv.Model.*;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** One outstanding mutation per identity; unknown outcomes retain the exact command. */
public final class KvClient implements AutoCloseable {
  private final TlsTransport.Settings settings;
  private final EventLoopGroup group = new NioEventLoopGroup(1);
  private final Map<Integer, Channel> channels = new HashMap<>();
  private final Map<Long, CompletableFuture<Result>> pending = new ConcurrentHashMap<>();
  private final AtomicLong correlation = new AtomicLong();
  private final MutationIdentity mutationIdentity;

  public KvClient(TlsTransport.Settings settings, String identity) {
    this.settings = settings;
    this.mutationIdentity = new MutationIdentity(identity);
  }

  public synchronized Result mutate(Kind kind, Key key, byte[] value, byte[] expected)
      throws Exception {
    Command requested = mutationIdentity.begin(kind, key, value, expected);
    Result result = retry(new Mutation(requested));
    mutationIdentity.resolve(result);
    return result;
  }

  public Result get(Key key) throws Exception {
    return retry(new Get(key));
  }

  private Result retry(Body body) throws Exception {
    Result result = new Result("UNKNOWN_OUTCOME", null, -1);
    for (int attempt = 0; attempt < 9; attempt++) {
      int node =
          result.code().equals("NOT_LEADER") && result.leader() >= 0 && result.leader() < 3
              ? result.leader()
              : attempt % 3;
      result = request(node, 3, body, 1800);
      if (!Set.of("NOT_LEADER", "UNAVAILABLE", "UNKNOWN_OUTCOME").contains(result.code()))
        return result;
    }
    return result;
  }

  public Result admin(int node, String action, int peer) throws Exception {
    return request(node, 4, new Admin(action, peer), 2500);
  }

  public Result direct(int node, Body body, long timeout) throws Exception {
    return request(node, 3, body, timeout);
  }

  private Result request(int node, int source, Body body, long timeout) throws Exception {
    if (pending.size() >= 256) return new Result("RESOURCE_EXHAUSTED", null, -1);
    long id = correlation.incrementAndGet();
    var future = new CompletableFuture<Result>();
    pending.put(id, future);
    try {
      Channel channel = channel(node);
      if (!channel.isWritable()) return new Result("RESOURCE_EXHAUSTED", null, -1);
      channel.writeAndFlush(
          Unpooled.wrappedBuffer(
              encode(new Envelope(source, node, settings.token(source), id, body))));
      return future.get(timeout, TimeUnit.MILLISECONDS);
    } catch (TimeoutException | ExecutionException | java.net.ConnectException e) {
      return new Result("UNKNOWN_OUTCOME", null, -1);
    } finally {
      pending.remove(id);
    }
  }

  private synchronized Channel channel(int node) throws Exception {
    Channel existing = channels.get(node);
    if (existing != null && existing.isActive()) return existing;
    var tls = TlsTransport.clientContext(settings);
    Channel created =
        new Bootstrap()
            .group(group)
            .channel(NioSocketChannel.class)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 500)
            .handler(
                new ChannelInitializer<SocketChannel>() {
                  protected void initChannel(SocketChannel ch) {
                    ch.pipeline()
                        .addLast(TlsTransport.handler(tls, ch, settings.basePort() + node));
                    TlsTransport.frames(ch.pipeline());
                    ch.pipeline()
                        .addLast(
                            new SimpleChannelInboundHandler<ByteBuf>() {
                              protected void channelRead0(ChannelHandlerContext ctx, ByteBuf buffer)
                                  throws Exception {
                                byte[] bytes = new byte[buffer.readableBytes()];
                                buffer.readBytes(bytes);
                                Envelope e = decode(bytes);
                                if (e.source() != node
                                    || !MessageDigest.isEqual(
                                        e.token().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                        settings
                                            .token(node)
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                                    || !(e.body() instanceof Response response)) {
                                  ctx.close();
                                  return;
                                }
                                CompletableFuture<Result> f = pending.get(e.correlation());
                                if (f != null) f.complete(response.result());
                              }

                              @Override
                              public void exceptionCaught(
                                  ChannelHandlerContext ctx, Throwable error) {
                                ctx.close();
                              }
                            });
                  }
                })
            .connect("localhost", settings.basePort() + node)
            .sync()
            .channel();
    channels.put(node, created);
    return created;
  }

  @Override
  public void close() {
    channels.values().forEach(c -> c.close().awaitUninterruptibly());
    group.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly();
  }
}
