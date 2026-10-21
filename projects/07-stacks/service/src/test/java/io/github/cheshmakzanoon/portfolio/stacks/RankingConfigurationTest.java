package io.github.cheshmakzanoon.portfolio.stacks;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class RankingConfigurationTest {
  @TempDir Path root;

  @Test
  void bootAndCliCaptureIdenticalExplicitStrategyModelAndProfile() throws Exception {
    double[] scales = new double[10];
    Arrays.fill(scales, 1);
    var model =
        new LinearRankingModel(
            new double[10], scales, new double[10], "a".repeat(64), "b".repeat(64), 1);
    Path path = root.resolve("weights.bin");
    model.writeNew(path);
    var environment =
        new MockEnvironment()
            .withProperty("stacks.data", root.resolve("http").toString())
            .withProperty("stacks.ranking-mode", "SOURCE_AWARE")
            .withProperty("stacks.ranking-profile", "CODE_HIGH")
            .withProperty("stacks.ranking-strategy", "PAIRWISE")
            .withProperty("stacks.ranking-model", path.toString());
    var auth = new AuthorizationFilter();
    try (var http = new StacksApplication().searchEngine(environment, auth);
        var cli =
            new SearchEngine(
                root.resolve("cli"),
                auth,
                QueryPlanner.Mode.SOURCE_AWARE,
                RankingProfile.CODE_HIGH,
                RankingConfiguration.from("PAIRWISE", path.toString()))) {
      assertEquals(cli.rankingIdentity(), http.rankingIdentity());
      assertTrue(http.rankingIdentity().endsWith(model.identity()));
      assertEquals(
          http.rankingIdentity(),
          RankingConfiguration.from("PAIRWISE", path.toString())
              .identity(RankingProfile.CODE_HIGH));
    }
    environment.setProperty("stacks.ranking-strategy", "SYNTAX_GATED");
    assertThrows(
        IllegalArgumentException.class,
        () -> new StacksApplication().searchEngine(environment, auth));
    environment.setProperty("stacks.ranking-strategy", "PAIRWISE");
    Files.write(path, new byte[] {1});
    assertThrows(
        java.io.IOException.class,
        () -> new StacksApplication().searchEngine(environment, auth));
  }
}
