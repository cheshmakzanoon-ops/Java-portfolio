# Architecture and ownership

ThreadLens has two observation paths. JFR supplies sampled/runtime events; an optional startup agent records selected method exits. Their units, loss counters and producer clocks remain separate. Both paths produce bounded aggregates for local JSON/HTML reports.

~~~mermaid
flowchart LR
  L[Live RecordingStream] --> N[Explicit-field normalizer]
  N --> Q[Bounded event queue]
  Q --> E[EventProcessor]
  F[Offline recording] --> N2[Same normalizer]
  N2 --> A2[Same accumulators]
  E --> P[Immutable sorted Profile]
  A2 --> P
  C[Startup allowlist] --> T[ASM transformer]
  T --> B[Primitive timing ring]
  B --> S[TimingSession consumer]
  S --> I[Bounded summaries and integrity metadata]
  I --> TI[Timing importer]
  P --> TI
  TI --> V[Report validation]
  V --> R[Escaped offline HTML / JSON]
~~~

## Source boundaries

| Module | Sources and responsibility |
| --- | --- |
| probe | [TimingProbe](../probe/src/main/java/io/github/cheshmakzanoon/portfolio/threadlens/probe/TimingProbe.java) owns enablement, reentrancy and admitted-exit quiescence. [TimingBuffer](../probe/src/main/java/io/github/cheshmakzanoon/portfolio/threadlens/probe/TimingBuffer.java) owns primitive ring publication/sealing. [MethodRegistry](../probe/src/main/java/io/github/cheshmakzanoon/portfolio/threadlens/probe/MethodRegistry.java) assigns bounded stable identities during transformation. |
| agent | [AgentConfig](../agent/src/main/java/io/github/cheshmakzanoon/portfolio/threadlens/agent/AgentConfig.java) parses the bounded UTF-8 configuration. [InstrumentationPolicy](../agent/src/main/java/io/github/cheshmakzanoon/portfolio/threadlens/agent/InstrumentationPolicy.java) selects supported classes/methods. [SafeHierarchy](../agent/src/main/java/io/github/cheshmakzanoon/portfolio/threadlens/agent/SafeHierarchy.java) resolves resource metadata without loading classes. [TimingTransformer](../agent/src/main/java/io/github/cheshmakzanoon/portfolio/threadlens/agent/TimingTransformer.java) injects entry/exit advice. [TimingStatistics](../agent/src/main/java/io/github/cheshmakzanoon/portfolio/threadlens/agent/TimingStatistics.java) owns bounded distributions. [TimingSession](../agent/src/main/java/io/github/cheshmakzanoon/portfolio/threadlens/agent/TimingSession.java) owns installation, consumer/output resources and shutdown. [ThreadLensPremain](../agent/src/main/java/io/github/cheshmakzanoon/portfolio/threadlens/agent/ThreadLensPremain.java) claims the single startup attempt and contains recoverable initialization failures. |
| analysis | [NormalizedEvent](../analysis/src/main/java/io/github/cheshmakzanoon/portfolio/threadlens/analysis/NormalizedEvent.java) bounds normalized values. [StackAggregator](../analysis/src/main/java/io/github/cheshmakzanoon/portfolio/threadlens/analysis/StackAggregator.java) maintains independent trie/method totals. [WindowAccumulator](../analysis/src/main/java/io/github/cheshmakzanoon/portfolio/threadlens/analysis/WindowAccumulator.java) assigns half-open event-time buckets. [AllocationSummary](../analysis/src/main/java/io/github/cheshmakzanoon/portfolio/threadlens/analysis/AllocationSummary.java) sums observed weighted bytes. [ContentionSummary](../analysis/src/main/java/io/github/cheshmakzanoon/portfolio/threadlens/analysis/ContentionSummary.java) summarizes durations. [ProfileAccumulator](../analysis/src/main/java/io/github/cheshmakzanoon/portfolio/threadlens/analysis/ProfileAccumulator.java) owns aggregate/loss state; [Profile](../analysis/src/main/java/io/github/cheshmakzanoon/portfolio/threadlens/analysis/Profile.java) freezes sorted snapshots. [ProfileComparator](../analysis/src/main/java/io/github/cheshmakzanoon/portfolio/threadlens/analysis/ProfileComparator.java) computes sampled proportions and paired log-ratio estimates. |
| jfr | [EventCapabilities](../jfr/src/main/java/io/github/cheshmakzanoon/portfolio/threadlens/jfr/EventCapabilities.java) discovers supported schemas/settings. [EventNormalizer](../jfr/src/main/java/io/github/cheshmakzanoon/portfolio/threadlens/jfr/EventNormalizer.java) copies only allowlisted fields. [EventQueue](../jfr/src/main/java/io/github/cheshmakzanoon/portfolio/threadlens/jfr/EventQueue.java) bounds slots and conservative retained payload. [EventProcessor](../jfr/src/main/java/io/github/cheshmakzanoon/portfolio/threadlens/jfr/EventProcessor.java) owns analysis execution, admission and bounded drain. [RecordingController](../jfr/src/main/java/io/github/cheshmakzanoon/portfolio/threadlens/jfr/RecordingController.java) owns the live recording/directory budget; [RecordingImporter](../jfr/src/main/java/io/github/cheshmakzanoon/portfolio/threadlens/jfr/RecordingImporter.java) owns sequential offline replay. |
| report | [TimingImporter](../report/src/main/java/io/github/cheshmakzanoon/portfolio/threadlens/report/TimingImporter.java) reconciles timing identities, summaries and completion/loss evidence. [ReportDocument](../report/src/main/java/io/github/cheshmakzanoon/portfolio/threadlens/report/ReportDocument.java) declares units/settings/limits. [ReportValidator](../report/src/main/java/io/github/cheshmakzanoon/portfolio/threadlens/report/ReportValidator.java) checks bounded structures and numeric consistency. [ReportWriter](../report/src/main/java/io/github/cheshmakzanoon/portfolio/threadlens/report/ReportWriter.java) owns bounded JSON ingestion and escaped offline rendering. |
| cli | [ThreadLensCli](../cli/src/main/java/io/github/cheshmakzanoon/portfolio/threadlens/cli/ThreadLensCli.java) dispatches explicit commands. [SettingsManifest](../cli/src/main/java/io/github/cheshmakzanoon/portfolio/threadlens/cli/SettingsManifest.java) accepts only bounded producer-setting declarations. [ChildJvm](../cli/src/main/java/io/github/cheshmakzanoon/portfolio/threadlens/cli/ChildJvm.java) owns experiment process deadlines/termination. [BenchmarkRunner](../cli/src/main/java/io/github/cheshmakzanoon/portfolio/threadlens/cli/BenchmarkRunner.java) preserves paired experiment order/results. [BoundedStress](../cli/src/main/java/io/github/cheshmakzanoon/portfolio/threadlens/cli/BoundedStress.java) and [ObserverStress](../cli/src/main/java/io/github/cheshmakzanoon/portfolio/threadlens/cli/ObserverStress.java) are separate synthetic/live stress drivers. |
| workloads | [ControlledWorkload](../workloads/src/main/java/io/github/cheshmakzanoon/portfolio/threadlens/workloads/ControlledWorkload.java) provides named planted causes. [WorkloadMain](../workloads/src/main/java/io/github/cheshmakzanoon/portfolio/threadlens/workloads/WorkloadMain.java) owns worker lifecycle, measurement boundaries and actual producer settings. |
| compatibility | Independent packaged JVMs exercise startup faults, duplicate installation, transformation verification, shutdown races and workload interruption. It has no production implementation. |

