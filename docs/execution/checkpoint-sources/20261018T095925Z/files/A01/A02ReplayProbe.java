import io.github.cheshmakzanoon.portfolio.durableflow.*;
import io.github.cheshmakzanoon.portfolio.durableflow.Definition.*;
import io.github.cheshmakzanoon.portfolio.durableflow.Interpreter.*;
import java.util.*;
import java.time.*;
class A02ReplayProbe {
 public static void main(String[] args) {
  var d=new Definition("failed-then-cancel",1,List.of(new Node("root",Kind.ACTIVITY,List.of(),"reserve",Safety.NON_IDEMPOTENT,null,Type.TEXT,Type.TEXT,0,null,null,null)));
  var failed=List.of(new Event(1,EventType.STARTED,"","start",Instant.EPOCH),new Event(2,EventType.FAILED,"root","failed",Instant.EPOCH.plusSeconds(1)));
  var after=new ArrayList<Event>(failed);after.add(new Event(3,EventType.CANCELLED,"","cancel",Instant.EPOCH.plusSeconds(2)));
  var engine=new Interpreter();System.out.println("before="+engine.replay(d,failed,Map.of()).state());System.out.println("after="+engine.replay(d,after,Map.of()).state());
 }
}
