from pathlib import Path
b=Path('projects/09-byteguard/analysis-core/src/main/java/io/github/cheshmakzanoon/portfolio/byteguard')
p=b/'interprocedural/SummaryWorklist.java';s=p.read_text().replace('''    TreeMap<String, MethodSummary> summaries = new TreeMap<>();''','''    TreeMap<String, MethodSummary> summaries = new TreeMap<>();
    methods.keySet().forEach(id->summaries.put(id,MethodSummary.empty()));''');old='''          if (n instanceof org.objectweb.asm.tree.MethodInsnNode call)
            targets.addAll(resolver.resolve(call.owner, call.name, call.desc, call.getOpcode()));''';new=old+'''
          if(n instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode dynamic
            && dynamic.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
            && Set.of("metafactory","altMetafactory").contains(dynamic.bsm.getName()))
            for(Object argument:dynamic.bsmArgs) {
              budget.spend();
              if(argument instanceof org.objectweb.asm.Handle handle) {
                String target=handle.getOwner()+"."+handle.getName()+handle.getDesc();
                if(methods.containsKey(target)) targets.add(target);
              }
            }''';assert old in s;s=s.replace(old,new);p.write_text(s)
p=b/'dataflow/TransferFunction.java';s=p.read_text().replace('''          MethodSummary summary = summaries.get(lambdaTarget);
          if (summary != null) {''','''          MethodSummary summary = summaries.get(lambdaTarget);
          if(tag!=H_INVOKESTATIC) {
            diagnostics.add("UNSUPPORTED_LAMBDA_DISPATCH "+site+" "+lambdaTarget);
            returned=returned.join(Taint.unknownValue());
          }
          if(summary==null) {
            diagnostics.add("UNSUPPORTED_LAMBDA_TARGET "+site+" "+lambdaTarget);
            returned=returned.join(Taint.unknownValue());
          }
          if (summary != null) {''');p.write_text(s)
p=Path('projects/09-byteguard/evaluation/src/test/java/io/github/cheshmakzanoon/portfolio/byteguard/evaluation/BoundaryTest.java');s=p.read_text();old='''    assertEquals(1, new FindingReporter().findings(a).size());
  }

  @Test
  void unsupportedLambdaHeapEffectsAreExplicitlyPartial()''';new='''    assertEquals(1, new FindingReporter().findings(a).size());
    var reverse=new SummaryWorklist().analyze(a.classes(),AnalysisBudget.defaults(),true);
    assertFalse(reverse.partial());
    assertEquals(a.analysis().summaries(),reverse.summaries());
  }

  @Test
  void unresolvedLambdaImplementationIsExplicitlyPartial() throws Exception {
    Path file=compile("MissingLambda", """
      public class MissingLambda {
        public static void run(java.sql.Statement stmt)throws Exception{
          java.util.function.Function<String,String> function=String::trim;
          stmt.execute(function.apply(System.getenv("X")));
        }
      }
      """);
    var result=scan(file,AnalysisBudget.defaults());
    assertTrue(result.analysis().partial());
    assertTrue(result.analysis().diagnostics().stream().anyMatch(d->d.startsWith("UNSUPPORTED_LAMBDA_TARGET")));
    assertEquals(1,new FindingReporter().findings(result).size());
  }

  @Test
  void unsupportedLambdaHeapEffectsAreExplicitlyPartial()''';assert old in s;s=s.replace(old,new);p.write_text(s)
