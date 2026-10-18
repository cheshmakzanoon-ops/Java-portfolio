package io.github.cheshmakzanoon.portfolio.matchcore;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public final class Model {
  private Model() {}

  public enum Type {
    INIT,
    NEW,
    CANCEL,
    REPLACE
  }

  public enum Side {
    BUY,
    SELL
  }

  public enum Tif {
    GTC,
    IOC,
    FOK
  }

  public record Command(
      String clientCommandId,
      String accountId,
      Type type,
      String orderId,
      Side side,
      long priceTicks,
      long quantityLots,
      Tif timeInForce,
      boolean postOnly,
      long initialCash,
      long initialLots) {
    public String key() {
      return accountId + ":" + clientCommandId;
    }

    public String orderKey() {
      return accountId + ":" + orderId;
    }

    public String hash() {
      return sha(toString());
    }

    public void validate() {
      if (!identifier(clientCommandId) || !identifier(accountId) || type == null)
        throw new IllegalArgumentException("invalid command identity/type");
      if (type != Type.INIT && !identifier(orderId))
        throw new IllegalArgumentException("invalid order identity");
      if (type == Type.INIT && (initialCash < 0 || initialLots < 0))
        throw new IllegalArgumentException("negative initial balance");
      if ((type == Type.NEW || type == Type.REPLACE)
          && (side == null
              || timeInForce == null
              || priceTicks <= 0
              || quantityLots < 0
              || (type == Type.NEW && quantityLots == 0)
              || (postOnly && timeInForce != Tif.GTC)))
        throw new IllegalArgumentException("invalid order fields");
    }
  }

  public static boolean identifier(String s) {
    return s != null && s.matches("[a-zA-Z0-9_-]{1,64}");
  }

  public static long decimalPrice(String text) {
    return new BigDecimal(text).movePointRight(2).longValueExact();
  }

  public record Balance(long cash, long lots, long reservedCash, long reservedLots) {}

  public record Order(
      String accountId,
      String orderId,
      Side side,
      long priceTicks,
      long remaining,
      long priority,
      long revision) {}

  public record Execution(
      long sequence,
      String makerAccount,
      String makerOrder,
      String takerAccount,
      String takerOrder,
      long priceTicks,
      long lots) {}

  public record Result(long sequence, String code, String commandHash, List<Execution> executions) {
    public Result {
      executions = List.copyOf(executions);
    }
  }

  public record View(
      long sequence,
      Map<String, Balance> accounts,
      List<Order> orders,
      List<Execution> executions,
      Map<String, Result> results) {
    public View {
      accounts = Map.copyOf(accounts);
      orders = List.copyOf(orders);
      executions = List.copyOf(executions);
      results = Map.copyOf(results);
    }

    public String digest() {
      return sha(
          sequence
              + "|"
              + new java.util.TreeMap<>(accounts)
              + "|"
              + orders
              + "|"
              + executions
              + "|"
              + new java.util.TreeMap<>(results));
    }
  }

  public record Checkpoint(View view, java.util.Set<String> usedOrderIds) {
    public Checkpoint {
      usedOrderIds = java.util.Set.copyOf(usedOrderIds);
    }
  }

  public static String sha(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }
}
