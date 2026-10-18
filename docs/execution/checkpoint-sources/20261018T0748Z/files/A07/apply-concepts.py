from pathlib import Path
b=Path('projects/07-indexforge');pkg=Path('io/github/cheshmakzanoon/portfolio/indexforge')
p=b/'query/src/main/java'/pkg/'SearchConcepts.java';p.write_text(Path('.work/A07/SearchConcepts.java').read_text())
p=b/'query/src/main/java'/pkg/'QueryPlanner.java';s=p.read_text().replace('    NO_PROSE\n','    NO_PROSE,\n    NO_CONCEPTS\n').replace('"source-v3-prose"','"source-v4-concepts"');a=s.index('  public Query plan(QueryAst ast, Mode mode) {');z=s.index('  private Query plan(QueryAst ast, Mode mode, boolean navigational)',a);s=s[:a]+'''  public Query plan(QueryAst ast,Mode mode){return plan(ast,mode,null);}
  public Query plan(QueryAst ast,Mode mode,String raw) {
    boolean navigational=ast instanceof QueryAst.Term term&&term.field().equals("all")&&!term.phrase()&&term.value().matches("[\\\\p{L}_$][\\\\p{L}\\\\p{N}_$]{0,127}");
    Query lexical=plan(ast,mode,navigational);
    if(mode!=Mode.BASELINE&&mode!=Mode.NO_CONCEPTS&&mode!=Mode.NO_PROSE) {
      var related=SearchConcepts.expansion(raw);
      if(related.isPresent())lexical=new BooleanQuery.Builder().add(lexical,BooleanClause.Occur.SHOULD).add(new BoostQuery(related.get(),.75f),BooleanClause.Occur.SHOULD).build();
    }
    lexical.visit(new ClauseBudget());return lexical;
  }
  private static final class ClauseBudget extends QueryVisitor {
    private int terms;
    @Override public void consumeTerms(Query query,Term... values){terms+=values.length;if(terms>768)throw new IllegalArgumentException("expanded query quota");}
    @Override public QueryVisitor getSubVisitor(BooleanClause.Occur occur,Query parent){return this;}
  }

'''+s[z:];p.write_text(s)
p=b/'query/src/main/java'/pkg/'SearchEngine.java';s=p.read_text().replace('new QueryPlanner().plan(ast, mode)','new QueryPlanner().plan(ast, mode,request.text())');p.write_text(s)
p=b/'evaluation/src/main/java'/pkg/'RelevanceEvaluator.java';s=p.read_text().replace('            "QueryPlanner",','            "SearchConcepts",\n            "SearchConcepts$Group",\n            "QueryPlanner$ClauseBudget",\n            "QueryPlanner",');p.write_text(s)
