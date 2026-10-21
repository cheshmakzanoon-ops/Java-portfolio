package io.github.cheshmakzanoon.portfolio.gatehouse;

import java.nio.file.*;
import java.util.*;
import jdk.jfr.consumer.*;
import tools.jackson.databind.ObjectMapper;

/** Summarizes real server-side JFR events; sampled allocation is explicitly an estimate. */
public final class FlightSummary {
  private FlightSummary() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 2) throw new IllegalArgumentException("FlightSummary input.jfr output.json");
    long cpuSamples = 0, gcs = 0, sampledBytes = 0, allocationSamples = 0;
    double cpuSum = 0, cpuMax = 0, gcMillis = 0;
    try (RecordingFile recording = new RecordingFile(Path.of(args[0]))) {
      while (recording.hasMoreEvents()) {
        RecordedEvent event = recording.readEvent();
        switch (event.getEventType().getName()) {
          case "jdk.CPULoad" -> {
            double cpu = event.getFloat("jvmUser") + event.getFloat("jvmSystem");
            cpuSum += cpu;
            cpuMax = Math.max(cpuMax, cpu);
            cpuSamples++;
          }
          case "jdk.GarbageCollection" -> {
            gcs++;
            gcMillis += event.getDuration().toNanos() / 1e6;
          }
          case "jdk.ObjectAllocationSample" -> {
            sampledBytes = Math.addExact(sampledBytes, event.getLong("weight"));
            allocationSamples++;
          }
          default -> {}
        }
      }
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put(
        "sourceRevision",
        System.getenv().getOrDefault("GATEHOUSE_SOURCE_FINGERPRINT", "UNRECORDED"));
    out.put("rawArtifact", args[0]);
    out.put("boundary", "decision-service JVM during authenticated HTTP workload");
    out.put("cpuSamples", cpuSamples);
    out.put("meanJvmCpuFraction", cpuSamples == 0 ? 0 : cpuSum / cpuSamples);
    out.put("maximumJvmCpuFraction", cpuMax);
    out.put("garbageCollections", gcs);
    out.put("totalGcEventMillis", gcMillis);
    out.put("allocationSamples", allocationSamples);
    out.put("sampledAllocationWeightBytes", sampledBytes);
    out.put(
        "limitations",
        List.of(
            "CPU is periodic JFR sampling normalized by JVM processor count; this is not exact cgroup utilization.",
            "Allocation weight is sampled estimation, not exact allocation per decision.",
            "GC event duration includes collector-specific concurrent work and is not total application pause time."));
    Files.writeString(
        Path.of(args[1]),
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(out) + "\n");
  }
}
