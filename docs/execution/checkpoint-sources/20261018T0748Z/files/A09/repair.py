from pathlib import Path
b=Path('projects/09-byteguard/analysis-core/src/main/java/io/github/cheshmakzanoon/portfolio/byteguard')
p=b/'rules/RuleModels.java';s=p.read_text().replace('  public static final String VERSION', '''  private final Set<String> configuredSources;
  private final Map<String,Sink> configuredSinks;
  private final Map<String,Integer> configuredSanitizers;
  public RuleModels(){configuredSources=null;configuredSinks=null;configuredSanitizers=null;}
  public RuleModels(Set<String> sources,Map<String,Sink> sinks,Map<String,Integer> sanitizers){configuredSources=Set.copyOf(sources);configuredSinks=Map.copyOf(sinks);configuredSanitizers=Map.copyOf(sanitizers);for(Sink sink:sinks.values())if(!rules().contains(sink.id())||sink.mask()<1||sink.mask()>7||sink.argument()<0)throw new IllegalArgumentException("invalid exact sink model");for(int context:sanitizers.values())if(context!=1&&context!=2&&context!=4)throw new IllegalArgumentException("sanitizer must have exactly one context");}
  public static final String VERSION''').replace('public boolean source(String id) {','public boolean source(String id) {if(configuredSources!=null)return configuredSources.contains(id);').replace('public Sink sink(String id) {','public Sink sink(String id) {if(configuredSinks!=null)return configuredSinks.get(id);').replace('public int sanitizer(String id) {','public int sanitizer(String id) {if(configuredSanitizers!=null)return configuredSanitizers.getOrDefault(id,0);');p.write_text(s)
p=b/'interprocedural/SummaryWorklist.java';s=p.read_text();start=s.index('    TreeMap<String, MethodIr> methods =');s=s[:start]+'''    return analyze(classes,budget,reverse,new RuleModels());
  }
  public Result analyze(SortedMap<String,ClassFactsReader.ClassFacts> classes,AnalysisBudget budget,boolean reverse,RuleModels models){
'''+s[start:];s=s.replace('    methods.forEach((k, v) -> graphs.put(k, new ControlFlowBuilder().build(v)));','''    try{for(var entry:methods.entrySet()){budget.spend();graphs.put(entry.getKey(),new ControlFlowBuilder().build(entry.getValue(),budget::spend));}}
    catch(AnalysisBudget.Exhausted exhausted){diagnostics.add("RESOURCE_LIMIT "+exhausted.getMessage());return new Result(summaries,diagnostics,true,List.copyOf(methods.keySet()),budget.work(),Map.of(),List.of());}''').replace('m, heap, new RuleModels(), resolver, summaries, budget, diagnostics','m, heap, models, resolver, summaries, budget, diagnostics');p.write_text(s)
p=b/'interprocedural/Analyzer.java';s=p.read_text().replace('    InputManifest manifest =', '''    return scan(input,classpath,release,limits,budget,new RuleModels());
  }
  public Result scan(List<Path> input,List<Path> classpath,int release,InputLimits limits,AnalysisBudget budget,RuleModels models)throws IOException{
    InputManifest manifest =''').replace('analyze(classes, budget, false)','analyze(classes, budget, false,models)');p.write_text(s)
p=Path('projects/09-byteguard/analysis-core/src/test/java/io/github/cheshmakzanoon/portfolio/byteguard/EngineTest.java');p.write_text(p.read_text().replace('new InputLimits(20, 2, 10','new InputLimits(20, 1, 10'))
