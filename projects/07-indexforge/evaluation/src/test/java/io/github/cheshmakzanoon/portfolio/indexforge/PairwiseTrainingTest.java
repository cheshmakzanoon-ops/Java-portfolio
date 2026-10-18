package io.github.cheshmakzanoon.portfolio.indexforge;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PairwiseTrainingTest {
  static final String HASH = "a".repeat(64);

  static double[] f(double value) {
    double[] f = new double[10];
    f[0] = value;
    return f;
  }

  static PairwiseData.Query query(
      String id, String group, String text, String split, double low, double high) {
    return new PairwiseData.Query(
        id,
        Digests.sha256(group),
        text,
        split,
        List.of(
            new PairwiseData.Row("low", f(low), 0, 1),
            new PairwiseData.Row("high", f(high), 3, 0)));
  }

  static List<PairwiseData.Query> simple() {
    return List.of(
        query("q1", "g1", "reader lease", "development", 0, 1),
        query("q2", "g2", "source snapshot", "development", 0, 1));
  }

  @Test
  void deterministicLogisticTrainingReducesLossAndLearnsSharedPreference() {
    var a = new PairwiseTrainer().fit(simple(), 1, HASH, HASH);
    var b = new PairwiseTrainer().fit(simple(), 1, HASH, HASH);
    assertEquals(a.model().identity(), b.model().identity());
    assertArrayEquals(a.model().weights(), b.model().weights());
    assertEquals(Math.log(2), a.initialLoss(), 1e-12);
    assertTrue(a.finalLoss() < a.initialLoss());
    assertTrue(a.model().score(f(1)) > a.model().score(f(0)));
    assertTrue(a.model().weights()[0] > 0 && a.model().weights()[0] < 1);
    for (int i = 1; i < 10; i++) assertEquals(0, a.model().weights()[i]);
    assertTrue(a.iterations() < 100);
    assertEquals(2, a.eligibleQueries());
    assertEquals(2, a.pairs());
    assertEquals(7007, a.seed());
    assertEquals(.5, a.model().means()[0]);
    assertEquals(.5, a.model().scales()[0]);
    var renamed =
        List.of(
            query("differentA", "g1", "reader lease", "development", 0, 1),
            query("differentB", "g2", "source snapshot", "development", 0, 1));
    assertArrayEquals(
        a.model().weights(),
        new PairwiseTrainer().fit(renamed, 1, HASH, HASH).model().weights(),
        1e-12);
  }

  @Test
  void queryContributionIsNormalizedRatherThanMultipliedByCandidateCount() {
    var original = new PairwiseTrainer().fit(simple(), 1, HASH, HASH);
    List<PairwiseData.Row> many = new ArrayList<>();
    for (int i = 0; i < 100; i++)
      many.add(new PairwiseData.Row("row" + i, f(i % 2), i % 2 == 0 ? 0 : 3));
    var expanded =
        List.of(
            new PairwiseData.Query("q1", Digests.sha256("g1"), "reader lease", "development", many),
            simple().get(1));
    var fitted = new PairwiseTrainer().fit(expanded, 1, HASH, HASH);
    assertEquals(513, fitted.pairs());
    assertArrayEquals(original.model().weights(), fitted.model().weights(), 1e-10);
  }

  @Test
  void scalersUseTrainingQueriesOnlyWithEqualQueryWeightAndClipping() {
    List<PairwiseData.Row> large = new ArrayList<>();
    for (int i = 0; i < 100; i++) large.add(new PairwiseData.Row("r" + i, f(4), 0));
    var small = query("small", "g", "reader lease", "development", 0, 0);
    var big =
        new PairwiseData.Query("big", Digests.sha256("h"), "source snapshot", "development", large);
    var scaler = DevelopmentScaler.fit(List.of(small, big));
    assertEquals(2, scaler.means()[0], 1e-12);
    assertEquals(2, scaler.scales()[0], 1e-12);
    for (int i = 1; i < 10; i++) assertEquals(1e-6, scaler.scales()[i]);
    scaler.means()[0] = 100;
    scaler.scales()[0] = 100;
    assertEquals(-1, scaler.apply(f(0))[0], 1e-12);
    var validation = query("v", "v", "reader lease", "validation", 10, 12);
    assertThrows(
        IllegalArgumentException.class, () -> DevelopmentScaler.fit(List.of(small, validation)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PairwiseTrainer().fit(List.of(small, validation), 1, HASH, HASH));
    var identifier = query("id", "id", "reader", "development", 10, 12);
    var unchanged = DevelopmentScaler.fit(List.of(small, big, identifier));
    assertArrayEquals(scaler.means(), unchanged.means());
    assertThrows(IllegalArgumentException.class, () -> DevelopmentScaler.fit(List.of(identifier)));
    double[] means = new double[10], scales = new double[10];
    Arrays.fill(scales, 1e-6);
    assertEquals(8, new DevelopmentScaler(means, scales).apply(f(1))[0]);
    means[0] = 1;
    assertEquals(-8, new DevelopmentScaler(means, scales).apply(f(0))[0]);
    assertThrows(
        IllegalArgumentException.class, () -> new DevelopmentScaler(new double[1], scales));
    scales[0] = 0;
    assertThrows(
        IllegalArgumentException.class, () -> new DevelopmentScaler(new double[10], scales));
  }

  @Test
  void groupedFoldsKeepPairedTasksTogetherAndRejectLeakageOrValidationRows() {
    List<PairwiseData.Query> queries = new ArrayList<>();
    for (int i = 0; i < 10; i++)
      queries.add(
          query(
              "q" + i,
              "group" + i / 2,
              i % 2 == 0 ? "reader" : "reader lease",
              "development",
              0,
              1));
    var folds = GroupedFolds.assign(queries);
    var reverse = new ArrayList<>(queries);
    Collections.reverse(reverse);
    assertEquals(folds, GroupedFolds.assign(reverse));
    assertEquals(Set.of(0, 1, 2, 3, 4), new HashSet<>(folds.values()));
    for (int i = 0; i < 10; i += 2) assertEquals(folds.get("q" + i), folds.get("q" + (i + 1)));
    for (int fold = 0; fold < 5; fold++) {
      var train = GroupedFolds.training(queries, folds, fold);
      var held = GroupedFolds.held(queries, folds, fold);
      assertEquals(8, train.size());
      assertEquals(2, held.size());
      assertTrue(
          train.stream().noneMatch(q -> held.stream().anyMatch(h -> h.group().equals(q.group()))));
    }
    var leaky = new HashMap<>(folds);
    leaky.put("q0", (folds.get("q1") + 1) % 5);
    assertThrows(IllegalArgumentException.class, () -> GroupedFolds.training(queries, leaky, 0));
    var missing = new HashMap<>(folds);
    missing.remove("q0");
    assertThrows(IllegalArgumentException.class, () -> GroupedFolds.held(queries, missing, 0));
    var invalid = new HashMap<>(folds);
    invalid.put("q0", 5);
    assertThrows(IllegalArgumentException.class, () -> GroupedFolds.held(queries, invalid, 0));
    assertThrows(IllegalArgumentException.class, () -> GroupedFolds.held(queries, folds, -1));
    assertThrows(IllegalArgumentException.class, () -> GroupedFolds.assign(queries.subList(0, 4)));
    assertThrows(
        IllegalArgumentException.class,
        () -> GroupedFolds.assign(List.of(query("v", "g", "read", "validation", 0, 1))));
  }

  @Test
  void trainingFailuresAndInputBudgetsNeverReturnASuccessModel() {
    var clock = new AtomicLong();
    var budget = new PairwiseTrainer(() -> clock.getAndAdd(10), 100, 1);
    assertThrows(PairwiseTrainer.TrainingFailure.class, () -> budget.fit(simple(), 1, HASH, HASH));
    var oneIteration = new PairwiseTrainer(() -> 0, 1, 100);
    assertThrows(
        PairwiseTrainer.TrainingFailure.class, () -> oneIteration.fit(simple(), 1, HASH, HASH));
    assertThrows(IllegalArgumentException.class, () -> new PairwiseTrainer(() -> 0, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> new PairwiseTrainer(() -> 0, 101, 1));
    assertThrows(IllegalArgumentException.class, () -> new PairwiseTrainer(() -> 0, 100, 0));
    assertThrows(
        IllegalArgumentException.class, () -> new PairwiseTrainer(() -> 0, 100, 30_000_000_001L));
    assertThrows(
        IllegalArgumentException.class, () -> new PairwiseTrainer().fit(simple(), 2, HASH, HASH));
    assertThrows(
        IllegalArgumentException.class, () -> new PairwiseTrainer().fit(simple(), 1, "bad", HASH));
    assertThrows(
        IllegalArgumentException.class, () -> new PairwiseTrainer().fit(simple(), 1, HASH, "bad"));
    assertThrows(
        PairwiseTrainer.TrainingFailure.class,
        () -> new PairwiseTrainer().fit(List.of(simple().getFirst()), 1, HASH, HASH));
    var noContrast =
        List.of(
            query("a", "a", "reader lease", "development", 0, 0),
            query("b", "b", "source snapshot", "development", 0, 0));
    // Equal features can legitimately have different grades: the honest optimum is zero, not
    // fabricated separation.
    var zero = new PairwiseTrainer().fit(noContrast, 1, HASH, HASH);
    assertEquals(0, zero.iterations());
    assertEquals(Math.log(2), zero.finalLoss(), 1e-12);
    var sameGrades =
        new PairwiseData.Query(
            "c",
            HASH,
            "reader lease",
            "development",
            List.of(new PairwiseData.Row("one", f(1), 1), new PairwiseData.Row("two", f(2), 1)));
    assertThrows(
        PairwiseTrainer.TrainingFailure.class,
        () -> new PairwiseTrainer().fit(List.of(sameGrades), 1, HASH, HASH));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PairwiseData.Query("bad", HASH, "reader lease", "held-out", List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PairwiseData.Query(
                "dup",
                HASH,
                "reader lease",
                "development",
                List.of(
                    new PairwiseData.Row("same", f(0), 0), new PairwiseData.Row("same", f(1), 3))));
    assertThrows(IllegalArgumentException.class, () -> new PairwiseData.Row("r", f(0), 4));
    assertThrows(IllegalArgumentException.class, () -> new PairwiseData.Row("r", f(0), 0, 500));
    var row = new PairwiseData.Row("r", f(1), 1);
    row.features()[0] = 0;
    assertEquals(1, row.features()[0]);
  }
}
