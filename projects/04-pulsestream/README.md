# PulseStream

Original Java 25 event-time analytics with recoverable one-minute tumbling and five-minute sliding windows. Kafka transports versioned telemetry; PostgreSQL atomically stores source progress, dedup decisions, state, result revisions, next-offset checkpoints and an at-least-once result outbox. One active processor owns each finite run.

```bash
projects/04-pulsestream/scripts/up.sh
projects/04-pulsestream/scripts/demo.sh
projects/04-pulsestream/scripts/down.sh
```

The asserted replay uses an injected clock, real Kafka/PostgreSQL and real process termination. It checks first-window count 3/sum 35/final, a logical duplicate, a rejected late tumbling contribution, separately listed sliding totals and recovery/oracle digests. The independent property oracle does not call the pane algorithm. A protected query profile validates real issuer signatures and tenant/scope claims; the disposable local profile requires loopback.

Query service: `http://127.0.0.1:18400/health`. All result queries require tenant authorization; see [HTTP contract](contracts/http.md), [operations](docs/OPERATIONS.md) and [security](docs/SECURITY.md). Full verification adds `-Pintegration` to the project Maven build and fails when required Docker is unavailable.

Start with [architecture](docs/ARCHITECTURE.md), [invariants](docs/INVARIANTS.md) and [case study](docs/CASE_STUDY.md). [STATUS](docs/STATUS.md) contains exact observed gates and remaining work; [performance methodology](docs/PERFORMANCE.md) distinguishes the engine JMH boundary from the prescribed durable million-event workload. This project does not claim distributed rescaling or global exactly-once delivery. Finite quotas and a single owner make the supported boundary explicit.