## Startup instrumentation

~~~mermaid
sequenceDiagram
  participant JVM
  participant Entry as Premain
  participant Session as TimingSession
  participant Consumer
  participant Probe
  JVM->>Entry: first startup attempt
  Entry->>Session: validated config / empty output
  Session->>Session: stage writer, registry, ring and threads
  Session->>JVM: register shutdown hook
  Session->>Consumer: start owned consumer
  Session->>JVM: install transformer
  Session->>Probe: enable publication last
  Note over Session,Probe: activation lock prevents consumer failure from being followed by re-enablement
  JVM->>Session: shutdown hook
  Session->>Probe: disable and await admitted exits
  Session->>Session: seal reservations / set drain deadline
  Session->>Consumer: stop, drain, join
  Consumer-->>Session: terminal statistics
  Session->>Session: publish registry and status
~~~

Only the first premain attempt owns this JVM. Additional agent declarations cannot replace its ring or disable it. Setup failure attempts transformer removal, hook removal and consumer/writer cleanup while preserving the original failure and suppressed cleanup errors. The host receives one bounded diagnostic; changing configuration requires a new JVM.

Entry timestamps and primitive ring values capture no application arguments or return objects. The first participating thread may allocate its reentrancy guard; there is no per-invocation record allocation on the ordinary probe path. Application scheduling, GC and fatal VM failures prevent a hard real-time guarantee.

