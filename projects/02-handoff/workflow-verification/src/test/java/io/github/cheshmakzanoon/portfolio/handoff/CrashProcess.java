package io.github.cheshmakzanoon.portfolio.handoff;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

public final class CrashProcess {
  public static void main(String[] args) throws Exception {
    Path marker = Path.of(args[4]);
    if (args[0].equals("start")) {
      WorkflowStore store =
          new WorkflowStore(
              args[1],
              args[2],
              args[3],
              () -> {
                try {
                  Files.writeString(marker, "before-commit");
                  Thread.sleep(60000);
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }
              });
      store.start(args[5], args[6], "crash-start", Map.of());
    } else {
      WorkflowStore store = new WorkflowStore(args[1], args[2], args[3]);
      WorkflowStore.Claim claim =
          new ObjectMapper()
              .readValue(Base64.getDecoder().decode(args[5]), WorkflowStore.Claim.class);
      try (Worker worker = new Worker(store, URI.create(args[6]))) {
        var result = worker.execute(claim, "normal");
        Files.writeString(marker, new ObjectMapper().writeValueAsString(result));
        Thread.sleep(60000);
        store.complete(claim, result);
      }
    }
  }
}
