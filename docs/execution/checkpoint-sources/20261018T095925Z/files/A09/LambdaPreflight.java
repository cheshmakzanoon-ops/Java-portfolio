import io.github.cheshmakzanoon.portfolio.byteguard.interprocedural.*;
import io.github.cheshmakzanoon.portfolio.byteguard.input.*;
import java.nio.file.*;import java.util.*;
public class LambdaPreflight {
 public static void main(String[]args)throws Exception{
  var result=new Analyzer().scan(List.of(Path.of(args[0])),List.of(),25,InputLimits.defaults(),AnalysisBudget.defaults());
  var reverse=new SummaryWorklist().analyze(result.classes(),AnalysisBudget.defaults(),true);
  if(result.analysis().partial()||reverse.partial())throw new AssertionError(result.analysis().diagnostics()+" "+reverse.diagnostics());
  if(!result.analysis().summaries().equals(reverse.summaries()))throw new AssertionError("schedule mismatch");
  long sinks=result.analysis().summaries().values().stream().flatMap(s->s.flows().values().stream()).filter(f->f.taint().hasConcrete(4)).count();
  if(sinks!=1)throw new AssertionError("concrete path sinks="+sinks);
  System.out.println("PASS: complete; one concrete lambda-target path flow; forward/reverse summaries equal.");
 }
}
