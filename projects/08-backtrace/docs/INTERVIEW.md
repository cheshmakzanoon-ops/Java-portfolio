# Technical walkthrough

1. Run the demo. Find plantedCpuHotspot, weighted byte-array allocation, and PlantedLock monitor-enter evidence. Explain why a virtual-thread recording may lack execution samples.
2. Trace a JFR callback through EventNormalizer, EventQueue, ProfileAccumulator and ReportWriter. Identify where work can be dropped and which counters make the affected windows incomplete.
3. Trace one recursive stack. Explain prefix inclusive versus method inclusive versus leaf self counts and why they use different denominators.
4. Read TimingTransformer's appended handler and the 48-case tests. Explain why existing catch/finally precedence and thrown-object identity survive and why synchronized acquisition is excluded.
5. Read SafeHierarchy. Explain the risk of Class.forName during transformation and why unresolved/array frame merges are skipped.
6. Inspect actual paired trials. Distinguish warmup from measurement, closed-loop latency from arrival-queue latency, log-ratio uncertainty from universal overhead claims, and shared stress from reference performance.
7. Inject a consumer fault, malformed config or a two-slot queue. Show host progress and bounded evidence without suppressing VM verification.
8. Make a small live change to window width and predict boundary/test/report changes. Explain the next bottleneck: callback stack conversion, disk emission, bounded histogram cardinality, or JDK decoder allocation.

Third-party mechanisms: OpenJDK JFR consumer/instrumentation APIs, OW2 ASM, HDR Histogram and Jackson under their upstream licenses. Application-specific components cover aggregation, instrumentation scope, privacy filtering and report interpretation. No employment history, production customers or certification are claimed.
