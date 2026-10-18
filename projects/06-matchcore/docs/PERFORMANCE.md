# Measurement boundary and commands

No performance number is accepted until the actual runner output is recorded in evidence. The prescribed v1 driver uses 1,000,000 seeded commands, 64 accounts, exactly 60% NEW / 25% CANCEL / 15% REPLACE per 100-command cycle, 100,000 open-order cap, uniformly distributed integral prices 9900..10100 and quantities 1..10. Duplicate injections are separately scheduled every 1000 commands. The generator may target terminated/missing order IDs; those are real sequenced business results. Every run checks final aggregate cash/inventory and durable runs compare recovery digests.

```bash
projects/06-matchcore/scripts/benchmark.sh core
projects/06-matchcore/scripts/benchmark.sh durable
# Explicit smoke only, never the prescribed acceptance workload:
MATCHCORE_COMMANDS=10000 projects/06-matchcore/scripts/benchmark.sh durable
```

Core driver throughput includes generated command construction, risk, matching, retained outcomes and periodic dedup lookups. Durable driver throughput includes bounded gateway admission, JSON framing, one local `force(true)` per command, immutable aggregate-state publication and acknowledgement. These are separate measurements; memory-only throughput is never durable exchange throughput.

The driver is closed-loop, one outstanding request, and includes scheduled duplicate requests in elapsed wall time. Acknowledgement percentiles exclude generator construction and the separately scheduled duplicates. Closed-loop load has coordinated omission and cannot establish overload latency under arbitrary offered load. Queue depth is a sampled observation, not a full queue histogram. Allocation comes from the JDK ThreadMXBean's live Java thread allocation counters and includes driver/writer/background allocations during the measurement window. Heap is a point-in-time used heap, not retained size after GC. JFR profile recordings are generated under ignored .runtime run directories.

MatchingBenchmark is a separate JMH partial-fill microbenchmark with three one-second warmup iterations, three one-second measurement iterations and three independent forks (-Xmx512m). Per-invocation setup constructs a two-account resting book outside the timed matching method; setup allocations can affect GC/allocation profilers and are not claimed as matching-only allocation. The system driver measures allocation for its own documented boundary. Run JMH only after the independent reference suite passes.

Reference host facts and raw outputs will be linked here after measurement. The current shared cloud worker is a 5-vCPU Intel Xeon Platinum 8573C x86_64 guest, 32 GiB cgroup memory and overlay filesystem. Storage physical-device durability and power-loss behavior are unknown. Performance must run in an exclusive coordinator-approved measurement window; ordinary concurrent build timings are not publishable baselines.

Exact microbenchmark launcher: `projects/06-matchcore/scripts/jmh.sh`. Exact targeted mutation launcher: `./mvnw -f projects/06-matchcore/pom.xml -pl matching-core -Dmaven.repo.local="$PWD/.work/A06/m2" -B -ntp -Pmutation verify`. Mutation targets the original handwritten Book state machine and index (not generated record methods), with a mandatory 70% threshold; survivors must be investigated before claiming acceptance.

Actual environment capture: [environment.json](../evidence/environment.json). The guest exposes five processors but its current cgroup quota is **four CPU cores**; the executable JDK is Eclipse Temurin 25.0.4.1+1-LTS on Debian 13.6. The manifest records these measured limits rather than assuming all visible processors are available.
