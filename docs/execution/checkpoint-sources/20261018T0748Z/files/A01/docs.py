from pathlib import Path
p=Path('projects/01-clearledger')
files={
'README.md':'''# ClearLedger

A synthetic marketplace accounting engine with a privileged PostgreSQL posting boundary, durable idempotency receipts, full reversals, bank-statement review and verified reporting replay. Moves no money; no payment network or regulatory-compliance claim.

Requires JDK25, the repository Maven wrapper, Docker Compose and Python3. From the repository root:

```bash
./mvnw -f projects/01-clearledger/pom.xml -B -ntp verify
./mvnw -f projects/01-clearledger/pom.xml -B -ntp -Pintegration verify
./scripts/portfolio.sh up clearledger
./scripts/portfolio.sh demo clearledger
./scripts/portfolio.sh down clearledger
projects/01-clearledger/scripts/benchmark.sh 100000 3
```

`demo` checks live HTTP results, kills a committed JVM writer, imports the same CSV twice, shows unresolved ambiguity, and verifies shadow reporting. `reset-demo.sh` explicitly resets only the named synthetic tenant; `down` preserves the database volume. The default API is127.0.0.1:18100 and database127.0.0.1:18101. Configure a protected issuer using `--ledger.issuer=...`; only `up.sh` explicitly selects the disposable `dev` JWT profile.

[Architecture](docs/ARCHITECTURE.md), [invariants](docs/INVARIANTS.md), [operations](docs/OPERATIONS.md), [security](docs/SECURITY.md), [OpenAPI](contracts/openapi.yaml), [measured results](docs/PERFORMANCE.md), [current gates](docs/STATUS.md), [walkthrough](docs/INTERVIEW.md).

Amounts use strict decimal strings at the boundary and BigInteger minor units internally. Input amount and cumulative debit/credit balances are each at most10^30−1 minor units; journals contain2–100 lines. CAD/USD use two decimal places; JPY uses zero, pinned by `fixtures/currency-scales-v1.csv`. PostgreSQL is the authoritative current ledger. Reporting exposes pending-event freshness.

Assisted-development provenance: implemented by coding agents under the owner's execution brief; no prior customers, deployments or employment history implied. Dependencies retain their upstream licenses. All fixtures are generated synthetic data.
''',
'docs/ARCHITECTURE.md':'''# Architecture

```mermaid
flowchart LR
  O[Verified OIDC principal] --> A[ledger-api: tenant and scope]
  A --> D[ledger-domain: strict amounts and journal intent]
  A --> S[ledger-persistence: serializable retry]
  S --> F[SECURITY DEFINER posting function]
  F --> J[(immutable journals and postings)]
  F --> B[(authoritative balances)]
  F --> R[(receipt and outbox)]
  R --> P[restartable Projector]
  P --> C[(per-event applied membership)]
  P --> V[(generation reporting balances)]
  J --> X[shadow replay and comparison]
  X --> V
```

The domain has no Spring/JDBC annotations or dependencies. The API authenticates issuer, signature, audience, expiry, tenant claim and required scope before using the tenant identifier. JDBC owns complete serializable transactions and bounded retries. It passes parameters to schema-qualified SQL functions; runtime cannot update/delete posted journals, postings, accounts or authoritative balances.

Storage relationships use composite `(tenant_id,id)` keys. Tenant→account/period; period→journal; journal→posting/receipt/outbox; import→row→match/decision. A match's candidate posting is consumed at most once. A reversal references its immutable original and is unique per original.

Each posting locks its period, then participating accounts sorted by UUID. Period closure updates the same period row. One period lock intentionally serializes writers inside that period; this conservative boundary favors reviewable correctness over maximal intra-tenant throughput. Retry includes every read and decision and has no network side effects. See ADR001.

Reporting uses `(generation,event_id)` membership rather than a sequence maximum. Each event's deltas and applied membership commit together. Shadow replay populates a new generation, verifies both-direction set equality against authoritative balances under an outbox table fence, then changes the active generation. New events can make promotion fail safely; rerun with a new generation. Prior generations remain available for inspection. See ADR002.

Statements sort `(effective_date,journal_id,line_no)` with keyset cursors. `snapshot` is PostgreSQL's transaction visibility snapshot, applied to each immutable journal's xid8 recording transaction. It excludes transactions that were in progress at first-page visibility, including later-committing lower IDs. Reuse snapshot, boundary and filters on subsequent pages. Recorded date is an additional explicit upper bound. No offset pagination or unsafe MAX journal watermark is used.
''',
'docs/INVARIANTS.md':'''# Accounting vocabulary and invariants

Debit and credit are sides. Asset cash is normally debit; revenue is normally credit. Posting CAD12.34 debit cash and CAD12.34 credit revenue gives cash signed debit-minus-credit+1234 and revenue−1234. Presented normal-side balance is `(debits−credits) × (normal_side==DEBIT ?1:−1)`, so both display12.34. A reversal swaps every side and brings their signed balances to zero while retaining both journals.

| Invariant | Enforcement | Regression evidence |
|---|---|---|
| Positive integral bounded line amounts,2–100 lines, one currency, equal debit/credit sums | Journal and privileged SQL function plus column checks | AccountingProperties; databaseMatchesIndependentGeneratedModelAndPrivileges |
| No partial journal/balance/receipt/outbox commit | Single SQL transaction/function | actualProcessTerminationBeforeAndAfterCommit |
| At most one result per tenant+operation+key; changed intent conflicts | Immutable digest/response receipt; unique key; serializable retry | sixtyFourDuplicateRequestsAndRacingReversals |
| Account currency immutable and same tenant | Runtime INSERT-only accounts; composite FKs; function validates currency | databaseMatchesIndependentGeneratedModelAndPrivileges |
| Full reversal exactly swaps source; one full reversal only | EXCEPT ALL multiset comparison, unique `(tenant,reverses)` | racing reversals |
| No posting into a closed or wrong-dated period | Locked period row and date boundaries | closeRacesWritersWithoutPostingIntoClosedPeriod |
| Runtime cannot bypass posting boundary | Explicit grants; hardened function search_path; no public function execute | actual runtime-role mutation attempts |
| Projection redelivery is idempotent; no missed late commit from sequence IDs | Durable applied-event membership committed with deltas | projectionHandlesEarlierAllocatedEventCommittedLater |
| Reporting promotion matches source | Shadow generation and two-way set comparison | replay and backupRestoreReauditsImmutableSource |
| Reconciliation does not invent matches or consume a posting twice | Exact reference/currency/signed amount, ambiguity state, unique candidate | reconciliationDistinguishesRowsAndPreservesDuplicateImport |

The independent reference computes debit/credit sums in memory without SQL or database functions. Currency fixture version1 is immutable for this release. DBA writes and host compromise are outside the integrity threat model.
''',
'docs/DECISIONS/001-transaction-boundary.md':'''# ADR001: one privileged posting boundary

Accepted: PostgreSQL owns posting invariants through a restricted SECURITY DEFINER function. The function fixes `search_path=pg_catalog,ledger`, uses explicit schema names, has no dynamic SQL, and revokes PUBLIC execution. The runtime login has SELECT plus narrowly granted operational writes; no direct journal/posting/balance mutations.

Rejected: an ordinary CHECK for cross-row equality (PostgreSQL cannot enforce a whole journal sum that way), ORM callbacks (other writers bypass them), and application-only serializability. Application transactions use SERIALIZABLE, period locking, stable UUID account lock order and complete bounded retries. The period lock is deliberately conservative and can become the first write scaling bottleneck.

A receipt digest covers normalized intent; its result, source, totals and outbox commit atomically. A timeout is ambiguous. Query the receipt or retry the same operation/key, never generate a new key merely because the first response was lost.
''',
'docs/DECISIONS/002-reporting-consistency.md':'''# ADR002: membership, generations and visibility

Accepted: reporting processes each durable outbox event at least once, atomically recording generation/event membership with its delta. Commit order is independent of sequence allocation. The regression test allocates an event in an open transaction, commits a later event in another tenant, projects it, then commits the earlier one and proves it is still applied.

Rejected: treating MAX(outbox.id) as an applied watermark. That design permanently loses a lower event that commits later. No real incident is claimed: this adversarial schedule motivated the design.

A replay writes a separate generation, compares all authoritative debit and credit totals bidirectionally under an outbox SHARE fence, and promotes only after equality. Live tables are never truncated by replay. A concurrent commit can cause a safe failed comparison and a new-generation retry. The API reports pending event count; authoritative statements never come from reporting.

Statement cursors carry a PostgreSQL xid8 visibility snapshot, avoiding the analogous backdated/later-commit pagination problem. Immutable source rows make this token usable without retaining MVCC row versions or a long-lived connection.
''',
'docs/OPERATIONS.md':'''# Operations

Run `scripts/up.sh`, `scripts/demo.sh`, `scripts/down.sh` from this project or use the root launcher. Scripts resolve their own paths, bound readiness waits, preserve failures, and isolate Compose project `java-portfolio-clearledger`. Only loopback18100/18101 are published. `target/api.log` and `target/api.pid` hold local process output/state; down verifies the PID command before stopping it and preserves volumes.

Health: GET `/health` reports adapter readiness. Database-affecting operations independently enforce timeout budgets; an UP adapter alone does not prove PostgreSQL readiness. Reporting: GET `/v1/reports/freshness` with `ledger.admin` reports pending events. Do not use reporting as the authoritative current balance.

Run the projector independently using `java -cp '<domain classes>:<persistence classes>:<runtime dependencies>/*' io.github.cheshmakzanoon.portfolio.clearledger.Projector`; set LEDGER_URL/LEDGER_PASSWORD. It can be terminated/restarted; every applied event/checkpoint is atomic. To rebuild, pass `replay`; a mismatch fails promotion and preserves the active generation.

Ambiguous timeout recovery: retain the same Idempotency-Key, query GET `/v1/requests/{key}` under the same tenant and operation, or resend the identical request.503 permits a bounded client backoff with the original key;422 requires input correction;409 means changed intent or an already reversed source.

Backup example: `docker compose -f infra/compose.yaml exec -T postgres pg_dump -U postgres -d clearledger --no-owner --no-privileges > target/backup.sql`. Restore into a new empty database using psql `-v ON_ERROR_STOP=1`; do not overwrite live tables. The integration suite restores a dump into a second clean PostgreSQL instance and rechecks balance/source equality and replay. Provision runtime role/grants through migrations when restoring for actual operation; `--no-privileges` dumps intentionally omit grants.

Audit: group ledger.posting by `(tenant_id,journal_id)` and require count≥2 and signed sum zero; recompute grouped account debit/credit sums and compare with ledger.account_balance. Investigate any mismatch before accepting further writes. Restrict DBA credentials to administration. The demo admin and runtime passwords are explicitly disposable local fixtures; protected deployments must inject different secrets via environment settings.

`reset-demo.sh` deliberately removes only fixture tenant00000000-0000-0000-0000-000000000101 and dependents, then reseeds it. Benchmark scripts replace only explicitly named `clearledger_benchmark_rN_wN` databases. No global prune or destructive live reporting replay exists.
''',
'docs/SECURITY.md':'''# Security and trust boundaries

Protected mode uses actual OIDC discovery and JWKS RSA signature validation, issuer, audience `clearledger`, required expiry, subject and UUID tenant claim. Spring Security verifies operation scopes: ledger.read, ledger.write, ledger.reconcile and ledger.admin. The verified principal supplies tenant identity; DTOs have no tenant field. SQL always binds tenant identifiers and uses composite foreign keys.

The local OIDC integration fixture serves discovery and public JWKS over loopback and signs real RSA tokens. It exercises wrong signature, issuer, audience, expiry, tenant isolation and scopes. Two HTTP application instances share PostgreSQL and preserve a common idempotency receipt.

`up.sh` explicitly activates `dev`: a known disposable HS256 key and loopback-only issuer enable a repeatable local demo. The application rejects a non-loopback server.address in that profile. Do not expose the development profile through a public proxy. Protected mode is the default and requires reachable trusted issuer configuration; real credentials never belong in Git.

The database runtime role is a trusted application capability, not a login exposed to end users. It can SELECT tenant data for the application and manage reconciliation/projections, but cannot modify immutable posted source or authoritative balances. Cross-tenant isolation is enforced in verified adapter identity and parameterized repository predicates; DBA/host compromise and direct misuse of an application credential are outside tenant-user threat model. Privileged functions have fixed schema resolution and restricted grants.

The adapter bounds actual POST bytes to1MiB, including chunked bodies; journal lines≤100, statement page≤200, CSV rows≤5000 and decimal text≤64 characters. Terminal errors produce structured codes, not stack traces. CSV import records rejection reasons; export prefixes spreadsheet-trigger characters and quotes fields. Names and references use bound SQL, never query concatenation.

No card, bank-network or personal data is processed. All accounting data is synthetic. This is not a security certification or regulatory product.
''',
'docs/PERFORMANCE.md':'''# Performance methodology

The prescribed generator creates100000 journals across20 tenants and10000 accounts with seed20261017, CAD/USD/JPY, 20% four-line journals, duplicate retries every100 inputs and reversals every1000. It computes expected balances with the independent reference before invoking SQL and writes `target/benchmark/rN-wN/expected-balances.csv`.

Run `scripts/benchmark.sh 100000 3` on an otherwise idle host. It builds first, then runs1/8/32 bounded Hikari writer pools in three fresh-database rounds. Raw latency CSVs and result envelopes live under target/benchmark. `scripts/benchmark.sh 1000 1` is a smoke run and cannot satisfy the full workload gate.

Measured boundary: closed-loop JDBC transactions including retry and duplicate/reversal work associated with each input. Results report throughput, p50/p95/p99, elapsed time, retry count, sampled concurrent lock waiters, reporting backlog/replay duration, sampled peak Java heap, JVM flags and runtime, CPU, cores, cgroup limit and relevant database durability settings. The database and clients share the host; the API network path is excluded. There is no separated warmup because writes use a fresh durable dataset. Closed-loop latency underestimates open-loop overload queue delay. Lock waiter counts are sampled observations, not aggregate wait-duration measurements; sampled Java heap is not process RSS. Do not infer production capacity.

See STATUS and committed evidence for exactly which measurements were actually run. The first scaling bottleneck is the deliberate per-period lock; reducing it requires a new close/post proof rather than removing the fence for a prettier number.
''',
'docs/CASE_STUDY.md':'''# Case study: recoverable accounting without a false watermark

The hard requirement is a single auditable accounting result even when a client does not know whether commit happened. A privileged SQL boundary atomically inserts journal/postings, updates authoritative totals, preserves a canonical request receipt and writes an outbox event. The adapter retries the complete serializable transaction and never puts network side effects inside it.

The rejected reporting design used the greatest processed sequence ID as a checkpoint. Sequence allocation precedes transaction commit, so a slow transaction can receive ID1, another commit ID2 and advance that checkpoint, then the original commit becomes permanently invisible. No production incident is claimed. The adversarial delayed-commit integration test demonstrates this schedule and motivated durable `(generation,event)` membership.

The chosen design makes event application and membership one transaction; a restarted projector scans for missing memberships. Replay uses a separate generation and verifies source totals before promotion. The same commit-order reasoning prompted transaction-visibility tokens for keyset statements rather than pretending recording time alone freezes a multi-page statement.

Regression evidence includes a real JVM killed before commit and a second killed after commit,64 duplicate submissions, racing reversals/closure, exact reconciliation statuses and a clean-instance dump/restore audit. Consult STATUS for observed passes and remaining gates; source presence is not a passed acceptance item.
''',
'docs/INTERVIEW.md':'''# Five-minute demo and technical walkthrough

Run `scripts/demo.sh`. Minute0–1: balanced post, unbalanced rejection and repeated receipt. Minute1–2: kill committed JVM writer and recover the original receipt. Minute2–3: full reversal and preserved source. Minute3–4: import the same CSV twice and inspect visible ambiguity/invalid/duplicate rows. Minute4–5: shadow replay, freshness and zero-imbalance audit.

For a10–15 minute walkthrough:

1. Trace Jwt principal→DTO amount validation→canonical intent→serializable transaction→privileged function→receipt/outbox commit.
2. Explain why debit/credit differ from increase/decrease, and calculate cash/revenue display balances and reversal.
3. Read post_journal's period lock, sorted account locks, exact multiset reversal check and grants. What does DBA exclusion mean?
4. Read the64-request race and crash barrier tests. Why does an HTTP timeout not authorize a new key? Where does complete retry restart?
5. Run the delayed event-ID commit schedule. Why does MAX(id) fail? How do membership and shadow generations survive restarts?
6. Inspect the reconciliation ambiguity fixture. Why is amount-only matching forbidden? Show the actor/reason/evidence decision record and candidate uniqueness.
7. Inspect raw benchmark percentiles, retry/lock observations and generated expected file. Use EXPLAIN on the statement's tenant/date/id keyset join; discuss index order and the per-period fence bottleneck.
8. Make a small live change to a fixture amount, predict the reference sum, run the relevant property/database test and explain any rejected bound.

The project is agent-assisted synthetic portfolio work, not a claim of production money movement or prior employment.
''',
'docs/REVIEW.md':'''# Independent review

Pending the coordinator's A10→A01 review. Owner must address reported findings and link exact rerun evidence here. No independent approval is claimed by this placeholder.
''',
'docs/STATUS.md':'''# A01 ClearLedger status

Project:01-clearledger. Foundation checkpoint:d6e0c2b6de4a9681903cbf4b0c70d540c9ee5d6a. Owned paths:projects/01-clearledger/** and.work/A01/**. No worker Git writes.

M1 domain and architecture:implemented. M2 persistence and proof:implemented, integration validation running. M3 API and authorization:implemented, protected local OIDC and multi-instance integration validation running. M4 recovery/reconciliation/replay:implemented, validation running. M5 full workload and release evidence:IN_PROGRESS.

| Acceptance | Current status | Evidence command/location |
|---|---|---|
| P01-A1 | IMPLEMENTED_UNVERIFIED | project verify/-Pintegration verify; domain target/site/jacoco; LedgerPostgresIT |
| P01-A2 | IMPLEMENTED_UNVERIFIED | 64 duplicate requests, reversal/closure races, subprocess termination, protected OIDC/two API instances |
| P01-A3 | IMPLEMENTED_UNVERIFIED | reconciliation fixtures/duplicate import/manual audit/export tests |
| P01-A4 | IMPLEMENTED_UNVERIFIED | delayed event commit, shadow replay, pg_dump restore into clean PostgreSQL |
| P01-A5 | IN_PROGRESS | demo and workload scripts; actual results not yet recorded |

Commands: source scripts/cloud-env.sh; ./mvnw -f projects/01-clearledger/pom.xml -Dmaven.repo.local=/workspace/Java-portfolio/.work/A01/m2 -B -ntp com.diffplug.spotless:spotless-maven-plugin:3.0.0:apply -Pintegration verify. Logs:.work/A01/integration.log (local, not committed). First attempts found/fixed Java import ambiguity and Testcontainers Transferable package; ongoing snapshot/pool changes require final complete rerun. Earlier unit run observed domain properties and strict decimal tests passing; do not treat that earlier tree as final acceptance.

Next:resolve real-PG failures, finish asserted demo, measure workload alone, run targeted mutation, request independent review, record exact source fingerprints and raw evidence. Full100k×1/8/32×3 and independent review remain unclosed until observed. No fabricated metrics or waived gates.
'''
}
for name,text in files.items():(p/name).write_text(text)
