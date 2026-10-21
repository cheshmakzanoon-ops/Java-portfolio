package io.github.cheshmakzanoon.portfolio.latearrival;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RetentionTest {
  @Test
  void nextPositionMaySkipOffsetsButNeverRetention() {
    assertDoesNotThrow(
        () ->
            PostgresStore.validateRetainedPositions(
                Map.of(0, 14L), Map.of(0, 10L), Map.of(0, 20L)));
    assertThrows(
        IllegalStateException.class,
        () ->
            PostgresStore.validateRetainedPositions(Map.of(0, 9L), Map.of(0, 10L), Map.of(0, 20L)));
    assertThrows(
        IllegalStateException.class,
        () ->
            PostgresStore.validateRetainedPositions(
                Map.of(0, 21L), Map.of(0, 10L), Map.of(0, 20L)));
  }
}
