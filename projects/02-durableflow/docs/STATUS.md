# DurableFlow status

**P02-A5 remains OPEN:** the three full quiet 10,000-execution/eight-activity workloads, measured variability and numeric reference-machine budget have not run. The standalone lifecycle, both fault-demo rounds and 100-execution smoke now pass. No project release claim is made. No worker Git writes were performed.

Source is frozen at `sha256:104e648ae642e84a02ce3031ac7439e4a71f20767285404b87a96b4019254187`; root POM `3743d1ba382977b3f8d8609e1191190f220cdf272a5338d538869f96a7b7230d`. [Current manifest](../evidence/source-manifest.json) lists 59 entries. All 19 implementation main-source/resource entries remain byte-identical to the independently reviewed semantics 3 milestone at a8a4692a. Actual Jupiter/platform is 6.1.3; historical 6.0.3 runs are not relabeled.

## Automated correctness evidence

[Verification envelope](../evidence/verification.json), [environment](../evidence/verification-environment.json) and [full log](../evidence/verification-build.log) record the latest full `-Pintegration verify`: PASS, 2026-10-18T02:40:51Z,124 seconds; **68 ordinary methods plus 13 actual PostgreSQL methods**, zero failures/errors/skips. PostgreSQL is pinned to the same18.3 digest as Compose, `sha256:54451ecb8ab38c24c3ec123f2fd501303a3a1856a5c66e98cecf2460d5e1e9d7`. Reports remain in `.work/A02/final-pinned-reports/` and raw `.work/A02/final-pinned-integration.log`.

Core coverage is model94.20% line/88.70% branch and engine96.34% line/84.76% branch, above unchanged85% line/75% branch checks. Selected Data* mutation ran on a8a4692a at02:03:16Z:41 generated/41 KILLED, zero survivors/timeouts/uncovered;87/88 mutated lines covered. The selected implementation and mutation configuration are unchanged in the current source. This is explicit prior-source proof for the identical selected target, not a claim that PIT reran globally on104e. [All mutants](../evidence/mutations.xml), [log](../evidence/mutation-build.log) and `.work/A02/semantics3-mutation-reports/` retain every result. It is not an engine-wide mutation claim.

The suite covers genuine typed scalar/schema/vector propagation; wrong-type/wrong-length atomic rejection; actual provider predecessor parameters; deterministic recorded results; timers, timeout/skipped/branch/join outcomes; explicit version CAS and killed-before-commit rollback; duplicate delivery; killed workers after independent provider effects; stale dispatch/heartbeat/completion; uncertainty and audited resolution; cancellation/late completion/reverse compensation/failure/retry; signal/timer races and5,000 dormant timers. Fixed-seed reference/property suites run for supported interpreters. Streaming provider recovery verifies one retained receipt from a40MiB log in a32MiB child JVM. Original signed JWT claim-type/missing-claim/time checks, scope, chunked8KiB bounds, RFC9457 and typed HTTP flow pass. OpenAPI route/record/ref drift is checked.

## Compatibility and review

Semantics3 records a bounded typed INTEGER forward-lease observation under the instance lock. Replay rejects terminal FAILED→CANCELLED while preserving cancellation with another leased forward task, late result and compensation. Frozen1/2 interpreters retain their original pins, hashes, histories and worker parameters. [ADR003](DECISIONS/003-cancellation-evidence.md) documents the explicit event/version boundary and legacy ambiguity. Actual PostgreSQL verifies persisted empty-result2 cancellation across deploy3; the3 equivalent records lease count1. Terminal rejection leaves version/history unchanged. Frozen2 normalizes byte-for-byte to the prior interpreter hash `6f741ef514566f4617941b8b4d2df882d3b20d865d207639d92ed2ff6a055888`.

Expanded A01 source/correctness review CLOSED against a8a4692a; prepared-cache review CLOSED against104e after independent recomputation and comparison of all19 main files. Prior repeated non-idempotent dispatch, non-activity compensation, original signed JWT and terminal cancellation findings have tested scoped closure. [REVIEW](REVIEW.md) records fingerprints and limits. The fresh13-PG run now validates the final same-version digest pin; no implementation changed after review.

## Actual standalone evidence

[Lifecycle envelope](../evidence/lifecycle/envelope.json), [step exits](../evidence/lifecycle/steps.jsonl), both demo JSONs, independent provider assertions and raw command logs record **14/14 command steps passing** from02:41:04Z through02:50:06Z. Raw capture `.work/A02/lifecycle-20261018T024104/` remains intact.

