# Performance protocol

Handoff does not yet have a complete reference baseline or a numeric capacity budget. The workload is executable, and finite smoke results are retained, but two completed runs from a failed three-run attempt do not establish repeatable performance.

## Workload

Run `projects/02-handoff/scripts/benchmark.sh` for the full workload, or append `--smoke` for 100 executions. Both use PostgreSQL and the provider's independent effect log.

| Parameter | Full workload |
| --- | ---: |
| Seeded executions | 10,000 |
| Activities per execution | 8 |
| First-attempt rejections before effect | 20% of logical activities |
| Accepted approval signals | 10,000, with duplicate delivery attempts |
| Durable one-second timers | 5,000 |
| Scheduler processes | 2 |
| Worker processes | 8 |
| Activity slots per worker | 4 |
| Warm-up executions | 0 |

The fixed ordinal rule rejects every fifth logical activity on its first attempt. A successful run must observe 80,000 completed activities and unique effects, 16,000 first-attempt rejections, 10,000 accepted signals, 5,000 timers, and an empty final queue. A failed assertion preserves a nonzero command exit and cannot publish a successful result.

The driver submits through the JDBC application boundary; workers call the provider over HTTP. This is a closed-loop workload, so submission cost and offered rate can hide delay through coordinated omission. It does not measure an open-loop HTTP service-level objective. Zero warm-up also means results include a cold-start component.

## Prepare an exclusive measurement window

Stop this project's runtime, then run `projects/02-handoff/scripts/prepare.sh` before reserving the measurement host. Preparation runs ordinary verification and binds the source, root POM, verification log, six module JARs, and two executable JARs. It refuses owned live processes, including those whose PID files are missing.

Export `HANDOFF_PREPARED=1` for startup and workload execution. Prepared mode rechecks the receipt before creating services and runs no Maven. Missing receipts, changed inputs, modified or missing artifacts, and changed verification evidence all refuse startup. Ordinary and integration test commands remain uncached.

Run three complete reset/start/measure/stop rounds under the same verified source and environment. Preserve the result, provider log, profiling files, stdout, and command exit before resetting the next round. Reset removes disposable state; never run it against data needed for unresolved-effect reconciliation.

```bash
source scripts/cloud-env.sh
export HANDOFF_PREPARED=1
./scripts/portfolio.sh reset-demo handoff
./scripts/portfolio.sh up handoff
projects/02-handoff/scripts/benchmark.sh
./scripts/portfolio.sh down handoff
# Repeat the complete sequence twice more, preserving each capture first.
projects/02-handoff/scripts/summarize-performance.py RUN1 RUN2 RUN3
```

## Interpret the measurements

| Measurement | Boundary and limitation |
| --- | --- |
| Driver elapsed time and completion latency | Application submission to completion; includes closed-loop behavior |
| `ScheduleToStartMs` | Task creation to first authorized dispatch, generation 1; retry backoff is separate |
| Timer lateness | Due time to recorded firing; database clock changes can affect scheduling latency |
| Attempts, expirations, queue depth | Observed retry and scheduling behavior, with final empty-queue assertions |
| Database growth | Actual size delta; isolate runs with reset because total database size can include prior data |
| RSS and process CPU ticks | One-second samples for API, provider, schedulers, and workers; not exact peak heap or allocation |
| Unified GC logs | Process-lifetime collection counts, pause time, and heap occupancy; may include startup or earlier demo work |
| Worker 1 JFR | Statistical allocation samples for one worker; not exact whole-cluster allocation |
| Fault-demo recovery delay | Measured separately under worker/scheduler failure, not inferred from ordinary latency |

Record JDK, JVM limits, effective CPU settings, cgroup limits, RAM, source/root-POM fingerprints, Git revision, seed, host contention, and container sharing. An idle container list alone does not establish an exclusive host. Keep unrelated builds, services, stress tests, and profilers stopped throughout the reserved measurement window.

## Capture integrity and comparison policy

Every invocation retains its exit code, environment, results, process samples, and per-process GC logs under `target/performance/<UTC>-<executions>-<pid>/`. JFR startup must succeed before the workload begins. The original recording and summary remain available; exact `jdk.ObjectAllocationSample` JSON bytes are compressed into `allocation-samples.json.gz` after measurement. Producer and compressor must both succeed before non-overwriting publication. Failures retain partial output and the original recording.

Readers require exactly one plain legacy or gzip recording and verify gzip EOF and CRC. The summarizer validates actual workload dimensions, source/JDK/environment identity, and profiling artifacts across all three runs before reporting variability, throughput, resource samples, and latency. The proposed same-host regression tolerance is 25% above the slowest reference duration and p95 completion/scheduling latency. It can be established only when all three-run coefficients of variation are at most 10%; no such budget is currently established.

## Retained results

The [100-execution smoke envelope](../evidence/smoke-100/envelope.json) records 800 unique effects, 160 transient rejections, 50 timers, and an empty queue in 33.877 driver seconds under shared verification load. It validates the workload machinery, not the full baseline.

The failed reference attempt completed two full runs in 962.372049 and 1611.414379 driver seconds. A third was refused before reset because free workspace storage was below the unchanged 2 GiB admission reserve. Two uncompressed allocation JSON exports occupied 3,848,127,471 bytes. [Archive/recovery evidence](../evidence/quiet18-storage/README.md) retains their exact manifests and restoration accounting. No three-run variability result or numeric budget follows from these two measurements.

The [gzip capture repair](../evidence/gzip-capture-repair.json) passed separate controls and verification while preserving the previously built runtime bytes. It changes storage after measurement, not workload dimensions or JFR settings. Three fresh full runs remain required; historical rows are never mixed across source identities.
