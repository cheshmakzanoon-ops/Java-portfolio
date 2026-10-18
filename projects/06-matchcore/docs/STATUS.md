# MatchCore execution status

The implementation, correctness/fault gates, exact demonstration and full isolated reference study have passed in this cloud instance. **Clean-checkout and repository-wide release validation remain coordinator work.** No remote-CI or complete repository release claim follows from these results.

Executable/build-input source fingerprint is `62fc0b0351864486ffa736bd64f444393e3dda894f93677c7ca95448dab1ce83`. Java/POM/scripts/fixtures remain frozen; this handoff changes only analysis evidence and documentation. Workers perform no Git writes.

| Acceptance | Current-instance result | Evidence |
| --- | --- | --- |
| P06-A1 | PASS | Independent flat-list/BigInteger oracle agrees across 120 histories of 350 transitions; three-seed live-workload targeting/tracker assertions also pass. |
| P06-A2 | PASS | Additional 12,000 invariant transitions, FIFO/replacement/FOK/STP/risk/maximum-supply cases; core coverage 260/263 lines and 178/190 branches clears unchanged 85%/75% gates. |
| P06-A3 | PASS | Separate-process SIGKILL before append, during frame, after force and after apply; recovered durable-prefix and stable retry assertions. |
| P06-A4 | PASS | Every-byte torn-tail/corruption tests, snapshot fallback, writer lock, consistent barriers, bounded admission and protected JWT/HTTP overload negatives. |
| P06-A5 | PASS in current instance; clean reproduction pending | Two exact lifecycle/demo/reset rounds and three independent million-command runs per mode, matching all digests/histograms; actual three-fork JMH and resource/profiling evidence. |

## Verification and artifact integrity

Full `-Pintegration,mutation verify` passed at 02:19:44 UTC on 2026-10-18: **21 test methods, zero failures/errors/skips**; Book mutations **119 killed / 133 total**, with 14 survivors and zero uncovered/timeouts/runner errors. Core coverage and exact command/log hash are in [prebaseline verification](../evidence/prebaseline-verification.json). The earlier 19-method `verification-current.json` remains historical, not a substitute for this later verification. [All 14 survivors](../evidence/mutation-survivors.json) stay visible and in the denominator; equivalence is not assumed.

Subsequent changes affected capture/cache scripts and fixture prose only. The coordinator's latest ordinary verification passed 20 methods with zero failures/errors/skips. Seventeen actual artifact tamper/restoration/classpath probes passed: the prepared cache binds compiled classes/resources, runtime classpath and dependency JARs, rejects symbolic links and refuses stale artifacts before Maven. [Runtime cache evidence](../evidence/runtime-artifact-cache.json) identifies 67 bound artifacts. No production Java changed after the full 21-method integration/mutation run.

Revalidation command used for the full affected Java scope:

```bash
source scripts/cloud-env.sh
./mvnw -f projects/06-matchcore/pom.xml \
  -Dmaven.repo.local=/workspace/Java-portfolio/.work/A06/m2 -B -ntp \
  com.diffplug.spotless:spotless-maven-plugin:3.0.0:apply -Pintegration,mutation verify
```

A05's scoped correctness/security review is recorded in [REVIEW.md](REVIEW.md). Its subsequent artifact-cache finding was fixed by the coordinator and exercised through the 17 actual probes above. **Final measured-artifact review is closed with no blocking finding.** A05 independently confirmed the reviewed ten-file packet fingerprint `7f6e9002ce30dc765d3b1081aa9f470c0c6760b1382ca908d62f8a62dcbc1a48`, all 83 raw-file hashes/sizes, 67 actual runtime-artifact hashes, exact results and complete JFR/GC/JMH summaries. Its approval covers measurement lineage, numbers and stated limits; clean/global CI/release gates remain separate. Subsequent changes are closure prose and documented CRLF-to-LF serialization of the derivative CSV; [both CSV identities](../evidence/reference-20261018/README.md) are retained, with all values and raw measurements unchanged.

## Demonstration and full study

Both catalog rounds ran `up`, `demo`, `reset-demo` and `down` successfully. Each demo recovered the interrupted durable cancellation and asserted four executions, eight lots, Alice cash 19,200 / lots eight, zero open orders and 12 unique command results. See [demo evidence](../evidence/demo-current.json).

The coordinator's quiet reservation held all three build locks and observed no unrelated active JVMs/application containers at reservation or completion. Every launch recorded zero other live Java processes. From 02:54:16.349894 to 03:28:07.482351 UTC, `bash .work/A06/run-reference-window.sh` completed all six one-million-command runs and three-fork JMH, exiting zero after 2031.133 seconds. Source/runtime cache checks were already prepared; no Maven build ran during measurement.

Median core throughput was 113,277 commands/s (range 112,881–171,278); median durable throughput was 1,706 commands/s (range 1,558–1,790). Durable runs each forced one million measured commands, plus 64 setup initializations. All six produced digest `37574212b19a77ee68564470ad68f1b4488e112a8b85886a3e4443e6ebfff6f5` and identical complete business histograms. Actual book peak was **27**, versus the configured 100,000 cap. Recovery ranged 10.944–25.456 seconds in a warm JVM/hot OS cache. These are distinct memory-only/durable, closed-loop boundaries rather than HTTP/deep-book/overload claims.

[PERFORMANCE.md](PERFORMANCE.md) provides every repetition, allocation/heap/GC/CPU/JFR findings, JMH confidence interval, exact workload, variability and limits. Committed raw result copies and the hashed envelope are under [reference-20261018](../evidence/reference-20261018/measurement-envelope.json). All 83 indexed raw files passed independent size/SHA-256 checks; all six JFR event summaries were independently recomputed and matched. Large original recordings, journals and logs are preserved in the verified local archive at `.work/A06/archive/reference.2lkNWp.0n0jw_63/reference.2lkNWp.tar.gz` (165,957,826 bytes; SHA-256 `4a12347b29bad6e7ffe4ec149ab1c783bc9dbebde23a70fca0025bceded4f361`). After independent physical recovery PASS and immediate complete revalidation, the exact original batch was removed with coordinator authorization. The [full manifest, recovery/removal receipts and restore instructions](../evidence/reference-20261018/README.md#restore-the-original-raw-files) preserve all 98 files and 15 directories; historical raw locators require restoration. The local archive is not bundled with a Git clone. Observed free-space increase was 1,588,310,016 bytes (shared-workspace observation); original removed-path allocation was 1,588,314,112 bytes. No measurement or reviewed original identity changed.

## Remaining release work

- Coordinator: clean-checkout reproduction, independent/root and repository-wide gates, hosted CI verification, final commit/push and release evidence.
- Keep production source frozen unless an actual defect requires an explicitly resumed fix; do not rerun or cherry-pick measurements to hide variability.

Historical failed/smoke captures remain dated and ineligible as baselines. Local POSIX storage honoring force, one writer and a bounded finite run are deliberate boundaries; disk-loss protection, replicated availability and physical power-loss guarantees are excluded.
