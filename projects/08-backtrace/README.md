# Backtrace

A JVM diagnostics tool combining JFR recordings with explicitly selected startup instrumentation. Backtrace normalizes event data, aggregates stack prefixes and event-time windows, summarizes sampled allocations and method timings, and produces standalone HTML/JSON reports. Sampling proportions and selected-method wall times remain separate throughout the pipeline.

The validated platform is Java 25 HotSpot on Linux x86-64. No Docker, database, credentials or external service is required. The first full overhead study **failed its acceptance limits**; correctness checks and bounded collection do not establish acceptable overhead. See [verification status](docs/STATUS.md) and [measurement protocol](docs/PERFORMANCE.md).

## Build and validate

From the repository root:

```bash
source scripts/cloud-env.sh
./mvnw -f projects/08-backtrace/pom.xml \
  -Dmaven.repo.local="$PWD/.work/A08/m2" -B -ntp -Pintegration verify
```

The integration profile runs independent JVMs with the packaged instrumentation JAR and bytecode verification enabled. Selected algorithm mutation testing uses `-Pmutation`; the full overhead and 20-minute stress studies run separately from the development test suite.

The 21 October development verification passed 208 ordinary test methods and seven packaged-JVM integration methods. Strict mutation checks passed across six modules, including the new comparison model and staged-output command. The earlier report implementation was exercised in Chromium; current differential rendering has structural, escaping and navigation regressions, with fresh browser layout verification still pending. [Verification status](docs/STATUS.md) records exact outcomes, source identities and remaining limits.

## Run the demonstration

```bash
./scripts/portfolio.sh up backtrace
./scripts/portfolio.sh demo backtrace
./scripts/portfolio.sh down backtrace
```

The demo runs baseline and deliberately regressed CPU, allocation, contention and virtual-thread workloads, checks their observed evidence, and writes differential reports under `target/demo/`. It runs eight short workload JVMs plus analysis. `up` prepares the CLI; there is no background server. Optional desktop opening is available through `BACKTRACE_OPEN_REPORT=1 projects/08-backtrace/scripts/demo.sh`.

CLI commands:

| Command | Purpose |
| --- | --- |
| `capabilities` | Describe the current JVM's supported event schemas |
| `analyze recording.jfr output-directory` | Normalize a recording and produce HTML/JSON reports |
| `analyze recording.jfr output-directory --timings agent-directory` | Include separately collected selected-method timings |
| `compare baseline.json candidate.json output.html [--json output.json]` | Compare matching declared settings across sample proportions, weighted allocations, monitor waits, parks and selected timings |
| `export folded report.json output.txt` | Collapsed stacks (`a;b;c N`) for flamegraph.pl, inferno and speedscope, with readable frame names |
| `export speedscope report.json output.json` | A speedscope sampled profile with the exact frame names |
| `export diff baseline.json candidate.json output.txt` | `stack baseline candidate` lines for a differential flame graph |
| `benchmark output-directory agent.jar [--smoke]` | Run the declared overhead protocol; smoke results are ineligible for acceptance |

## Configure startup instrumentation

Start the application with `-javaagent:backtrace-agent-1.0.0-SNAPSHOT.jar=config=backtrace.yaml`. The packaged JAR includes its dependencies.

```yaml
allowPackages: [sample.application]
methodNames: [handleRequest, calculate]
maxMethods: 100
queueCapacity: 65536
outputDirectory: target/observer-run-unique
```

Use a dedicated empty output directory for each run. Existing artifacts and symbolic-link output ancestry disable collection before transformation begins; application startup continues with one bounded diagnostic. One startup attempt owns the process-wide probe: repeated agent declarations are ignored, and correcting a failed configuration requires a JVM restart. Configuration accepts a strict, small YAML subset. See [operations](docs/OPERATIONS.md) for bounds and output semantics.

Constructors, initializers, JDK/tool classes, native/abstract methods, named modules and non-system loaders are skipped. Frame resolution reads class resources without initializing target classes. Unsupported array merges or unresolved metadata skip the affected class. Selected method timings exclude implicit synchronized-method lock acquisition before bytecode execution.

## Stacks in other tools

`report.html` now opens with a static flame graph (an icicle chart: root on top, width is the inclusive sample share, labels shorten but tooltips keep the full name and counts) above the navigable tree and tables; it is inline SVG with no script or external resource. `export` hands the same sampled stacks to other profilers. Every export sums to exactly the report's sample count: samples that the bounded stack tree recorded only in a prefix appear as an explicit `[truncated]` leaf (and sampleless ones as `[unattributed]`) instead of being dropped or credited to a frame that was not the leaf. The text forms drop the per-JVM numbers in hidden-class names (`Main$$Lambda.0x...`, `GeneratedMethodAccessor117`, `$Proxy42`) and the parameter descriptors' `;`, so the same code lines up between a baseline and a candidate recording; the speedscope form keeps every exact name. Sampled stacks remain sampled evidence, not call counts or CPU time, in every format.

[Architecture](docs/ARCHITECTURE.md) describes module boundaries; [invariants](docs/INVARIANTS.md) and [security](docs/SECURITY.md) describe safety and privacy. JFR execution samples do not provide exact call counts or complete virtual-thread attribution. Raw recordings may contain data outside the normalized export allowlist.
