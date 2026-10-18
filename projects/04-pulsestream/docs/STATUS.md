# PulseStream execution status

A04 owns this project and `.work/A04`; coordinator owns Git/shared files. M1–M4 have current full integration correctness evidence. M5 prescribed isolated performance and final release review remain **IN_PROGRESS**. No global exactly-once or distributed execution claim is made.

| Acceptance | Status | Observed evidence and remaining boundary |
|---|---|---|
| P04-A1 original tumbling/sliding algorithms | PASS (bounded tests) | `evidence/unit-current.json`:15 methods,120 boundary histories(seed4404)+80 independent source/control histories(seed4411), all JUnit6.1.3. Original pane engine versus independent membership/watermark oracles. |
| P04-A2 watermark/finality/idle/restart | PASS (bounded tests) | Exact end+30s finality, pane retention, per-record finality within atomic batch, pause/backlog/all-idle/resume/restart. Actual demo recovery digest matches oracle. |
| P04-A3 atomicity/crash/fencing | PASS (bounded current tests) | `evidence/integration-current-eight.json`:8realIT passed on current optimized engine/startup with process kills before SQL, after SQL/before mirror, during outbox; real lease expiry/fencing and unstarted consumer cleanup. |
| P04-A4 dedup/quotas/outbox/retention | PASS (bounded current tests) | Full8IT passed quotas, retry/conflict, unavailable DB, retention gap, slow delivery lease, actual topic delete/recreate identity and partition-growth startup rejection. |
| P04-A5 demo/workload | IN_PROGRESS | Corrected actual up/demo/reset/down passed; named-volume recreation preserves original topic identity, checkpoints and digest.10k diagnostic smoke passed. Prescribed1m/8partitions/10kkeys ×3 quiet runs and3fork JMH remain unexecuted. |

Latest format/preparation: `.work/A04/processor-optimization-prepare.log`, exit0 at2026-10-18T02:39:53Z,15unit methods and generated JMH BenchmarkList/classpath. Latest full integration: `.work/A04/current-eight-integration-digest-compatible.log`, exit0 at2026-10-18T02:45:47Z,15unit/property+8realIT=23methods,0failure/error/skip on JUnit6.1.3. Exact report copies, hashes and tested Java/SQL/properties/POM inputs are preserved in `evidence/unit-current.json` and `evidence/integration-current-eight.json`. Earlier13/14method envelopes remain separately preserved. Core coverage is recorded in unit-current; the active85%line/75%branch gate passed with no exclusions.

The initial current8IT attempt failed before container startup because Testcontainers2.0.5's compatibility check rejected tag@digest references.15units passed,0ITmethods executed,1setup error; raw report/log and cause remain in `evidence/integration-digest-compatibility-failure.json`. Supported DockerImageName same-image compatibility declarations retained the exact immutable digests. The entire suite then passed; no test was skipped or weakened. The older root7IT and preceding failed reports remain preserved as historical evidence.

Actual lifecycle source `working-tree-sha256:2f3eb8b945c5e2ecbbacb19987d661e438aad50b656f6fd5af8df14c38651d30`:

- Cache-hit real startup and HTTP18400 health passed. Coordinator captured A01+A04 actual service coexistence in root `integration/coexistence/evidence.json`.
- First actual demo passed before storage correction; retained as `evidence/demo-before-storage-fix.json` and transcript. The correction fixes a real Kafka image default writing outside the original named-volume mount.
- Offline migration copied original broker log data into its existing project named volume. Compose now explicitly uses `/var/lib/kafka/data`, matching the named-volume destination. Both actual Kafka and PG containers were recreated. Original topic UUID `c16zn4DtS8GeyvY7fBD_Rg`, nonzero SQL next offsets3/7 and complete demo digest were retained. `evidence/container-recreation.json` records actual before/after IDs, mount and independent ReplayWorker verification. This proves container recreation, not physical power-loss durability.
- Both Compose and Testcontainers pin the already-tested immutable Kafka4.2.1 and PostgreSQL18.3-alpine image digests; `evidence/container-images.json` records observed RepoDigests.
- Corrected real demo exit0 at02:28:49Z: first window count3/sum35/final, duplicate1, rejected late tumbling contribution1, explicit sliding outcomes, crash/recovery/outbox retry; digest `0050e36db52d262196924b3b278eb13e549e8cfd6cacca8b3d8e96d6d423eff6` equals oracle. `evidence/demo.json` includes7shell/API barriers; additional worker assertions run but are not counted as separate methods.
- `benchmark.sh smoke` exit0 at02:31:52Z:8partitions,100keys,10000measurements+10duplicates; independent200published-window oracle and complete recovered-state digest match. Raw JFR/GC/env/result are `.work/A04/runtime/durable-smoke-1792290676285088`; envelope `evidence/smoke-before-optimization.json`. Shared-host diagnostic only:861.06accepted/s,11.61s drain,12checkpoints,p50/p95/p99=427.57/3223.88/3223.88ms. It is not a prescribed full or isolated baseline.
- Scoped reset exit0 removed only new demo run, retained older migrated demo+live; down exit0 stopped own API/PG/Kafka. `evidence/lifecycle.json` records exact command exits. Workload database/topic and named volumes remain intentionally retained.

