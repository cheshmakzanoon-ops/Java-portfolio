# Apply only after any current invocation/scenario exits.
from pathlib import Path
root=Path('/workspace/Java-portfolio/projects/07-indexforge');base=Path('src/main/java/io/github/cheshmakzanoon/portfolio/indexforge');test=Path('src/test/java/io/github/cheshmakzanoon/portfolio/indexforge')
p=root/'evaluation'/base/'RelevanceEvaluator.java';s=p.read_text();at=s.index('  public static void run(');s=s[:at]+'''  public static String modelFingerprint()throws IOException {
    StringBuilder model=new StringBuilder();
    for(String type:List.of("QueryPlanner","Ranker","CandidateRetriever","CodeIdentifierAnalyzer","CodeIdentifierAnalyzer$1","QueryAst","QueryAst$Parser","RelevanceMetrics")){
      try(var resource=RelevanceEvaluator.class.getResourceAsStream(type+".class")){if(resource==null)throw new IOException("missing model bytecode");model.append(type).append(':').append(Digests.sha256(resource.readAllBytes())).append('\\n');}
    }
    return Digests.sha256(model.toString());
  }

'''+s[at:];s=s.replace('"Pre-held-out selection from development/validation only: " + selected + "\\n"','"Pre-held-out selection from development/validation only: " + selected + "\\nModel SHA256: "+modelFingerprint()+"\\nFrozen fixture SHA256: "+Digests.sha256(Files.readAllBytes(fixtures.resolve("FREEZE.sha256")))+"\\n"');s=s.replace('      Path selection = output.resolve("selection.txt");','      Map<QueryPlanner.Mode,RelevanceMetrics.Values> heldOut=new EnumMap<>(QueryPlanner.Mode.class);\n      Path selection = output.resolve("selection.txt");')
s=s.replace('          summary.add(\n              mode\n                  + " held-out nDCG10="','          heldOut.put(mode,new RelevanceMetrics.Values(ndcg/n,identifierMrr/ids,recall/n));\n          summary.add(\n              mode\n                  + " held-out nDCG10="')
s=s.replace('      Files.writeString(output.resolve(phase + "-per-query.csv"), rows);','''      if(phase.equals("final")) {
        var baseline=heldOut.get(QueryPlanner.Mode.BASELINE);var candidate=heldOut.get(QueryPlanner.Mode.SOURCE_AWARE);
        boolean improved=selected.equals("SOURCE_AWARE")&&candidate.ndcg10()>baseline.ndcg10()&&candidate.mrr10()>=baseline.mrr10();
        summary.add("Retained deployment mode="+(improved?"SOURCE_AWARE":"BASELINE")+"; original baseline retained unless held-out nDCG improves without identifier-MRR regression.");
      }
      Files.writeString(output.resolve(phase + "-per-query.csv"), rows);''');p.write_text(s)
p=root/'evaluation'/test/'RelevanceMetricsTest.java';s=p.read_text();at=s.index('  @Test');s=s[:at]+'''  @Test void zeroGradesAndTheTenthVersusEleventhRankHaveIndependentMeaning() {
    var zero=RelevanceMetrics.evaluate(List.of("a","irrelevant"),Map.of("a",3,"irrelevant",0));assertEquals(1,zero.recall20());assertEquals(1,zero.ndcg10());assertEquals(1,zero.mrr10());
    assertThrows(IllegalArgumentException.class,()->RelevanceMetrics.evaluate(List.of("a"),Map.of("a",-1)));
    List<String> values=new ArrayList<>();for(int i=0;i<10;i++)values.add("miss"+i);values.add("a");var eleventh=RelevanceMetrics.evaluate(values,Map.of("a",3));assertEquals(new RelevanceMetrics.Values(0,0,1),eleventh);
    values.removeLast();values.set(9,"a");var tenth=RelevanceMetrics.evaluate(values,Map.of("a",3));assertEquals(1.0/(Math.log(11)/Math.log(2)),tenth.ndcg10(),1e-12);assertEquals(.1,tenth.mrr10());assertEquals(1,tenth.recall20());
  }

'''+s[at:];p.write_text(s)
