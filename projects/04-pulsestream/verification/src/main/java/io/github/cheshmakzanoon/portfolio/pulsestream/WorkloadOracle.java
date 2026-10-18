package io.github.cheshmakzanoon.portfolio.pulsestream;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.util.HexFormat;

/**
 * Bounded reference for workload-v1: direct interval membership, no production panes or watermark.
 */
public final class WorkloadOracle {
  public record Total(long count, long sum, long minimum, long maximum) {}

  public record Proof(long windows, String resultDigest, String referenceDigest) {}

  private static final int WINDOWS = 8;
  private final int keys;
  private final long[] count, sum, minimum, maximum;

  public WorkloadOracle(int keys) {
    if (keys < 1 || keys > 50_000) throw new IllegalArgumentException("keys");
    this.keys = keys;
    count = new long[keys * WINDOWS];
    sum = new long[count.length];
    minimum = new long[count.length];
    maximum = new long[count.length];
    java.util.Arrays.fill(minimum, Long.MAX_VALUE);
    java.util.Arrays.fill(maximum, Long.MIN_VALUE);
  }

  public void measurement(int sensor, long time, long value) {
    if (sensor < 0 || sensor >= keys || time < 0 || time >= 120_000)
      throw new IllegalArgumentException("workload-v1 domain");
    // Enumerate the complete bounded timeline instead of computing pane assignments or merging.
    for (String type : new String[] {"TUMBLING", "SLIDING"}) {
      long width = type.equals("TUMBLING") ? 60_000 : 300_000;
      long first = type.equals("TUMBLING") ? 0 : -240_000;
      for (long start = first; start <= 60_000; start += 60_000)
        if (time >= start && time < start + width) {
          int slot = slot(sensor, type, start);
          count[slot] = Math.addExact(count[slot], 1);
          sum[slot] = Math.addExact(sum[slot], value);
          minimum[slot] = Math.min(minimum[slot], value);
          maximum[slot] = Math.max(maximum[slot], value);
        }
    }
  }

  public Total expected(int sensor, String type, long start) {
    int i = slot(sensor, type, start);
    return new Total(count[i], sum[i], minimum[i], maximum[i]);
  }

  private int slot(int sensor, String type, long start) {
    if (sensor < 0 || sensor >= keys || start % 60_000 != 0)
      throw new IllegalArgumentException("window");
    int local;
    if (type.equals("TUMBLING") && start >= 0 && start <= 60_000) local = (int) (start / 60_000);
    else if (type.equals("SLIDING") && start >= -240_000 && start <= 60_000)
      local = 2 + (int) ((start + 240_000) / 60_000);
    else throw new IllegalArgumentException("window");
    return sensor * WINDOWS + local;
  }

  public Proof verify(PostgresStore store, String run, long watermark) throws SQLException {
    long expectedRows = 0;
    for (int sensor = 0; sensor < keys; sensor++)
      for (String type : new String[] {"TUMBLING", "SLIDING"})
        for (long start = type.equals("TUMBLING") ? 0 : -240_000;
            start <= 60_000;
            start += 60_000) {
          long end = start + (type.equals("TUMBLING") ? 60_000 : 300_000);
          if (end <= watermark && expected(sensor, type, start).count > 0) expectedRows++;
        }
    MessageDigest actual = digest(), reference = digest();
    long rows = 0;
    try (var c = store.connection()) {
      c.setAutoCommit(false);
      try (var query =
          c.prepareStatement(
              "SELECT tenant_id,sensor_id,type,start_time,end_time,count,sum,minimum,maximum,revision,final FROM window_result WHERE run_id=? ORDER BY tenant_id,sensor_id,type,start_time")) {
        query.setString(1, run);
        query.setFetchSize(1000);
        query.setQueryTimeout(30);
        try (var r = query.executeQuery()) {
          while (r.next()) {
            String sensor = r.getString(2), type = r.getString(3);
            if (!r.getString(1).equals("t") || !sensor.matches("sensor[0-9]{1,5}"))
              throw new AssertionError("unexpected SQL key");
            int number = Integer.parseInt(sensor.substring(6));
            long start = r.getLong(4), end = start + (type.equals("TUMBLING") ? 60_000 : 300_000);
            Total expected = expected(number, type, start);
            boolean finalized = watermark >= end + 30_000;
            if (end > watermark
                || r.getLong(5) != end
                || r.getLong(6) != expected.count
                || r.getLong(7) != expected.sum
                || r.getLong(8) != expected.minimum
                || r.getLong(9) != expected.maximum
                || r.getLong(10) < 1
                || r.getBoolean(11) != finalized)
              throw new AssertionError(
                  "independent workload window mismatch: " + sensor + ":" + type + ":" + start);
            update(
                actual,
                sensor
                    + ":"
                    + type
                    + ":"
                    + start
                    + ":"
                    + r.getLong(6)
                    + ":"
                    + r.getLong(7)
                    + ":"
                    + r.getLong(8)
                    + ":"
                    + r.getLong(9)
                    + ":"
                    + r.getBoolean(11));
            update(
                reference,
                sensor
                    + ":"
                    + type
                    + ":"
                    + start
                    + ":"
                    + expected.count
                    + ":"
                    + expected.sum
                    + ":"
                    + expected.minimum
                    + ":"
                    + expected.maximum
                    + ":"
                    + finalized);
            rows++;
          }
        }
      }
      c.commit();
    }
    if (rows != expectedRows)
      throw new AssertionError("missing published windows: " + rows + " != " + expectedRows);
    return new Proof(
        rows,
        HexFormat.of().formatHex(actual.digest()),
        HexFormat.of().formatHex(reference.digest()));
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void update(MessageDigest digest, String row) {
    digest.update(row.getBytes(StandardCharsets.UTF_8));
    digest.update((byte) '\n');
  }
}
