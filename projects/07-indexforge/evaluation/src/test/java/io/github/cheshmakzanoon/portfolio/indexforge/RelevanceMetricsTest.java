package io.github.cheshmakzanoon.portfolio.indexforge;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class RelevanceMetricsTest {
  @Test
  void zeroGradesAndTheTenthVersusEleventhRankHaveIndependentMeaning() {
    var zero =
        RelevanceMetrics.evaluate(List.of("a", "irrelevant"), Map.of("a", 3, "irrelevant", 0));
    assertEquals(1, zero.recall20());
    assertEquals(1, zero.ndcg10());
    assertEquals(1, zero.mrr10());
    assertThrows(
        IllegalArgumentException.class,
        () -> RelevanceMetrics.evaluate(List.of("a"), Map.of("a", -1)));
    List<String> values = new ArrayList<>();
    for (int i = 0; i < 10; i++) values.add("miss" + i);
    values.add("a");
    var eleventh = RelevanceMetrics.evaluate(values, Map.of("a", 3));
    assertEquals(new RelevanceMetrics.Values(0, 0, 1), eleventh);
    values.removeLast();
    values.set(9, "a");
    var tenth = RelevanceMetrics.evaluate(values, Map.of("a", 3));
    assertEquals(1.0 / (Math.log(11) / Math.log(2)), tenth.ndcg10(), 1e-12);
    assertEquals(.1, tenth.mrr10());
    assertEquals(1, tenth.recall20());
  }

  @Test
  void independentHandCalculatedGradedMetrics() {
    Map<String, Integer> grades = Map.of("a", 3, "b", 2, "c", 1);
    var perfect = RelevanceMetrics.evaluate(List.of("a", "b", "c"), grades);
    assertEquals(1, perfect.ndcg10(), 1e-12);
    assertEquals(1, perfect.mrr10(), 1e-12);
    assertEquals(1, perfect.recall20(), 1e-12);
    var second = RelevanceMetrics.evaluate(List.of("irrelevant", "b"), grades);
    double gain = 3 / (Math.log(3) / Math.log(2));
    double ideal = 7 + 3 / (Math.log(3) / Math.log(2)) + 1 / (Math.log(4) / Math.log(2));
    assertEquals(gain / ideal, second.ndcg10(), 1e-12);
    assertEquals(0.5, second.mrr10());
    assertEquals(1.0 / 3, second.recall20(), 1e-12);
    var empty = RelevanceMetrics.evaluate(List.of(), Map.of());
    assertEquals(new RelevanceMetrics.Values(0, 0, 0), empty);
    assertThrows(
        IllegalArgumentException.class, () -> RelevanceMetrics.evaluate(List.of("a", "a"), grades));
    assertThrows(
        IllegalArgumentException.class,
        () -> RelevanceMetrics.evaluate(List.of("a"), Map.of("a", 4)));
  }

  @Test
  void cutoffBoundariesAreIndependent() {
    List<String> values = new ArrayList<>();
    for (int i = 0; i < 20; i++) values.add("miss" + i);
    values.add("a");
    var zero = RelevanceMetrics.evaluate(values, Map.of("a", 3));
    assertEquals(new RelevanceMetrics.Values(0, 0, 0), zero);
    values.set(19, "a");
    values.removeLast();
    var last = RelevanceMetrics.evaluate(values, Map.of("a", 3));
    assertEquals(0, last.ndcg10());
    assertEquals(0, last.mrr10());
    assertEquals(1, last.recall20());
  }
}