Scripts reject stale source/artifact/running-process fingerprints, record artifact hashes after real verified builds and bound owned child lifetimes. Diagnostic JFR inclusive samples found repeated Engine.advance work; source inspection confirmed full pending/pane scans even when watermark was unchanged. The engine now skips unchanged-watermark scans and publishes newly introduced already-due late windows directly. A new regression proves first provisional revision, late correction, finalization and no reopening. Explicit JMH processor configuration generates real BenchmarkList metadata on Java25; preparation rejects its absence. Those changes passed15unit/property methods at02:39:53Z in `.work/A04/processor-optimization-prepare.log`; fresh8IT subsequently passed with same-image digest compatibility declarations at source `working-tree-sha256:08c1eab6bc4ff1a37d4ccfef6916393b9782622ebd2e93ee57e9b5b312ec1401`. After that full pass, only the first-late-window test constants changed from70000 to the exact60000 watermark boundary. The ten core methods and strict PIT passed again at02:50:25Z; production Java/SQL/POM/configuration inputs are byte-identical to full8IT (verified against its input hash manifest). Final frozen fingerprint is `working-tree-sha256:1dcc6557f80d9025111654ae5041d99c2bd6321752a6caa39db207103838fb99`. Quantitative optimization benefit remains unmeasured. Original compiled Engine classes/source/hash are archived in `.work/A04/pre-optimization-engine` for an honest controlled comparison if profiling justifies a change.

Mutation: original113strictkills/128outcomes included1memory error and1timeout; strict gate failed, preserved `.work/coordinator/pulsestream-first-pit` and `evidence/coordinator-first-mutation.json`. Fixed six-slot assignment allocation removed the memory failure without reducing scope. Next actual report115/129strictkills(89.15%),13survivors,1penalizedtimeout,0runner/memory errors is retained `.work/A04/pit-baseline-115`, envelope `evidence/mutation-baseline-115.json`. Later optimized report130/133strictkills(97.74%),2survivors,1penalizedtimeout is retained separately. The exact first-late-window watermark=end regression killed that new boundary survivor. Final actual PIT:131/133strictkills(98.50%),1survivor,1penalizedtimeout,0runner/memory errors; Maven and strict outcome checker both exit0 at02:50:25Z. Reports `.work/A04/pit-exact-edge-131`, log `.work/A04/exact-edge-mutation.log`, envelope `evidence/mutation-current.json`. The remaining future-time conditional-boundary survivor remains penalized; no equivalent-mutant denominator adjustment.

Remaining work:

1. Diagnostic JFR and explicit managed JMH processor preparation completed after ByteGuard quiet ended. Generated BenchmarkList and full reactor classpath exist;15unit methods passed. No Java/build runs may overlap a later coordinator quiet hold.
2. Current full8IT and final core PIT/strict checker passed; Testcontainers/API/Compose stopped. `.work/A04/QUIET_READY.json` records prepared artifacts, exact test-only delta and frozen fingerprint. `.work/A04/PERFORMANCE_HANDOFF.md` supplies actual no-Maven commands and preserved original-engine baseline runner. Do not restart application/Compose until scheduling grants it.
3. Obtain explicit quiet host; stop ordinary live API, retain only required PG/Kafka. Run3fork engine JMH and three fresh-database full1m/8partition/10kkey durable runs, seed4404. Capture full JFR/GC,CPU/allocation,checkpoint p50/95/99,heap,SQLgrowth/recovery/environment/raw. Full driver asserts independent interval oracle and full recovered state digest.
4. Optimize only measured bottlenecks; preserve original baseline/classes and actual comparisons. Current per-record pending-window scan, full dedup/state copy and SQL pane replacement are architectural costs, not yet measured full-workload conclusions. Do not reduce workload or weaken checks.
5. Correctness gates are current at the explicit full-suite/test-only boundary above. After any further production optimization run affected fresh verification/PIT; complete readable performance/case-study/invariants docs, current-source asserted demo if needed, A03/root frozen review and coordinator release. Acceptance stays in progress until all required measurements/gates pass.

Commands from repository root:

```bash
source scripts/cloud-env.sh
./mvnw -f projects/04-pulsestream/pom.xml -Dmaven.repo.local=/workspace/Java-portfolio/.work/A04/m2 -B -ntp com.diffplug.spotless:spotless-maven-plugin:3.0.0:apply verify
./mvnw -f projects/04-pulsestream/pom.xml -Dmaven.repo.local=/workspace/Java-portfolio/.work/A04/m2 -B -ntp -Pintegration verify
./mvnw -f projects/04-pulsestream/engine/pom.xml -Dmaven.repo.local=/workspace/Java-portfolio/.work/A04/m2 -B -ntp -Pmutation verify
python3 scripts/check-mutations.py projects/04-pulsestream/engine
projects/04-pulsestream/scripts/prepare-benchmark.sh
# Only after the coordinator grants a quiet host:
projects/04-pulsestream/scripts/benchmark-engine.sh
projects/04-pulsestream/scripts/benchmark.sh full
projects/04-pulsestream/scripts/benchmark.sh full
projects/04-pulsestream/scripts/benchmark.sh full
```

Use ordinary FIFO wrapper, one A04 Maven process, coordinator Docker/quiet scheduling. Same-version SQL snapshot restoration is supported; rolling snapshot migration is outside scope.

Peer review: A03's independent watermark oracle/strict duplicate JSON/poisoned session findings were fixed and covered by prior gates. A04→A05 scoped final read-only review recomputed `4bd439735ed2a4399ba8c0f9d7abfa3d28dcbec9bec81130d6472dfa27ea124d`: prior audience/subject/atomic rollback findings closed against27current methods(all6.1.3) and21assertion real demo. This does not approve unexecuted A05 performance/release gates.
