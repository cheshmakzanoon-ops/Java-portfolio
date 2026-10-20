package io.github.cheshmakzanoon.portfolio.matchcore;

import static io.github.cheshmakzanoon.portfolio.matchcore.Model.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;

/** Minimal loopback HTTP adapter; matching remains exclusively in Gateway's writer. */
public final class HttpApi implements AutoCloseable {
  private final ObjectMapper json = CommandJson.create();
  private final Gateway gateway;
  private final HttpServer server;
  private final ThreadPoolExecutor executor;
  private final JwtDecoder decoder;
  private final Semaphore handlerPermits = new Semaphore(4);
  private final boolean trusted;

  public HttpApi(
      Gateway gateway, InetSocketAddress address, String issuer, String audience, boolean trusted)
      throws IOException {
    if (trusted && !address.getAddress().isLoopbackAddress())
      throw new IllegalArgumentException("trusted local mode requires loopback binding");
    this.gateway = gateway;
    this.trusted = trusted;
    if (!trusted) {
      NimbusJwtDecoder d = JwtDecoders.fromIssuerLocation(Objects.requireNonNull(issuer));
      d.setJwtValidator(
          new DelegatingOAuth2TokenValidator<>(
              JwtValidators.createDefaultWithIssuer(issuer),
              new JwtClaimValidator<java.time.Instant>(JwtClaimNames.EXP, Objects::nonNull),
              new JwtClaimValidator<java.time.Instant>(
                  JwtClaimNames.IAT,
                  iat -> iat != null && !iat.isAfter(java.time.Instant.now().plusSeconds(60))),
              token ->
                  token.getAudience() != null && token.getAudience().contains(audience)
                      ? OAuth2TokenValidatorResult.success()
                      : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
      decoder = StrictIdentityDecoder.wrap(d);
    } else decoder = null;
    server = HttpServer.create(address, 32);
    executor =
        new ThreadPoolExecutor(
            4,
            4,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64),
            new ThreadPoolExecutor.CallerRunsPolicy());
    server.setExecutor(executor);
    server.createContext("/", this::handle);
    server.start();
  }

  int activeRequests() {
    return 4 - handlerPermits.availablePermits();
  }

  public int port() {
    return server.getAddress().getPort();
  }

  private void handle(HttpExchange x) throws IOException {
    if (!handlerPermits.tryAcquire()) {
      try {
        send(x, 429, Map.of("code", "OVERLOADED_RETRYABLE"));
      } finally {
        x.close();
      }
      return;
    }
    try {
      if (x.getRequestURI().getPath().equals("/health")) {
        send(x, gateway.healthy() ? 200 : 503, Map.of("healthy", gateway.healthy()));
        return;
      }
      String identity;
      Set<String> scopes;
      if (trusted) {
        identity = x.getRequestHeaders().getFirst("X-Demo-Account");
        scopes = Set.of("matchcore:read", "matchcore:write", "matchcore:admin");
      } else {
        String auth = x.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
          send(x, 401, Map.of("code", "UNAUTHENTICATED"));
          return;
        }
        Jwt token = decoder.decode(auth.substring(7));
        identity = token.getSubject();
        String scope = token.getClaimAsString("scope");
        scopes = scope == null ? Set.of() : Set.copyOf(Arrays.asList(scope.split(" ")));
      }
      if (!identifier(identity)) {
        send(x, 401, Map.of("code", "UNAUTHENTICATED"));
        return;
      }
      String path = x.getRequestURI().getPath(), method = x.getRequestMethod();
      boolean write = method.equals("POST");
      if (!scopes.contains(write ? "matchcore:write" : "matchcore:read")) {
        send(x, 403, Map.of("code", "SCOPE_REQUIRED"));
        return;
      }
      Map<String, String> parameters =
          query(
              x,
              path.equals("/book")
                  ? Set.of("depth", "minimumSequence")
                  : path.equals("/executions") ? Set.of("offset", "limit") : Set.of());
      if (path.equals("/commands") && write) {
        byte[] body = x.getRequestBody().readNBytes(65537);
        if (body.length > 65536) {
          send(x, 413, Map.of("code", "BODY_LIMIT"));
          return;
        }
        com.fasterxml.jackson.databind.JsonNode tree = json.readTree(body);
        if (!(tree instanceof com.fasterxml.jackson.databind.node.ObjectNode object))
          throw new IllegalArgumentException();
        if (object.path("type").asText().equals("REPLACE")) {
          if (!object.has("newRemainingQtyLots") || object.has("quantityLots"))
            throw new IllegalArgumentException("replacement requires newRemainingQtyLots");
          object.set("quantityLots", object.remove("newRemainingQtyLots"));
        }
        Command c = json.treeToValue(tree, Command.class);
        c.validate();
        if (!identity.equals(c.accountId())
            || (c.type() == Type.INIT && !scopes.contains("matchcore:admin"))) {
          send(x, 403, Map.of("code", "OWNERSHIP_REQUIRED"));
          return;
        }
        Result r = gateway.submit(identity, c).get(3, TimeUnit.SECONDS);
        send(x, r.code().equals("CONFLICT") ? 409 : 200, r);
      } else if (path.startsWith("/commands/") && method.equals("GET")) {
        String id = path.substring("/commands/".length());
        if (!identifier(id)) throw new IllegalArgumentException();
        Result r = gateway.result(identity, id);
        send(x, r == null ? 404 : 200, r == null ? Map.of("code", "NOT_FOUND") : r);
      } else if (path.equals("/book") && method.equals("GET")) {
        Map<String, String> q = parameters;
        int depth = boundedInt(q, "depth", 20, 1, 100);
        long minimum = Long.parseLong(q.getOrDefault("minimumSequence", "0"));
        if (minimum < 0) throw new IllegalArgumentException("negative minimum sequence");
        View v = gateway.read(minimum, Duration.ofSeconds(2));
        send(
            x,
            200,
            Map.of(
                "lastAppliedSequence",
                v.sequence(),
                "bids",
                v.orders().stream().filter(o -> o.side() == Side.BUY).limit(depth).toList(),
                "asks",
                v.orders().stream().filter(o -> o.side() == Side.SELL).limit(depth).toList()));
      } else if (path.equals("/account") && method.equals("GET")) {
        View v = gateway.view();
        send(
            x,
            200,
            Map.of(
                "lastAppliedSequence",
                v.sequence(),
                "balance",
                v.accounts().getOrDefault(identity, new Balance(0, 0, 0, 0))));
      } else if (path.equals("/executions") && method.equals("GET")) {
        Map<String, String> q = parameters;
        int offset = boundedInt(q, "offset", 0, 0, Integer.MAX_VALUE),
            limit = boundedInt(q, "limit", 20, 1, 100);
        View v = gateway.view();
        send(
            x,
            200,
            Map.of(
                "lastAppliedSequence",
                v.sequence(),
                "executions",
                gateway.executions(identity, offset, limit, v.sequence())));
      } else send(x, 404, Map.of("code", "NOT_FOUND"));
    } catch (JwtException e) {
      send(x, 401, Map.of("code", "INVALID_TOKEN"));
    } catch (SecurityException e) {
      send(x, 403, Map.of("code", "OWNERSHIP_REQUIRED"));
    } catch (TimeoutException e) {
      send(x, 202, Map.of("code", "OUTCOME_UNKNOWN_QUERY_STABLE_ID"));
    } catch (ExecutionException e) {
      send(
          x,
          e.getCause() instanceof RejectedExecutionException ? 429 : 503,
          Map.of(
              "code",
              e.getCause() instanceof RejectedExecutionException
                  ? "OVERLOADED_RETRYABLE"
                  : "RECOVERY_REQUIRED"));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      send(x, 503, Map.of("code", "INTERRUPTED"));
    } catch (IllegalArgumentException | IOException e) {
      send(x, 400, Map.of("code", "INVALID_REQUEST"));
    } catch (RuntimeException e) {
      send(x, 503, Map.of("code", "UNAVAILABLE"));
    } finally {
      handlerPermits.release();
      x.close();
    }
  }

