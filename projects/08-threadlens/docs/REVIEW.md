# Independent review

A07 performed a read-only review of agent transformation/handler precedence, safe resource-only frame resolution, bounded aggregation/JFR normalization, escaped reports and timing import.

| ID | Finding | Owner correction | Independent verification |
| --- | --- | --- | --- |
| A08-R1 HIGH | Shutdown could abandon already accepted timing entries without reporting loss; importer could omit tail loss | Bounded drain, explicit shutdownDiscarded/incomplete/health and unknown-status propagation | A07 inspected fix and74passing Surefire tests at working-tree fingerprint dbc7e02bed4d1a5d1334e92f35e8a79efe9f175be5b50d9b394da9edab7c52c4 |
| A08-R2 MEDIUM | Report hardcoded ceilings looked like actual configured limits | Absolute ceiling names plus separate activeCollectionLimits,2-slot/100-ID JSONroundtrip regression | A07 inspected fix/test and74passing tests at same checkpoint |

Both original findings were VERIFIED RESOLVED at that checkpoint. The later bounded-histogram consumer change requires recertification of shutdown conservation/import behavior and final read-only handoff. Performance, mutation, final stress and platform gates are separate; no blanket release approval is implied.

A07 additionally inspected the bounded128-subject HDR consumer, raw-prefix omission versus true loss, and TimingImporter summary preference/overflow propagation before checkpoint4. No new high-severity finding was reported in that bounded source slice. Checkpoint6 passed100tests and core/mutation gates after strengthening supported transformer proof to48cases and final counters/source identity. A07 final read-only follow-up was requested against that frozen source. Full overhead and final stress remain separate runtime acceptance boundaries.

Final A07 bounded artifact verification independently parsed 100 tests with zero failures/errors/skips, matched raw completed-stress SHA256 `99d542390c1f820de7df3725c4019764bce3f0d32a4279a6774adf198bbc522e` to the envelope, confirmed that checkpoint's agent JAR is byte-identical to the stress artifact, and verified 87,210 + 87,449 monitor operations equal 174,659 healthy timings. It confirmed 46,361,673 queue drops and all 900 windows marked incomplete are preserved. No new high issue was found. That review preceded the actual first full overhead failure and subsequent repair; it does not certify changed repair source or settings. Other platforms remain untested.

Owner follow-up found method selection overflow/skips were exported by the agent but omitted by the timing importer/report. The repair adds bounded selection-reason parsing, a separate selection-overflow counter and incomplete selected-method coverage, with a real packaged two-method/one-ID JSON/HTML regression. Root authorized measured-cost repairs after the complete first overhead failure.

A01 independently reviewed the repair's queue/probe shutdown and import semantics. It found four defects, all corrected before repair certification 4:

| Finding | Correction and regression |
| --- | --- |
| Missing full summary could make an omitted raw prefix appear complete; producer totals were ignored | Importer compares observed events against accounted full-summary/raw counts, compares accepted reservations against observed plus discarded, and marks missing retained rows incomplete |
| A healthy truncated status prefix or duplicate recognized fields could hide missing coverage/loss | Version 2 requires every recognized field and a last completion marker; duplicates are rejected, legacy status retains unknown/incomplete coverage |
| Concurrent recoverable faults could lose increments in a volatile counter | Atomic rare-path counter; six concurrently admitted injected faults preserve six failures |
| An admitted producer could publish after shutdown finalized output | Bounded producer quiescence followed by atomic queue admission seal; deterministic paused-producer tests and a real System.exit child preserve application result 42 and report unfinished/incomplete observation |

A01 reported no remaining source blocker in those four findings after inspecting the fixes and meaningful regressions. That acknowledgment was a bounded source review while certification 4 was still running; it did not claim execution or performance acceptance. The subsequent actual clean run passed 118 methods and strict 256/266 targeted mutations, recorded separately in `evidence/repair-verification-v3.json`. Current source hashes include buffer `17bc2c03…`, probe `5e363356…`, premain `fe1f5f20…`, and importer `d05d6ccd…`.

Fresh cause/settings validation subsequently completed at **2026-10-18T07:05:14Z** under the root's all-three-lock quiet reservation: CPU7/7, allocation7/7 and contention7/7, actual outer exit0, all42 producer sidecars verified. The owner inspected actual context/summary/trials/settings/reservation and hashed339 raw files; `evidence/planted-cause-v3.json` preserves the full observed record and current transformation/fault-report references. This is owner evidence publication, not an invented independent performance review. The coordinator separately reported stopped-state checks passing.

Changed-observer stress and the full 84-JVM remeasurement remain open under **v3-allocation50-park-class-only**. The atomic in-flight fix adds real hot-path accounting; no speedup or release approval is inferred from source review, functional success or short cause tests.
