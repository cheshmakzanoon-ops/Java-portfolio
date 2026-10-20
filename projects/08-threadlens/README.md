# ThreadLens

A JVM diagnostics tool combining JFR recordings with explicitly selected startup instrumentation. ThreadLens normalizes event data, aggregates stack prefixes and event-time windows, summarizes sampled allocations and method timings, and produces standalone HTML/JSON reports. Sampling proportions and selected-method wall times remain separate throughout the pipeline.

The validated platform is Java 25 HotSpot on Linux x86-64. No Docker, database, credentials or external service is required. The first full overhead study **failed its acceptance limits**; correctness checks and bounded collection do not establish acceptable overhead. See [verification status](docs/STATUS.md) and [measurement protocol](docs/PERFORMANCE.md).

## Build and validate

From the repository root:

```bash
source scripts/cloud-env.sh
./mvnw -f projects/08-threadlens/pom.xml \
  -Dmaven.repo.local="$PWD/.work/A08/m2" -B -ntp -Pintegration verify
```

The integration profile runs independent JVMs with the packaged instrumentation JAR and bytecode verification enabled. Selected algorithm mutation testing uses `-Pmutation`; the full overhead and 20-minute stress studies run separately from the development test suite.

## Run the demonstration

```bash
./scripts/portfolio.sh up threadlens
./scripts/portfolio.sh demo threadlens
./scripts/portfolio.sh down threadlens
```

The demo runs baseline and deliberately regressed CPU, allocation, contention and virtual-thread workloads, checks their observed evidence, and writes differential reports under `target/demo/`. It runs eight short workload JVMs plus analysis. `up` prepares the CLI; there is no background server. Optional desktop opening is available through `THREADLENS_OPEN_REPORT=1 projects/08-threadlens/scripts/demo.sh`.

CLI commands:

| Command | Purpose |
| --- | --- |
| `capabilities` | Describe the current JVM's supported event schemas |
| `analyze recording.jfr output-directory` | Normalize a recording and produce HTML/JSON reports |
| `analyze recording.jfr output-directory --timings agent-directory` | Include separately collected selected-method timings |
| `compare baseline.json candidate.json output.html` | Compare profiles with matching declared producer settings |
| `benchmark output-directory agent.jar [--smoke]` | Run the declared overhead protocol; smoke results are ineligible for acceptance |

## Configure startup instrumentation

Start the application with `-javaagent:threadlens-agent-1.0.0-SNAPSHOT.jar=config=threadlens.yaml`. The packaged JAR includes its dependencies.

```yaml
allowPackages: [sample.application]
methodNames: [handleRequest, calculate]
maxMethods: 100
queueCapacity: 65536
outputDirectory: target/observer-run-unique
```

Use a dedicated empty output directory for each run. Existing artifacts and symbolic-link output directories disable collection before transformation begins; application startup continues with one bounded diagnostic. Configuration accepts a strict, small YAML subset. See [operations](docs/OPERATIONS.md) for bounds and output semantics.

Constructors, initializers, JDK/tool classes, native/abstract methods, named modules and non-system loaders are skipped. Frame resolution reads class resources without initializing target classes. Unsupported array merges or unresolved metadata skip the affected class. Selected method timings exclude implicit synchronized-method lock acquisition before bytecode execution.

[Architecture](docs/ARCHITECTURE.md) describes module boundaries; [invariants](docs/INVARIANTS.md) and [security](docs/SECURITY.md) describe safety and privacy. JFR execution samples do not provide exact call counts or complete virtual-thread attribution. Raw recordings may contain data outside the normalized export allowlist.
