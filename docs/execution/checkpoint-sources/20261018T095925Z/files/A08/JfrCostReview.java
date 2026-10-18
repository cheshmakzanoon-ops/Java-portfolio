import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import jdk.jfr.consumer.*;

/** Streaming, bounded diagnostics of already completed controlled recordings. */
class JfrCostReview {
  static final int LIMIT = 512;
  static String clip(String s) { return s.length() <= 1024 ? s : s.substring(0, 1024); }
  static void add(Map<String,Long> m, String k, long v) {
    k = clip(k); if (!m.containsKey(k) && m.size() >= LIMIT) k = "OVERFLOW";
    m.merge(k, v, Math::addExact);
  }
  static String stack(RecordedEvent e) {
    var s = e.getStackTrace(); if (s == null) return "MISSING";
    StringJoiner out = new StringJoiner(" <- "); int n=0;
    for (var f : s.getFrames()) { if (n++ == 8) break; var m=f.getMethod(); out.add(m.getType().getName()+"."+m.getName()); }
    return clip(out.toString());
  }
  static String quote(String s) { return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")+"\""; }
  static String map(Map<String,Long> m) {
    StringJoiner s=new StringJoiner(",","{","}");
    m.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).limit(16).forEach(e->s.add(quote(e.getKey())+":"+e.getValue())); return s.toString();
  }
  public static void main(String[] args) throws Exception {
    StringJoiner all=new StringJoiner(",\n","[\n","\n]\n");
    for (String line : Files.readAllLines(Path.of(args[0]))) {
      String[] a=line.split("\t"); Path run=Path.of(a[0]); Instant from=Instant.parse(a[1]);
      Instant to=from.plusNanos((long)(Double.parseDouble(a[2])*1e9));
      Map<String,Long> counts=new TreeMap<>(),durations=new TreeMap<>(),samples=new TreeMap<>(),types=new TreeMap<>(),allocationStacks=new TreeMap<>(),waitStacks=new TreeMap<>();
      long observerSamples=0,applicationSamples=0,allSamples=0,read=0,measured=0;
      try (RecordingFile input=new RecordingFile(run.resolve("recording.jfr"))) {
        while(input.hasMoreEvents()) {
          RecordedEvent e=input.readEvent(); read++;
          if(e.getStartTime().isBefore(from)||!e.getStartTime().isBefore(to)) continue;
          measured++; String name=e.getEventType().getName(); add(counts,name,1);
          if(name.equals("jdk.ExecutionSample")) {
            String s=stack(e); add(samples,s,1); allSamples++;
            if(s.contains("threadlens.agent.")||s.contains("threadlens.probe.")) observerSamples++;
            if(s.contains("ControlledWorkload.")) applicationSamples++;
          } else if(name.equals("jdk.ObjectAllocationSample")) {
            long w=e.getLong("weight"); add(types,e.getClass("objectClass").getName(),w);add(allocationStacks,stack(e),w);
          } else if(name.equals("jdk.GarbageCollection")||name.equals("jdk.JavaMonitorEnter")||name.equals("jdk.ThreadPark")) {
            add(durations,name,e.getDuration().toNanos()); if(!name.equals("jdk.GarbageCollection"))add(waitStacks,stack(e),e.getDuration().toNanos());
          }
        }
      }
      all.add("{\"run\":"+quote(run.getFileName().toString())+",\"eventsRead\":"+read+",\"measuredEvents\":"+measured+",\"samples\":"+allSamples+",\"observerStackSamples\":"+observerSamples+",\"applicationStackSamples\":"+applicationSamples+",\"counts\":"+map(counts)+",\"eventDurationNanos\":"+map(durations)+",\"topSampleStacks\":"+map(samples)+",\"weightedAllocationBytesByClass\":"+map(types)+",\"weightedAllocationStacks\":"+map(allocationStacks)+",\"waitDurationStacks\":"+map(waitStacks)+"}");
    }
    Files.writeString(Path.of(args[1]),all.toString(),StandardOpenOption.CREATE_NEW);
  }
}
