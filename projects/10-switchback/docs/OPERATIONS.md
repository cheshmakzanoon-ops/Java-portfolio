# Operating Switchback

The supported local workflow is a Linux Java 25 process and a pinned PostgreSQL Compose service. The database is authoritative; killing a JVM does not remove queued jobs or published plans. All included data is synthetic.

## Build and local demonstration

From the repository root:

```sh
source scripts/cloud-env.sh  # managed cloud toolchain/proxy initialization
projects/10-switchback/scripts/build.sh
python3 projects/10-switchback/scripts/test-runtime.py
projects/10-switchback/scripts/up.sh
projects/10-switchback/scripts/demo.sh
python3 projects/10-switchback/scripts/browser-check.py
projects/10-switchback/scripts/down.sh
```

`build.sh` accepts optional Maven flags; use `scripts/build.sh -Pintegration` from the project directory, or the full project path from the repository root, to include PostgreSQL verification in the same source/artifact receipt. It formats this project, verifies it, checks that the source did not change during verification, and writes source/JAR receipts. It refuses to rebuild an owned running service. `up.sh` checks those receipts before starting anything, then starts the project database and an explicitly trusted disposable local service on port 19000. The dispatcher and API share that listener. `down.sh` stops the identified process, waits for exit and removes the project container/network; it preserves the named data volume.

The demo creates 60 jobs and six vehicles, publishes a valid plan, observes completed stops, records a breakdown, audits commitment release, introduces urgent depot cargo and rejects stale publication. To repeat the same seeded scenario, explicitly run `projects/10-switchback/scripts/reset-demo.sh` while the service is running and has no active jobs, then rerun the demo. Reset is restricted to the development profile and demo tenant. Do not remove a volume to reset fixtures.

Build/up/down and polling diagnosis share `.runtime/lifecycle.lock`. A concurrent lifecycle command waits at most 30 seconds. Neither a PID file nor an open port alone establishes ownership: the launcher checks process start ticks, executable, exact JAR argument and listening-socket ownership. Linux pidfds protect signal delivery from PID recycling. Legacy live PID files without process metadata require inspection; the launcher refuses to signal them.

## Configuration

| Variable | Default / effect |
| --- | --- |
| `SWITCHBACK_PORT` | 19000, bound to loopback by the committed service configuration |
| `SWITCHBACK_DB_PORT` | 19001 for the disposable local Compose database |
| `SWITCHBACK_DB_URL` | JDBC URL; the local launcher supplies its selected database port |
| `SWITCHBACK_DB_USER`, `SWITCHBACK_DB_PASSWORD` | Database credentials; committed defaults are disposable fixtures |
| `SWITCHBACK_AUTH_MODE` | `protected`; `up.sh` explicitly selects `dev` |
| `SWITCHBACK_ISSUER`, `SWITCHBACK_AUDIENCE` | Required protected issuer; audience defaults to `switchback` |
| `SWITCHBACK_SOURCE_REVISION` | Set from verified launch inputs, never an HTTP field |
| `SWITCHBACK_DEMO_TOKEN` | Optional Bearer token for the command-line live driver against protected mode |

The protected profile uses signed OIDC tokens with explicit expiry, subject, audience, tenant and scopes. GET routes require `switchback.read`; mutations require `switchback.write`. The anonymous shell/assets contain no tenant data. A development request must use a loopback Host; supplied browser Origin must match the request scheme/host/port, and cross-site Fetch Metadata is refused.

To run protected mode in the foreground, first build and stop the local demo. Initialize your credential bindings and issuer settings, then source `projects/10-switchback/scripts/common.sh`, run `require_build`, set `SWITCHBACK_AUTH_MODE=protected`, and execute the service JAR with the verified Java 25 toolchain. That foreground process is owned by its terminal, not the background launcher's PID record; stop it with its terminal. Protect network-exposed tokens with TLS and provision actual migration/runtime database privileges. Automatic startup DDL currently requires a schema-owning role.

## Solve and publish

A minimal sequence for a problem already imported under the authenticated tenant is:

```http
POST /v1/problems/example/solve
Content-Type: application/json
Authorization: Bearer <token>

{"expectedVersion":1,"requestId":"dispatch-2026-001","seed":42,"budgetMs":5000,"steps":1000}
```

