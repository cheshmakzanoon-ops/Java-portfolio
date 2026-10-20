package io.github.cheshmakzanoon.portfolio.durableflow;

import static io.github.cheshmakzanoon.portfolio.durableflow.WorkflowSql.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cheshmakzanoon.portfolio.durableflow.Data.*;
import io.github.cheshmakzanoon.portfolio.durableflow.Interpreter.*;
import java.sql.*;
import java.util.*;

/** Contiguous versioned history and serialization limits within the caller's instance lock. */
final class WorkflowHistory {
  private final ObjectMapper json;

  WorkflowHistory(ObjectMapper json) {
    this.json = json;
  }

  void append(Connection c, UUID id, EventType type, String task, String value) throws Exception {
    append(c, id, type, task, value, List.of());
  }

  void append(Connection c, UUID id, EventType type, String task, String value, List<Value> result)
      throws Exception {
    String typed = json.writeValueAsString(result);
    if (typed.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
        > WorkflowLimits.RESULT_BYTES) throw new IllegalArgumentException("result byte cap");
    if (value == null) value = "";
    Value.text(
        value); // One text policy for typed results and legacy/event metadata, including Unicode
    // and newlines.
    long expectedVersion;
    try (PreparedStatement s =
            statement(c, "SELECT version FROM workflow_instance WHERE id=?", id);
        ResultSet r = s.executeQuery()) {
      if (!r.next()) throw new NoSuchElementException("execution missing");
      expectedVersion = r.getLong(1);
    }
    if (expectedVersion >= 10000) throw new IllegalArgumentException("history cap");
    long seq;
    try (PreparedStatement s =
            statement(
                c,
                "UPDATE workflow_instance SET version=version+1 WHERE id=? AND version=? RETURNING version",
                id,
                expectedVersion);
        ResultSet r = s.executeQuery()) {
      if (!r.next())
        throw new java.sql.SQLTransactionRollbackException("instance version changed", "40001");
      seq = r.getLong(1);
    }
    update(
        c,
        "INSERT INTO workflow_history(instance_id,sequence,type,task,value,result) VALUES(?,?,?,?,?,?)",
        id,
        seq,
        type.name(),
        task,
        value,
        typed);
  }

  List<Event> read(Connection c, UUID id) throws Exception {
    List<Event> events = new ArrayList<>();
    try (PreparedStatement s =
            statement(
                c,
                "SELECT sequence,type,task,value,at,result FROM workflow_history WHERE instance_id=? ORDER BY sequence",
                id);
        ResultSet r = s.executeQuery()) {
      while (r.next())
        events.add(
            new Event(
                r.getLong(1),
                EventType.valueOf(r.getString(2)),
                r.getString(3),
                r.getString(4),
                r.getTimestamp(5).toInstant(),
                json.readValue(r.getString(6), new TypeReference<List<Value>>() {})));
    }
    return events;
  }
}
