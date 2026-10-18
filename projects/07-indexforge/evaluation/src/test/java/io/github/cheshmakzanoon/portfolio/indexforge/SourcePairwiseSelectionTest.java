package io.github.cheshmakzanoon.portfolio.indexforge;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SourcePairwiseSelectionTest {
  private record Fixture(List<PairwiseData.Query> queries,Map<String,Map<String,Integer>> labels,Set<String> ids) {}
  private static Fixture fixture(int dimensions,boolean evidence) {
    var queries=new ArrayList<PairwiseData.Query>();var labels=new TreeMap<String,Map<String,Integer>>();Set<String> ids=new HashSet<>();
    for(int i=0;i<40;i++) {
      String id="q"+i,good="good"+(i%17),bad="bad"+(i%17);var grades=Map.of(good,3,bad,1);
      double[] high=new double[dimensions],low=new double[dimensions];if(evidence)high[dimensions>10?10:0]=1;
      var rows=List.of(new PairwiseData.Row(bad,low,1,0),new PairwiseData.Row(good,high,3,1),new PairwiseData.Row("noise",low,0,2));
      queries.add(new PairwiseData.Query(id,Wave2Inputs.judgmentGroup(grades),i<20?"process source":"member","development",rows));
      labels.put(id,grades);if(i>=20)ids.add(id);
    }
    return new Fixture(queries,labels,ids);
  }
  private static SourcePairwiseSelection.Result select(SourcePairwiseSelection selector,Fixture f,PairwiseSelection.Metrics guard) {
    return selector.select(f.queries(),f.labels(),f.ids(),GroupedFolds.assign(f.queries()),"a".repeat(64),"b".repeat(64),guard,guard);
  }

  @Test
  void finiteGroupedFitsKeepIdentifierOrderUseLargerL2TieAndPreserveAllOofPaths() {
    for(int dimensions:List.of(12,16)) {
      var fixture=fixture(dimensions,true);var guard=PairwiseSelection.metrics(PairwiseSelection.basePaths(fixture.queries()),fixture.labels(),fixture.ids());
      var result=select(new SourcePairwiseSelection(),fixture,guard);
      assertEquals("FITTED_BEFORE_VALIDATION",result.status());assertNotNull(result.selected());
      assertEquals(10,result.folds().size());assertEquals(2,result.trials().size());assertEquals(1,result.selected().model().l2());
      assertEquals(dimensions,result.selected().model().dimensions());assertEquals(40,result.selectedOutOfFoldPaths().size());
      assertTrue(result.trials().stream().allMatch(t->t.failure()==null&&t.metrics().dominates(guard)));
      for(var query:fixture.queries()) {
        var ranked=result.selectedOutOfFoldPaths().get(query.id());
        if(PlainTextQuery.identifier(query.text()))assertEquals(query.rows().stream().map(PairwiseData.Row::key).toList(),ranked);
        else assertEquals(query.rows().get(1).key(),ranked.getFirst());
      }
      assertTrue(result.selected().model().weights()[10]>0);
      assertEquals("a".repeat(64),result.selected().model().trainingExport());
    }
  }

  @Test
  void missingEvidenceAndImpossibleJointGuardNeverTriggerRescueFits() {
    var none=fixture(16,false);var guard=new PairwiseSelection.Metrics(1,1,1);
    var skipped=select(new SourcePairwiseSelection(),none,guard);
    assertEquals("NOT_ATTEMPTED_SOURCE_AUDIT_CONDITION",skipped.status());assertTrue(skipped.folds().isEmpty());assertNull(skipped.selected());
    var result=select(new SourcePairwiseSelection(),fixture(16,true),guard);
    assertEquals("FAILED_NO_JOINT_DEVELOPMENT_DOMINANCE",result.status());assertEquals(10,result.folds().size());assertEquals(2,result.trials().size());assertNull(result.selected());
  }

  @Test
  void wholeBudgetFailuresPreserveEachFiniteTrialAndRejectChangedGroupsLabelsAndDimensions() {
    var fixture=fixture(16,true);var clock=new AtomicLong();
    var result=select(new SourcePairwiseSelection(()->clock.getAndAdd(900_000_000_001L)),fixture,new PairwiseSelection.Metrics(0,0,0));
    assertNull(result.selected());assertEquals(2,result.trials().size());assertEquals(2,result.folds().size());
    assertTrue(result.trials().stream().allMatch(t->t.failure().contains("budget")&&t.outOfFoldPaths().isEmpty()));
    var assignments=new HashMap<>(GroupedFolds.assign(fixture.queries()));assignments.put("q0",(assignments.get("q0")+1)%5);
    assertThrows(IllegalArgumentException.class,()->new SourcePairwiseSelection().select(fixture.queries(),fixture.labels(),fixture.ids(),assignments,"a".repeat(64),"b".repeat(64),new PairwiseSelection.Metrics(0,0,0),new PairwiseSelection.Metrics(0,0,0)));
    var wrong=new HashMap<>(fixture.labels());wrong.remove("q0");
    assertThrows(IllegalArgumentException.class,()->new SourcePairwiseSelection().select(fixture.queries(),wrong,fixture.ids(),GroupedFolds.assign(fixture.queries()),"a".repeat(64),"b".repeat(64),new PairwiseSelection.Metrics(0,0,0),new PairwiseSelection.Metrics(0,0,0)));
    assertThrows(IllegalArgumentException.class,()->select(new SourcePairwiseSelection(),fixture(10,true),new PairwiseSelection.Metrics(0,0,0)));
  }
}