Poll `GET /v1/solve-jobs/{uuid}`. A returned UUID is durable admission, not completion. Reusing the same tenant/request ID with the same problem/version/seed/budget/steps returns the original job; changed parameters return 409. At most 17 queued/running/cancel-requested jobs exist globally. Each service process has one CPU task; additional replicas increase potential CPU demand and must be budgeted separately.

After reviewing a terminal valid incumbent, publication remains explicit:

```http
POST /v1/plans/example/publish
Content-Type: application/json
Authorization: Bearer <token>

{"solveJobId":"00000000-0000-0000-0000-000000000001","expectedVersion":1,"expectedRevision":0}
```

Use the actual UUID and current revision. A 409 means the active input, plan revision, job state or input identity no longer permits that publication; load the current problem/plan and decide again. Never retry by silently replacing expected versions. The [API contract](../contracts/API.md) describes import, trace, baseline, cancellation and observed-prefix replan bodies. Required record fields must be present; null/fractional/scalar-coerced units and missing fields fail with 400.

## Failure and recovery

| Observation | Meaning | Supported action |
| --- | --- | --- |
| 400 `INVALID_INPUT` | Invalid shape, units, bounds or domain relationships | Correct the request; nothing should be inferred from a missing response |
| 401 / 403 | Invalid token, scope/tenant denial, or untrusted development browser origin | Correct authentication or origin; do not relax the profile |
| 429 `SOLVER_CAPACITY` | Global durable admission is full | Back off, inspect existing jobs, or cancel work intentionally |
| `CANCEL_REQUESTED` | A running generation has not acknowledged cancellation | Keep polling; cancellation does not publish or erase its incumbent |
| Process exits with `RUNNING` job | Search memory was lost; persisted progress remains | Restart matching source; an expired 15-second database-clock lease requeues with a new fence |
| Graceful shutdown | Current work is interrupted and fenced | Restart matching source; queued work resumes from durable state |
| `LEASE_HEARTBEAT_FAILURE` | Renewal failed and useful work stopped | Diagnose database/network availability; a failed terminal write falls back to lease recovery |
| `SOURCE_REVISION_MISMATCH` | Submitted and executing source differ | Drain before upgrading, or submit a new request for the current source; do not rewrite historical source labels |
| `BLOCKED_RESIDUAL` / failed frozen commitment | Observed/frozen state has a hard conflict | Correct observations or make an explicit audited replan; never bypass validation |
| 500 with `incident` | Unexpected server failure | Correlate that incident with protected service logs; the client receives no stack trace |
| Receipt or ownership refusal | Source/JAR bytes or process identity cannot be trusted | Inspect the exact files/process; rebuild after stopping the owned service; do not replace a live PID record |

For a real process-crash exercise, run `scripts/restart-check.sh` against a running disposable service. It waits for an incumbent, kills only the recorded process through a pidfd, restarts, waits for lease recovery, and asserts a higher fence, valid nondegraded same-input incumbent, exact demand partition and no automatic publication. This modifies disposable demo state and uses its own fixture identifier.

JDBC transactions set 10-second statement and five-second lock timeouts. The default pool has eight connections, a five-second acquisition/connect limit and 15-second PostgreSQL socket limit. HTTP has eight worker threads, 32 connections and a 16-entry accept queue. Bodies are bounded to 16 MiB before parsing. Cooperative search checks stop/deadline conditions between trials; an individual validation or JDBC operation is allowed to finish. These limits do not promise a hard 30-second wall-clock terminal time.

## Backup, schema and retention

Back up with PostgreSQL's supported `pg_dump`/restore tools using securely supplied credentials. The integration suite performs a real dump into a separate database and verifies input identity, plan/queue survival and immutable triggers. Restore into a separate database first, run schema initialization, validate representative plans, and reconcile job states before switching a service to it. Do not edit lease/fence columns to force a recovered result into a plan.

Startup schema changes are additive and serialized. New constraints verify lease/state shape, terminal timestamps, nonnegative revisions/generations and referenced input/candidate identity. Existing incompatible rows fail initialization; preserve a backup and diagnose the failing constraint rather than dropping it. Snapshots cannot be updated through ordinary SQL because of the immutable triggers. Database owners can override those controls, so restrict administrative access.

Published snapshots, job traces and audit records grow over time. Retention/export policy is an operator responsibility; there is no automatic deletion service. Preserve required audit and failed measurement evidence before any deliberate archival. Shutdown preserves the database volume and never prunes another project or a shared Docker cache.
