package io.github.cheshmakzanoon.portfolio.handoff;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

/**
 * Fault target killed by demo after a provider receipt exists and before engine acknowledgement.
 */
public final class ScenarioWorker {
  private ScenarioWorker() {}

  public static void main(String[] args) throws Exception {
    try (WorkflowStore store =
        new WorkflowStore(
            System.getenv("DB_URL"), System.getenv("DB_USER"), System.getenv("DB_PASSWORD"))) {
      WorkflowStore.Claim q =
          new ObjectMapper()
              .readValue(Base64.getDecoder().decode(args[0]), WorkflowStore.Claim.class);
      try (Worker worker = new Worker(store, URI.create(System.getenv("PROVIDER_URL")))) {
        var receipt = worker.execute(q, "normal");
        Files.writeString(Path.of(args[1]), new ObjectMapper().writeValueAsString(receipt));
        Thread.sleep(60000);
        store.complete(q, receipt);
      }
    }
  }
}
