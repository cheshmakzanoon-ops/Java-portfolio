import java.nio.file.*;
import java.time.*;
import java.util.*;
import jdk.jfr.consumer.*;

public class ProfileCounters {
  static String quote(Object v) { return "\"" + String.valueOf(v).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""; }
  static String json(Object value) {
    if (value instanceof Map<?,?> m) {
      List<String> out = new ArrayList<>();
      m.forEach((k,v) -> out.add(quote(k) + ":" + json(v)));
      return "{" + String.join(",",out) + "}";
    }
    return value instanceof Number || value instanceof Boolean ? value.toString() : quote(value);
  }
  static class SocketStats {
    long bytes, nanos, maximum;
    ArrayList<Long> durations = new ArrayList<>();
    void add(RecordedEvent event) {
      if (durations.size() >= 500000) throw new IllegalStateException("Socket event cap exceeded");
      long n = event.getDuration().toNanos();
      durations.add(n); nanos += n; maximum = Math.max(maximum,n); bytes += event.getLong("bytesRead");
    }
    Map<String,Object> result() {
      Collections.sort(durations);
      int n = durations.size();
      return Map.of("recordedEvents",n,"recordedBytes",bytes,"totalRecordedSeconds",nanos/1e9,
          "meanRecordedMillis",nanos/1e6/n,"maximumRecordedMillis",maximum/1e6,
          "nearestRankP95RecordedMillis",durations.get((int)Math.ceil(n*.95)-1)/1e6,
          "nearestRankP99RecordedMillis",durations.get((int)Math.ceil(n*.99)-1)/1e6);
    }
  }
  static Map<String,Object> numeric(RecordedEvent event) {
    Map<String,Object> row = new TreeMap<>();
    row.put("at",event.getStartTime().toString());
    event.getFields().forEach(field -> {
      Object value=event.getValue(field.getName());
      if (value instanceof Number) row.put(field.getName(),value);
    });
    return row;
  }
  public static void main(String[] args) throws Exception {
    if (args.length != 6) throw new IllegalArgumentException("Two recording/start/end triples required");
    for (int offset=0;offset<args.length;offset+=3) {
      Instant start=Instant.parse(args[offset+1]),end=Instant.parse(args[offset+2]);
      Map<String,SocketStats> sockets=new TreeMap<>();
      Map<String,Map<String,Object>> first=new TreeMap<>(),last=new TreeMap<>();
      long events=0, allocationSamples=0, estimatedAllocatedBytes=0;
      try (RecordingFile file=new RecordingFile(Path.of(args[offset]))) {
        while (file.hasMoreEvents()) {
          RecordedEvent event=file.readEvent();
          if (++events>2000000) throw new IllegalStateException("Event cap exceeded");
          String type=event.getEventType().getName();
          if (type.startsWith("jdk.Container")) {
            first.putIfAbsent(type,numeric(event));last.put(type,numeric(event));
          }
          if (event.getStartTime().isBefore(start) || event.getStartTime().isAfter(end)) continue;
          if (type.equals("jdk.SocketRead")) {
            String key=event.getString("address")+":"+event.getInt("port");
            if (sockets.size()>64) throw new IllegalStateException("Endpoint cap exceeded");
            sockets.computeIfAbsent(key,k -> new SocketStats()).add(event);
          } else if (type.equals("jdk.ObjectAllocationSample")) {
            allocationSamples++;estimatedAllocatedBytes+=event.getLong("weight");
          }
        }
      }
      Map<String,Object> row=new TreeMap<>(), socketRows=new TreeMap<>();
      sockets.forEach((k,v)->socketRows.put(k,v.result()));
      row.put("recording",args[offset]);row.put("workloadStart",start.toString());row.put("workloadEnd",end.toString());
      row.put("totalParsedEvents",events);row.put("socketReadWorkloadWindow",socketRows);
      row.put("containerFirst",first);row.put("containerLast",last);
      row.put("workloadAllocationSamples",allocationSamples);row.put("workloadEstimatedAllocatedBytes",estimatedAllocatedBytes);
      System.out.println(json(row));
    }
  }
}
