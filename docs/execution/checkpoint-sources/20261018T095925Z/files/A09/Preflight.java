import io.github.cheshmakzanoon.portfolio.byteguard.interprocedural.*;
import io.github.cheshmakzanoon.portfolio.byteguard.input.*;
import java.nio.file.*;import java.util.*;
public class Preflight {
 public static void main(String[] args)throws Exception{
  var result=new Analyzer().scan(List.of(Path.of(args[0])),List.of(),25,InputLimits.defaults(),AnalysisBudget.defaults());
  if(result.analysis().partial())throw new AssertionError(result.analysis().diagnostics());
  TreeSet<String> sinks=new TreeSet<>();result.analysis().summaries().values().forEach(s->s.flows().values().forEach(f->{if(f.taint().hasConcrete(1))sinks.add(f.sink().method());}));
  System.out.println("partial=false concreteSQLsinks="+sinks);
  if(sinks.size()!=Integer.parseInt(args[1]))throw new AssertionError(sinks);
 }
}
