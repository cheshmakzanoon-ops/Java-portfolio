package io.github.cheshmakzanoon.portfolio.majority;

import static io.github.cheshmakzanoon.portfolio.majority.Model.*;

import java.util.Arrays;

/** Retry identity is retained until a definitive committed mutation result. */
public final class MutationIdentity {
  private final String client;
  private long sequence = 1;
  private Command pending;

  public MutationIdentity(String client) {
    this.client = client;
  }

  public synchronized Command begin(Kind kind, Key key, byte[] value, byte[] expected) {
    Command command = new Command(client, sequence, kind, key, value, expected);
    if (pending != null && !Arrays.equals(pending.digest(), command.digest()))
      throw new IllegalStateException("UNKNOWN_OUTCOME_RETRY_SAME_MUTATION");
    pending = command;
    return command;
  }

  public synchronized void resolve(Result result) {
    if (result.code().equals("OK") || result.code().equals("CAS_MISMATCH")) {
      sequence++;
      pending = null;
    }
  }
}
