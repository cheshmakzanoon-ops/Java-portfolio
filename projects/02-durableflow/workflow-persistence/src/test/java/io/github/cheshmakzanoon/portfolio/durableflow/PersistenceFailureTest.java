package io.github.cheshmakzanoon.portfolio.durableflow;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.*;
import java.sql.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class PersistenceFailureTest {
  @Test
  void knownConnectionTransactionResourceAndShutdownStatesPermitAcquisitionRetry() {
    for (String state :
        List.of(
            "08006", "40001", "40P01", "53300", "57P01", "57P02", "57P03", "57014", "HYT00",
            "HYT01")) {
      PersistenceFailure failure = new PersistenceFailure(new SQLException("injected", state));
      assertTrue(failure.retryable(), state);
      assertTrue(failure.databaseFailure(), state);
    }
    for (SQLException cause :
        List.of(
            new SQLTransientConnectionException("connection unavailable"),
            new SQLTimeoutException("query timed out"),
            new SQLRecoverableException("reconnect required"),
            new SQLTransientException("transient", " "))) {
      PersistenceFailure failure = new PersistenceFailure(new IOException(cause));
      assertTrue(failure.retryable());
      assertTrue(failure.databaseFailure());
    }
  }

  @Test
  void terminalSqlContractsAndUnclassifiedFailuresDoNotBecomeEndlessRetries() {
    for (String state : List.of("42P01", "42601", "23505", "28000", "57P04", "0A000", "XX000")) {
      SQLException cause = new SQLException("terminal", state, new SocketTimeoutException());
      PersistenceFailure failure = new PersistenceFailure(cause);
      assertFalse(failure.retryable(), state);
      assertTrue(failure.databaseFailure(), state);
      SocketTimeoutException wrapper = new SocketTimeoutException();
      wrapper.initCause(cause);
      assertFalse(new PersistenceFailure(wrapper).retryable(), state);
      assertFalse(
          new PersistenceFailure(new SQLTransientException("wrapper", cause)).retryable(), state);
    }
    for (Throwable cause :
        List.of(
            new SQLException("unclassified"),
            new SQLException("unclassified", ""),
            new IOException("serialization"),
            new IllegalStateException("configuration"))) {
      assertFalse(new PersistenceFailure(cause).retryable());
    }
    assertFalse(new PersistenceFailure(null).retryable());
    assertFalse(new PersistenceFailure(null).databaseFailure());
  }

  @Test
  void wrappedTransportOutagesAreRecognizedWithoutConfusingSerializationOrCauseCycles() {
    for (Throwable transport :
        List.of(
            new SocketTimeoutException(), new ConnectException(), new NoRouteToHostException())) {
      PersistenceFailure failure = new PersistenceFailure(new IOException(transport));
      assertTrue(failure.retryable());
      assertFalse(failure.databaseFailure());
      assertTrue(new PersistenceFailure(new SQLException("transport", transport)).retryable());
    }
    RuntimeException first = new RuntimeException(), second = new RuntimeException();
    first.initCause(second);
    second.initCause(first);
    PersistenceFailure cyclic = new PersistenceFailure(first);
    assertFalse(cyclic.retryable());
    assertFalse(cyclic.databaseFailure());
  }
}
