from pathlib import Path
p=Path('projects/09-byteguard/evaluation/src/test/java/io/github/cheshmakzanoon/portfolio/byteguard/evaluation/BoundaryTest.java')
s=p.read_text();loc='  @Test\n  void unsupportedVoidIndyIsPartialWithoutInvalidFrame()'
new='''  @Test
  void fieldAndReturnedContainerAliasesRetainUnknownMutation() throws Exception {
    Path file=compile("Aliases", """
      public class Aliases {
        static String[] holder=new String[1];
        static java.util.List<String> list=new java.util.ArrayList<>();
        static native void fill(String[] out,String text);
        static native void mutate(java.util.List<String> out,String text);
        static String[] pass(String[] out){return out;}
        static String[] make(){return new String[1];}
        static void store(String[] out){holder=out;}
        public static void field(java.sql.Statement stmt)throws Exception {
          fill(holder,System.getenv("FIELD"));stmt.execute(holder[0]);
        }
        public static void alias(java.sql.Statement stmt)throws Exception {
          String[] out=new String[1];store(out);fill(holder,System.getenv("ALIAS"));stmt.execute(out[0]);
        }
        public static void identity(java.sql.Statement stmt)throws Exception {
          String[] out=new String[1];fill(pass(out),System.getenv("IDENTITY"));stmt.execute(out[0]);
        }
        public static void allocation(java.sql.Statement stmt)throws Exception {
          String[] out=make();fill(out,System.getenv("FACTORY"));stmt.execute(out[0]);
        }
        public static void collection(java.sql.Statement stmt)throws Exception {
          mutate(list,System.getenv("LIST"));stmt.execute(list.get(0));
        }
      }
      """);
    var result=scan(file,AnalysisBudget.defaults());
    assertFalse(result.analysis().partial(),result.analysis().diagnostics().toString());
    var findings=new FindingReporter().findings(result);
    for(String method:List.of("field(","alias(","identity(","allocation(","collection(")) {
      assertTrue(findings.stream().anyMatch(f->f.sink().method().contains(method)),method+": "+findings);
    }
    assertTrue(findings.stream().allMatch(f->f.confidence().equals("low")));
  }

  @Test
  void scalarFieldSetterInstantiatesItsSymbolicWrite() throws Exception {
    Path file=compile("ScalarField", """
      public class ScalarField {
        static String holder;
        static void put(String text){holder=text;}
        public static void run(java.sql.Statement stmt)throws Exception{
          put(System.getenv("FIELD"));stmt.execute(holder);
        }
      }
      """);
    var result=scan(file,AnalysisBudget.defaults());
    assertFalse(result.analysis().partial());
    assertEquals(1,new FindingReporter().findings(result).size());
  }

  @Test
  void safeReturnedAndFieldContainersRemainClean() throws Exception {
    Path file=compile("CleanAliases", """
      public class CleanAliases {
        static String[] holder;
        static String[] pass(String[] out){return out;}
        static String[] make(){return new String[]{"constant"};}
        static void store(String[] out){holder=out;}
        public static void run(java.sql.Statement stmt)throws Exception{
          String[] out=make();store(pass(out));stmt.execute(holder[0]);stmt.execute(out[0]);
        }
      }
      """);
    var result=scan(file,AnalysisBudget.defaults());
    assertFalse(result.analysis().partial());
    assertTrue(new FindingReporter().findings(result).isEmpty());
  }

'''
assert loc in s;s=s.replace(loc,new+loc);p.write_text(s)