  private Map<String, String> query(HttpExchange x, Set<String> allowed) {
    Map<String, String> result = new HashMap<>();
    String q = x.getRequestURI().getRawQuery();
    if (q != null)
      for (String pair : q.split("&")) {
        String[] kv = pair.split("=", 2);
        String key = URLDecoder.decode(kv[0], java.nio.charset.StandardCharsets.UTF_8);
        String value =
            kv.length == 2 ? URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8) : "";
        if (!allowed.contains(key) || result.putIfAbsent(key, value) != null)
          throw new IllegalArgumentException("unknown or repeated query parameter");
      }
    return result;
  }

  private int boundedInt(Map<String, String> q, String key, int def, int min, int max) {
    int value = Integer.parseInt(q.getOrDefault(key, "" + def));
    if (value < min || value > max) throw new IllegalArgumentException();
    return value;
  }

  private void send(HttpExchange x, int status, Object body) throws IOException {
    byte[] bytes = json.writeValueAsBytes(body);
    x.getResponseHeaders().set("Content-Type", "application/json");
    x.getResponseHeaders().set("Cache-Control", "no-store");
    x.sendResponseHeaders(status, bytes.length);
    x.getResponseBody().write(bytes);
  }

  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }

  public static void main(String[] args) throws Exception {
    // The owned CLI configures the JDK server before its first initialization. Embedders
    // retain ownership of this JVM-wide policy and explicit -D values take precedence.
    System.getProperties().putIfAbsent("sun.net.httpserver.maxReqTime", "5");
    System.getProperties().putIfAbsent("sun.net.httpserver.maxRspTime", "5");
    PathConfig config = PathConfig.parse(args);
    try (Gateway gateway = new Gateway(config.path(), config.runId(), 256, 1_100_000);
        HttpApi api =
            new HttpApi(
                gateway,
                new InetSocketAddress("127.0.0.1", config.port()),
                config.issuer(),
                "matchcore",
                config.trusted())) {
      System.out.println(
          "MatchCore API listening on loopback:"
              + api.port()
              + " mode="
              + (config.trusted() ? "trusted-local-demo" : "protected"));
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    api.close();
                    try {
                      gateway.close();
                    } catch (IOException e) {
                      System.err.println("journal close failed");
                    }
                  }));
      new CountDownLatch(1).await();
    }
  }

  private record PathConfig(
      java.nio.file.Path path, String runId, int port, String issuer, boolean trusted) {
    static PathConfig parse(String[] args) {
      if (args.length < 3)
        throw new IllegalArgumentException(
            "usage: HttpApi journal runId port [issuer|trusted-local-demo]");
      String mode = args.length > 3 ? args[3] : "";
      return new PathConfig(
          java.nio.file.Path.of(args[0]),
          args[1],
          Integer.parseInt(args[2]),
          mode.equals("trusted-local-demo") ? null : mode,
          mode.equals("trusted-local-demo"));
    }
  }
}
