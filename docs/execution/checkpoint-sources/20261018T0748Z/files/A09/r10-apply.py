from pathlib import Path
p=Path('projects/09-byteguard/analysis-core/src/main/java/io/github/cheshmakzanoon/portfolio/byteguard/dataflow/TransferFunction.java')
s=p.read_text();old='''        if (op == GETFIELD) f.pop();
        f.push(
            heap.readField(
                field.owner + "." + field.name + field.desc,
                AbstractValue.kind(Type.getType(field.desc))));'''
new='''        AbstractValue receiver=op==GETFIELD?f.pop():AbstractValue.of(AbstractValue.Kind.REF,Taint.BOTTOM);
        AbstractValue loaded=heap.readField(field.owner+"."+field.name+field.desc,AbstractValue.kind(Type.getType(field.desc)));
        f.push(new AbstractValue(loaded.kind(),loaded.taint().join(heap.elements(receiver)),loaded.references(),loaded.constant()));'''
assert old in s;s=s.replace(old,new);p.write_text(s)
p=Path('projects/09-byteguard/evaluation/src/test/java/io/github/cheshmakzanoon/portfolio/byteguard/evaluation/BoundaryTest.java');s=p.read_text();pos=s.index('  @Test\n  void unsupportedVoidIndy')
s=s[:pos]+'''  @Test
  void unknownObjectMutationReachesScalarFieldsAndPreservesUnknown() throws Exception {
    Path file=compile("ObjectMutation", """
      public class ObjectMutation {
        String text="constant";
        static native void mutate(ObjectMutation box,String text);
        public static void run(java.sql.Statement stmt)throws Exception {
          ObjectMutation box=new ObjectMutation();mutate(box,System.getenv("FIELD"));stmt.execute(box.text);
        }
        public static String uncertain(){
          ObjectMutation box=new ObjectMutation();mutate(box,"constant");return box.text;
        }
      }
      """);
    var result=scan(file,AnalysisBudget.defaults());
    assertFalse(result.analysis().partial());
    var findings=new FindingReporter().findings(result);
    assertEquals(1,findings.size());
    assertEquals("low",findings.getFirst().confidence());
    assertTrue(result.analysis().summaries().entrySet().stream()
      .filter(e->e.getKey().contains(".uncertain("))
      .allMatch(e->e.getValue().returns().unknown()&&!e.getValue().returns().equals(io.github.cheshmakzanoon.portfolio.byteguard.dataflow.Taint.BOTTOM)));
    Path clean=compile("KnownField", """
      public class KnownField {
        String text="constant";
        static void set(KnownField box){box.text="known";}
        public static void run(java.sql.Statement stmt)throws Exception{
          KnownField box=new KnownField();set(box);stmt.execute(box.text);
        }
      }
      """);
    var matched=scan(clean,AnalysisBudget.defaults());
    assertFalse(matched.analysis().partial());
    assertTrue(new FindingReporter().findings(matched).isEmpty());
  }

'''+s[pos:];p.write_text(s)
