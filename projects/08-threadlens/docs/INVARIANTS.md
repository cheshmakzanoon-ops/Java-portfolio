# Invariants

| Guarantee | Enforcement and evidence |
| --- | --- |
| Producers never wait for a consumer | TimingBuffer/EventQueue bounded three-attempt CAS offers; saturation tests |
| Probe work never captures application arguments or object references | Primitive ring slots; TimingProbe only accepts method ID/time/exceptional flag |
| Observer state has explicit ceilings | 10,000 method IDs/stack nodes/allocation subjects, 128 frames, 256 characters, 900 windows, 65,536 queue slots; LimitsTest and real stress |
| Missing evidence remains distinguishable from zero activity | Capability flags, loss/overflow counters, incomplete windows, null comparison proportions |
| Same recording produces identical normalized aggregates | CollectionTest imports the exact stopped/dumped recording twice |
| Live stopped recording and offline replay use the same event set | RecordingStream.stop drains callbacks before dump; CollectionTest compares event/sample/method aggregates |
| Every stack prefix counts its descendant sample; only leaf counts self | StackAggregator and AggregationTest including recursion |
| Window boundary is half-open | WindowAccumulator and event timestamps 99/100 / 9/10 in fixture tests |
| Allocation weight is not an exact object count | ObjectAllocationSample.weight bytes; event count stored separately |
| Exceptions preserve identity and handler precedence | 48 transformation cases plus -Xverify:all JVM and nested finally/catch fixtures |
| Recoverable observer failure leaves host output unchanged | Probe failure injection, malformed/unwritable startup, saturated real agent JVM |
| Report identifiers cannot inject HTML | All dynamic text passes ReportWriter.escape; hostile method/recording fixture |
| No configuration-dependent sampled counts are called exact CPU time | Explicit report units and denominator text |

Resource/fatal VM errors, arbitrary unsupported class loaders/modules, invalid agent JARs and dynamic attachment are outside the recoverable guarantee. Histogram distributions are approximate (two significant digits); clipped duration counts are exported.
