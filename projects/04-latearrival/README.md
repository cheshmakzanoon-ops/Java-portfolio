# Late Arrival

Late Arrival computes event-time telemetry windows with explicit recovery and delivery semantics. An original pane algorithm maintains one-minute tumbling windows and five-minute sliding windows. Kafka transports versioned events; PostgreSQL commits aggregate state, deduplication decisions, result revisions, source checkpoints, and an output outbox together.

The supported execution model is one active processor per finite run. It provides an atomic PostgreSQL processing boundary and at-least-once output delivery. It does not claim distributed rescaling or global exactly-once delivery.

## Build and run

Requirements: JDK 25, the repository Maven wrapper, Docker with Compose, Bash, Python 3, and Linux with `/proc` and `flock`. From the repository root:

```bash
./scripts/portfolio.sh verify latearrival
./scripts/portfolio.sh integration latearrival
./scripts/portfolio.sh up latearrival
./scripts/portfolio.sh demo latearrival
./scripts/portfolio.sh down latearrival
```

In the managed workspace, first run `source scripts/cloud-env.sh` to select the pinned JDK and secure Maven configuration. Integration verification requires real Kafka and PostgreSQL; missing Docker is a failed prerequisite.

The query API listens on `127.0.0.1:18400`. The local profile trusts the explicit `X-Dev-Tenant` header and must remain on loopback. Protected mode validates a configured issuer's RSA signatures, audience, expiry, tenant claim, and `telemetry:read` scope. See the [HTTP contract](contracts/http.md) and [security model](docs/SECURITY.md) before changing that deployment boundary.

## Demonstrated behavior

The deterministic replay uses an injected clock, real Kafka/PostgreSQL, and process termination. It asserts a final first tumbling window with count **3** and sum **35**, a logical duplicate, a rejected late tumbling contribution, separate sliding-window totals, and recovery equality against an independent oracle.

The unit and property tests exercise half-open boundaries, checked aggregate arithmetic, monotonic watermarks, source-idle eligibility, finality, and finite quotas. The integration suite tests SQL/checkpoint atomicity, obsolete-owner fencing, partial outbox publication and retries, source retention gaps, topic identity, database outages, interrupted shutdown, and protected HTTP identities. Transaction and snapshot responsibilities have separate ordinary regressions and mutation targets.

## See why an event was late, without Kafka or PostgreSQL

```bash
projects/04-latearrival/scripts/explain.sh projects/04-latearrival/examples/late-data.jsonl
```

`explain` feeds a file of v1 records (one JSON object per line) through the real `Engine`, one record per transition, and says what happened to each: accepted, a duplicate, rejected with the engine's own reason (`MALFORMED_JSON`, `INVALID_RECORD`, `CONFLICTING_EVENT_ID`, quotas), or accepted but *too late for a finalized window* while still counted in the windows that remain open. It prints each watermark movement and each published result revision, and finishes with every window: its aggregate, whether it is `OPEN` (watermark has not reached its end), `PROVISIONAL` (published, revisable) or `FINAL`, and the watermark at which it publishes and finalizes. `--partitions N` routes records with the production Murmur2 rule (an unseen partition holds the watermark back, as in the real system), `--now MILLIS` sets the processing time (default: the largest event time, so nothing is "from the future"), `--format json` is structured. Replays are checked against the independent `BatchOracle` on random streams. It shows event-time behaviour only: no batching, durability, recovery, fencing, source idleness (no observations are supplied) or delivery.

## Design map

| Module | Responsibility |
| --- | --- |
| `engine` | Deterministic pane transitions, source progress, deduplication, and result revisions |
| `kafka-adapter` | Strict versioned envelopes, partition routing, bounded polling, and output publication |
| `postgres-store` | Fenced ownership, atomic state/checkpoint persistence, durable diagnostics, and query reads |
| `service` | Processor lifecycle and tenant-authorized query API |
| `verification` | Independent oracles, real process-failure scenarios, and workload drivers |

[Architecture](docs/ARCHITECTURE.md) · [Invariants](docs/INVARIANTS.md) · [Operations](docs/OPERATIONS.md) · [Case study](docs/CASE_STUDY.md) · [Verification status](docs/STATUS.md) · [Performance methodology and results](docs/PERFORMANCE.md)

Published measurements identify their exact historical source and boundary. Engine JMH results and durable million-event runs measure different work; neither should be presented as the other's throughput. Run limits, same-version snapshot restoration, and local-storage assumptions remain part of the specification.