- Fresh reset/up passed; repeated up preserved all7 live API/provider/two-scheduler/three-worker process identities.
- Both real fault demos passed,91 and98 seconds. Each retained both genuine31-second waits after actual30-second worker leases. Each independently logged exactly one idempotent reserve effect despite two dispatches, one non-idempotent charge invocation/effect with visible uncertainty and audited resolution, and four rejected compensation calls with zero compensation effects. Each media workflow completed. Recovery-delay fields measure worker kill to recovered order completion, including scheduler restart/resume work.
- First down and idle checks passed. Exactly104 instances survived prepared stop/start; real volume reset followed by startup yielded exactly0 instances. Second demo then passed on clean data.
- Final down, cleanup and independent idle checks passed. No A02 runtime/application container or build remains active; the application slot was released at02:50:06Z. Adopted zombie PIDs explain the measured66-second first and42-second final down latency; they have empty command lines and do not execute.

[Smoke envelope](../evidence/smoke-100/envelope.json) records100 executions/eight activities using2schedulers/8workers:800 completed activities and independently logged unique effects,160 transient first-attempt rejections,100 accepted signals,50 timers, zero lease expirations and empty final queue. Driver elapsed33.877 seconds; command59 seconds. Independent reparse confirms960CALLs/800uniqueEFFECTs/160transient-first CALLs across100instances. This shared finite run is **smoke only**, never a full baseline or budget.

Raw smoke JSON/environment/resources and plain synthetic provider evidence are Git-visible text. Original17MiB JFR/GC/allocation capture remains at `target/performance/20261018T024329-100-309440/`; compressed copies remain ignored under `.work/A02/`. The small envelope publishes exact paths/hashes/sizes. JFR is statistical worker1 allocation evidence; GC logs cover process lifetime, including startup and the preceding demo for this smoke. No whole-cluster exact allocation claim is made.

## Prepared quiet protocol and remaining work

[Preparation evidence](../evidence/preparation-verification.json) records successful ordinary68-method verification and9 internal CLI refusal cases. Preparation refuses any owned live executable process before Maven and stamping, including missing PID files, then binds unchanged source/root POM, six module JARs, two executable JARs and successful verification log. Explicit `DURABLEFLOW_PREPARED=1` validates before Compose/workers and performs no Maven. Default startup and explicit ordinary/integration commands always execute their real tests. Current receipt still validates after final integration and runtime lifecycle.

The remaining mandatory measurement must receive an explicitly quiet host reservation, with all competing builds/stress/services stopped. Do not infer authorization from an empty docker ps. `.work/A02/FULL_RUN_PROTOCOL.md` specifies the exact no-Maven protocol and raw provider-log preservation before reset. Existing preparation is ready; never invoke prepare while the coordinator holds all build locks.

For each of three independent full runs, reset data, start prepared services, run the full benchmark and stop. Assert10,000completed executions,80,000activities/unique provider effects,16,000first-attempt rejections,10,000signals,5,000business timers and empty queue. Preserve result/environment/RSS/CPU/GC/JFR, stdout/exit and provider CALL/EFFECT logs before the next reset. Run the actual three-run summarizer, report variability and establish a same-environment25%-above-slowest reference tolerance only when declaredCV<=10% holds. No numeric budget exists yet. Regenerate source identity and rerun affected gates only if an actual defect requires source changes; obtain review for changed invariants.

```bash
source scripts/cloud-env.sh
export DURABLEFLOW_PREPARED=1
./scripts/portfolio.sh reset-demo durableflow
./scripts/portfolio.sh up durableflow
projects/02-durableflow/scripts/benchmark.sh
./scripts/portfolio.sh down durableflow
# Repeat the complete reset/up/full/down sequence three times; preserve each raw capture.
projects/02-durableflow/scripts/summarize-performance.py RUN1 RUN2 RUN3
```

## Retained development failures

Historical initial integration ran7 actual-PG methods with1failure/2errors under one-second active leases and severe build contention. Tests now use60-second active claims plus explicit database expiry injection; live demos retained real30-second expiry. Early typed builds failed unchanged coverage thresholds before focused Data tests and rejected an invalid single-input parallel fixture, now corrected. First typed PG passed11/12 and exposed bearer-filter problem-response drift; explicit bearer/Basic RFC9457 handlers fixed it without weakened assertions. Invalid old cancellation fixtures and later terminal FAILED replay mismatch received legal histories and explicit semantics3 compatibility respectively. Queue-canceled invocations were scheduling changes, not test passes. Raw historical logs/reports remain in `.work/A02/`.

A02→A03 read-only reviews closed snapshot-transfer, unknown-mutation-identity and checker-state findings with tested owner fingerprints; the detached-process follow-up found no new serious issue in bounded zombie/ownership/independent-controller recovery. No other project was written.
