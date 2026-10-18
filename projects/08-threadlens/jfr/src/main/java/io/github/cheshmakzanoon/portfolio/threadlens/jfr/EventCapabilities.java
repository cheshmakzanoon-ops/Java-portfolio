package io.github.cheshmakzanoon.portfolio.threadlens.jfr;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;

public final class EventCapabilities {
  public static final List<String> REQUESTED =
      List.of(
          "jdk.ExecutionSample",
          "jdk.ObjectAllocationSample",
          "jdk.GarbageCollection",
          "jdk.JavaMonitorEnter",
          "jdk.ThreadPark",
          "jdk.DataLoss");

  public record Capability(boolean supported, List<String> fields) {}

  private EventCapabilities() {}

  public static Map<String, Capability> discover() {
    return describe(FlightRecorder.getFlightRecorder().getEventTypes());
  }

  public static Map<String, Capability> describe(List<EventType> types) {
    Map<String, Capability> result = new TreeMap<>();
    for (String name : REQUESTED) result.put(name, new Capability(false, List.of()));
    for (EventType type : types)
      if (result.containsKey(type.getName()))
        result.put(
            type.getName(),
            new Capability(
                true, type.getFields().stream().map(f -> f.getName()).sorted().toList()));
    return result;
  }

  public static Map<String, String> settings(Map<String, Capability> capabilities) {
    Map<String, String> settings = new TreeMap<>();
    capabilities.forEach(
        (name, c) -> {
          if (c.supported()) {
            settings.put(name + "#enabled", "true");
            settings.put(
                name + "#stackTrace",
                Boolean.toString(
                    !name.equals("jdk.GarbageCollection") && !name.equals("jdk.DataLoss")));
          }
        });
    if (capabilities
        .getOrDefault("jdk.ExecutionSample", new Capability(false, List.of()))
        .supported()) settings.put("jdk.ExecutionSample#period", "20 ms");
    if (capabilities
        .getOrDefault("jdk.ObjectAllocationSample", new Capability(false, List.of()))
        .supported()) settings.put("jdk.ObjectAllocationSample#throttle", "150/s");
    for (String name : List.of("jdk.JavaMonitorEnter", "jdk.ThreadPark"))
      if (capabilities.getOrDefault(name, new Capability(false, List.of())).supported())
        settings.put(name + "#threshold", "2 ms");
    return settings;
  }
}
