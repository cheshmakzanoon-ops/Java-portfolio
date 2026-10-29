package io.github.cheshmakzanoon.portfolio.thepit;

import static io.github.cheshmakzanoon.portfolio.thepit.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The read-only market surface in trusted local mode: shapes, bounds and anonymity. */
class HttpMarketApiTest {
  @TempDir Path dir;
  private final HttpClient client = HttpClient.newHttpClient();
  private final ObjectMapper json = new ObjectMapper();

  private HttpResponse<String> get(HttpApi api, String path, String account) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port() + path));
    if (account != null) request.header("X-Demo-Account", account);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static Command init(String account, long cash, long lots) {
    return new Command(
        "init-" + account, account, Type.INIT, null, null, 0, 0, null, false, cash, lots);
  }

  private static Command order(String account, String id, Side side, long price, long lots) {
    return new Command(id, account, Type.NEW, id, side, price, lots, Tif.GTC, false, 0, 0);
  }

  @Test
  void marketAndTradesReflectTheBookWithoutLeakingIdentity() throws Exception {
    try (Gateway gateway = new Gateway(dir.resolve("journal"), "run", 16, 100);
        HttpApi api =
            new HttpApi(gateway, new InetSocketAddress("127.0.0.1", 0), null, null, true)) {
      JsonNode empty = json.readTree(get(api, "/market", "alice").body());
      assertEquals(0, empty.get("lastAppliedSequence").asLong());
      assertTrue(empty.get("bid").isNull() && empty.get("ask").isNull());
      assertTrue(empty.get("spreadTicks").isNull() && empty.get("midTicks").isNull());
      assertEquals(0, empty.get("stats").get("trades").asLong());
      assertTrue(empty.get("stats").get("vwapTicks").isNull());
      assertEquals(0, json.readTree(get(api, "/trades", "alice").body()).get("trades").size());

      for (Command command :
          List.of(
              init("alice", 10_000, 50),
              init("bob", 10_000, 50),
              order("alice", "bid", Side.BUY, 100, 4),
              order("bob", "ask", Side.SELL, 103, 5),
              order("bob", "hit", Side.SELL, 100, 1)))
        gateway.submit(command.accountId(), command).get(5, TimeUnit.SECONDS);

      HttpResponse<String> response = get(api, "/market?depth=3", "carol");
      assertEquals(200, response.statusCode());
      assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
      JsonNode market = json.readTree(response.body());
      assertEquals(5, market.get("lastAppliedSequence").asLong());
      assertEquals(100, market.get("bid").get("priceTicks").asLong());
      assertEquals(3, market.get("bid").get("lots").asLong());
      assertEquals(103, market.get("ask").get("priceTicks").asLong());
      assertEquals(3, market.get("spreadTicks").asLong());
      assertEquals("101.5", market.get("midTicks").asText());
      assertEquals(1, market.get("bids").size());
      assertEquals("3", market.get("bidLots").asText());
      assertEquals("5", market.get("askLots").asText());
      JsonNode stats = market.get("stats");
      assertEquals(1, stats.get("trades").asLong());
      assertEquals("100.0000", stats.get("vwapTicks").asText());
      assertEquals(100, stats.get("lastTicks").asLong());

      HttpResponse<String> tape = get(api, "/trades?limit=10", "carol");
      assertEquals(200, tape.statusCode());
      JsonNode trades = json.readTree(tape.body());
      assertEquals(5, trades.get("lastAppliedSequence").asLong());
      assertEquals(1, trades.get("trades").size());
      JsonNode trade = trades.get("trades").get(0);
      assertEquals(5, trade.get("sequence").asLong());
      assertEquals(100, trade.get("priceTicks").asLong());
      assertEquals(1, trade.get("lots").asLong());
      assertEquals("SELL", trade.get("takerSide").asText());
      for (String forbidden : List.of("alice", "bob", "carol", "hit", "bid", "ask", "Account"))
        assertFalse(tape.body().contains(forbidden), "tape must not identify " + forbidden);
      assertFalse(response.body().contains("alice") || response.body().contains("bob"));
    }
  }

  @Test
  void queryBoundsAndMethodsAreEnforcedBeforeAnyWork() throws Exception {
    try (Gateway gateway = new Gateway(dir.resolve("bounds"), "run", 16, 100);
        HttpApi api =
            new HttpApi(gateway, new InetSocketAddress("127.0.0.1", 0), null, null, true)) {
      for (String path :
          List.of(
              "/market?depth=0",
              "/market?depth=101",
              "/market?depth=x",
              "/market?depth=1&depth=2",
              "/market?limit=1",
              "/trades?limit=0",
              "/trades?limit=101",
              "/trades?depth=1",
              "/trades?limit=1&limit=1"))
        assertEquals(400, get(api, path, "alice").statusCode(), path);
      assertEquals(200, get(api, "/market?depth=100", "alice").statusCode());
      assertEquals(200, get(api, "/market?%64epth=2", "alice").statusCode());
      assertEquals(200, get(api, "/trades?limit=100", "alice").statusCode());
      assertEquals(401, get(api, "/market", null).statusCode());
      HttpRequest post =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port() + "/market"))
              .header("X-Demo-Account", "alice")
              .POST(HttpRequest.BodyPublishers.ofString("{}"))
              .build();
      assertEquals(404, client.send(post, HttpResponse.BodyHandlers.ofString()).statusCode());
      assertEquals(0, gateway.view().sequence(), "reads never allocate a sequence");
    }
  }
}
