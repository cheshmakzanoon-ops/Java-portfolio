package io.github.cheshmakzanoon.portfolio.durableflow;

import java.net.*;
import java.sql.*;
import java.util.*;

/** Storage availability or transaction completion failure; details stay outside HTTP responses. */
public final class PersistenceFailure extends IllegalStateException {
  PersistenceFailure(Throwable cause) {
    super("database operation failed", cause);
  }

  /** Retry acquisition only for a known recoverable transport or transaction failure. */
  public boolean retryable() {
    List<Throwable> chain = causes();
    // An explicit SQL contract remains authoritative through any transport wrapper.
    for (Throwable cause : chain) {
      if (cause instanceof SQLException sql) {
        String state = sql.getSQLState();
        if (state != null && !state.isBlank())
          return state.startsWith("08")
              || state.startsWith("40")
              || state.startsWith("53")
              || Set.of("57P01", "57P02", "57P03", "57014", "HYT00", "HYT01").contains(state);
      }
    }
    for (Throwable cause : chain) {
      if (cause instanceof SQLTransientException
          || cause instanceof SQLRecoverableException
          || cause instanceof SocketTimeoutException
          || cause instanceof ConnectException
          || cause instanceof NoRouteToHostException) return true;
    }
    return false;
  }

  boolean databaseFailure() {
    return causes().stream().anyMatch(SQLException.class::isInstance);
  }

  private List<Throwable> causes() {
    List<Throwable> causes = new ArrayList<>();
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable cause = getCause(); cause != null && seen.add(cause); cause = cause.getCause())
      causes.add(cause);
    return causes;
  }
}