Original handlers precede the appended catch-all. Advice preserves return widths and rethrows the same escaping exception object. Constructors, initializers, native/abstract methods, named modules and non-system loaders are excluded. Target classes over 4 MiB, hierarchy resources over 2 MiB, metadata identities that do not match the requested name, unresolved types and unsupported array merges skip transformation. The per-transformation metadata cache holds at most 256 types.

## Live collection and shutdown

~~~mermaid
stateDiagram-v2
  [*] --> New
  New --> Running: configure recording / start consumer
  New --> Closed: failed setup or close
  Running --> Stopping: close, budget or callback failure
  Stopping --> Closed: consumer terminated / tail accounted
  Stopping --> Stopping: deadline failure reported; cleanup retry
~~~

Recording callbacks normalize at most 128 bounded frames, then offer without waiting for analysis. The event queue reserves both a slot and payload bytes. EventProcessor owns every accumulator mutation and checks disk on a monotonic schedule, independent of event volume. Its injectable clock permits exact deadline/cadence regressions.

Shutdown seals admission before draining accepted reservations. A deadline counts the abandoned tail and marks coverage incomplete; a blocked observer causes an explicit termination failure. No snapshot is exposed while its consumer can still mutate state. An observer may request its own shutdown; it returns so the consumer can finish, and a later external close joins that drain. No lifecycle lock is held across that join.

Stopping/dumping always attempts cleanup, including a failed destination write. Dumps accept only a direct child of the validated observer root, preserve an existing destination, and reject suffix traversal. Bounded scans refuse unvisited depth-eight directories rather than treating their contents as zero bytes. Periodic budgets are accounting controls; dedicated directories and OS quotas remain necessary against external writers.

## Aggregation and reports

Trie cardinality and method-summary cardinality are independent. Exhausting a new stack prefix cannot suppress a known method later in the same sample. Recursive method inclusive totals count once per sample; every recursive trie prefix remains distinct. Missing sample stacks, lost events, aggregate overflow and clipped distributions mark retained windows incomplete.

Windows are half-open and assign an entire duration to its start bucket. Allocation values are sampled weighted bytes, not object counts. Histograms retain two significant digits for at most one hour; exact totals and clipping counters remain separate.

JSON reports are limited to 32 MiB with duplicate-field detection, bounded nesting/string/number parsing and semantic validation. Root/sample/window coordinates, flame accounting, method denominators, cardinalities, nonnegative totals and quantile order must agree before rendering/comparison. Immutable sorted profiles preserve timeline order and prevent a later accumulator update or caller mutation from altering a snapshot.

HTML contains no scripts or external resources. Native details, fragment navigation, labelled scroll regions, keyboard focus and local table scrolling support offline inspection. Comparisons require matching declared settings; an adjacent manifest is an operator-provided declaration, not authenticated proof of the producer configuration. Analyzer platform metadata is labelled separately.
