from pathlib import Path
b=Path('projects/09-byteguard/analysis-core/src/main/java/io/github/cheshmakzanoon/portfolio/byteguard')
p=b/'dataflow/Taint.java';s=p.read_text().replace('  public Taint substitute(List<Taint> arguments) {','''  public static Taint parameter(String method,int index) { return source("P:"+method+"#"+index); }

  public Taint substitute(List<Taint> arguments) { return substitute(arguments, null); }

  public Taint substitute(List<Taint> arguments,String method) {''').replace('''if (e.getKey().startsWith("P:")) {
        int i = Integer.parseInt(e.getKey().substring(2));''','''String prefix=method==null?"P:":"P:"+method+"#";
      String suffix=e.getKey().startsWith(prefix)?e.getKey().substring(prefix.length()):"";
      if (!suffix.isEmpty() && suffix.chars().allMatch(Character::isDigit)) {
        int i = Integer.parseInt(suffix);''');p.write_text(s)
p=b/'dataflow/WorklistSolver.java';s=p.read_text().replace('Taint.source("P:" + param++)','Taint.parameter(method.id,param++)');p.write_text(s)
p=b/'interprocedural/MethodSummary.java';s=p.read_text().replace('  public MethodSummary instantiate(List<Taint> arguments) {','''  public MethodSummary instantiate(List<Taint> arguments) { return instantiate(arguments,null); }

  public MethodSummary instantiate(List<Taint> arguments,String method) {''').replace('.substitute(arguments)', '.substitute(arguments,method)');p.write_text(s)
p=b/'dataflow/TransferFunction.java';s=p.read_text().replace('.instantiate(actual);','.instantiate(actual,target);').replace('''returned = returned.join(summary.returns().substitute(actual));
            importFlows(summary, actual, site);''','''MethodSummary instantiated=summary.instantiate(actual,lambdaTarget);
            returned = returned.join(instantiated.returns());
            importFlows(instantiated, null, site);''');p.write_text(s)
p=Path('projects/09-byteguard/analysis-core/src/test/java/io/github/cheshmakzanoon/portfolio/byteguard/EngineTest.java');s=p.read_text().replace('a.summaries().values().iterator().next().returns().labels().containsKey("P:0")','a.summaries().values().iterator().next().returns().equals(Taint.parameter(a.summaries().firstKey(),0))').replace('assertEquals(Taint.source("P:0"), results.getFirst().summary().returns());','assertEquals(Taint.parameter(ir.id,0), results.getFirst().summary().returns());').replace('locals[2].taint().labels().containsKey("P:0")','locals[2].taint().labels().containsKey("P:"+ir.id+"#0")').replace('assertEquals(Taint.source("P:2"), result.summary().returns());','assertEquals(Taint.parameter(method.id,2), result.summary().returns());').replace('assertEquals(Taint.source("P:0"), entry.locals[0].taint());','assertEquals(Taint.parameter(method.id,0), entry.locals[0].taint());');p.write_text(s)
p=b/'dataflow/HeapSummary.java';s=p.read_text().replace('''aliases.computeIfAbsent(other,k->new TreeSet<>()).add(reference); changed=true;''','''budget.reserveFact(); aliases.computeIfAbsent(other,k->new TreeSet<>()).add(reference); changed=true;''');start=s.index('    TreeSet<String> visited=');end=s.index('    return visited;',start);s=s[:start]+'''    TreeSet<String> visited=new TreeSet<>(roots); ArrayDeque<String> todo=new ArrayDeque<>(roots);
    while(!todo.isEmpty()) {
      budget.spend(); String next=todo.removeFirst();
      if(visited.size()>4096) throw new AnalysisBudget.Exhausted("heap alias budget exceeded (4096 per value)");
      for(String adjacent:aliases.getOrDefault(next,Collections.emptySortedSet()))
        if(visited.add(adjacent)) todo.addLast(adjacent);
    }
'''+s[end:];p.write_text(s)
