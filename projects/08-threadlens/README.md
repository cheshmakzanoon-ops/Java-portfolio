# ThreadLens

ThreadLens collects JVM evidence using JFR and an explicitly selected startup Java agent. It implements bounded normalization, prefix-stack aggregation, event-time windows, weighted allocation summaries, timing histograms, profile comparison and standalone escaped HTML reports. It does not implement a native CPU sampler.

Java 25 HotSpot on Linux x86-64 is the full validation target. Maven runs through the repository wrapper. No Docker or external service is required.

```bash
./scripts/portfolio.sh up threadlens
./scripts/portfolio.sh demo threadlens
./scripts/portfolio.sh down threadlens
# Optional desktop opening after the demo:
THREADLENS_OPEN_REPORT=1 projects/08-threadlens/scripts/demo.sh
```

The demo launches baseline and deliberately regressed CPU, allocation, contention and virtual-thread workloads, asserts actual sampled CPU / allocation / lock evidence, and writes offline differential reports under `target/demo/`. It runs eight short workload JVMs plus analysis; elapsed time depends on host load. Output is diagnostic evidence; the short demo is not the seven-trial overhead acceptance study.

```bash
source scripts/cloud-env.sh
./mvnw -f projects/08-threadlens/pom.xml -Dmaven.repo.local=/workspace/Java-portfolio/.work/A08/m2 -B -ntp verify
./mvnw -f projects/08-threadlens/pom.xml -Dmaven.repo.local=/workspace/Java-portfolio/.work/A08/m2 -B -ntp -Pmutation verify
projects/08-threadlens/scripts/benchmark.sh  # Full 84-JVM / 126-minute measurement protocol, plus launch cost
```

CLI commands are `capabilities`, `analyze recording.jfr output-directory`, `compare baseline.json candidate.json output.html`, and `benchmark output-directory agent.jar [--smoke]`. The project launcher assembles the runtime classpath with a source-fingerprinted cache. The agent starts as `-javaagent:threadlens-agent-1.0.0-SNAPSHOT.jar=config=threadlens.yaml`; dependencies are shaded.

```yaml
allowPackages: [sample.application]
methodNames: [handleRequest, calculate]
maxMethods: 100
queueCapacity: 65536
outputDirectory: target/observer-run-unique
```

Config is a strict, small YAML subset, documented in [operations](docs/OPERATIONS.md). A missing/invalid configuration disables an installed agent with one bounded diagnostic while the host starts. Constructors, initializers, JDK/tool classes, native/abstract methods, named modules and non-system loaders are skipped. Safe byte-resource frame resolution never initializes target classes. Unsupported array frame merges and unresolved metadata skip the entire class.

Read [architecture](docs/ARCHITECTURE.md), [invariants](docs/INVARIANTS.md), [security](docs/SECURITY.md), [performance protocol](docs/PERFORMANCE.md), and the exact observed [status](docs/STATUS.md). Measurements and gates remain unverified unless STATUS links actual evidence. Sampling proportions and selected-method wall timings are distinct; lock acquisition before synchronized bytecode is outside method timings. Virtual-thread execution samples do not reliably attribute all mounted work.
