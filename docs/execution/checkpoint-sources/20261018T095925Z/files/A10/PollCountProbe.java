import io.github.cheshmakzanoon.portfolio.routeweaver.*;
import java.util.concurrent.atomic.AtomicLong;
class PollCountProbe {public static void main(String[]args){
for(int n:new int[]{100,250,500}){
var p=Fixtures.problem(n,n==100?5:n==250?10:25,4200+n,"overconstrained");
var calls=new AtomicLong();long start=System.nanoTime(),deadline=start+5_000_000_000L;
var v=new Heuristics().baseline(p,()->{calls.incrementAndGet();return System.nanoTime()>=deadline;});
System.out.println("jobs="+n+" calls="+calls.get()+" elapsedNanos="+(System.nanoTime()-start)+" unassigned="+v.candidate().unassigned().size()+" valid="+v.valid());
}}}
