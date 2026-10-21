# Engineering review

## Current state, recovery and ownership review

The production review covers every matching/model/checkpoint, journal/decoder, gateway/HTTP/identity, reference/demo/crash and workload/benchmark class. [Architecture](ARCHITECTURE.md) maps the fourteen production classes to their responsibilities. The six module POMs now use conventional XML formatting without changing dependency versions or coverage/mutation thresholds.

| Priority and finding | Correction | Regression |
| --- | --- | --- |
| High: v1 record-text hashes can collide for unequal nullable text, so changed retries could receive an old success | Exact command authority for pending/live/recovered receipts, retaining v1 bytes and hash values | Null vs literal-null pending/committed/checkpoint restart; state-only restore fails closed; exact retries succeed |
| High: direct journal callers could append after an incomplete frame or unknown force outcome | Explicit fail-stop OPEN/FAILED/CLOSED lifecycle; all admitted append faults poison the instance | Every append boundary, lost real force acknowledgement, reopen repair and stable retry |
| High: externally constructed/checksum-valid checkpoints could publish invalid or partially restored business state | Atomic private restore with domain invariants; result/command and initialized-supply/execution ledger binding | Invalid balance/order/result/history matrix; positive cash minting and CRC-valid snapshot tampering; valid generated round trips |
| Medium: caller cancellation/completion changed a shared pending response | Keep durable authority private and return dependent future copies | Cancel, forge success and fail three callers while another observes the genuine committed result |
| Medium: applied but unacknowledged commands were counted twice at the run cap | Count each successfully queued admission once | AFTER_APPLY barrier accepts the final legal command and rejects the next |
| Medium: cleanup failure could replace the primary recovery/replay failure | Preserve cause and suppressed cleanup errors; attempt both lock release and channel close | Real file-channel acquisition/release/close faults and invalid replay cleanup |
| Medium: completion callbacks could execute while holding admission ownership | Complete success/failure futures outside the synchronized monitor | Callback lock assertion and concurrent retry/admission barriers |
| Medium: four incomplete request bodies could retain every HTTP handler indefinitely | Standalone JDK request/response deadline defaults before first server initialization | Separate server process, four partial uploads, subsequent successful durable command |
| Medium: query validation differed across authenticated routes | Explicit route allowlists after scope checks; no-store responses | Unknown query on writes/private reads rejects without admission; missing scope stays 403 |
| Verification gap: CRC producer/consumer mutations could agree, and SIGKILL alone did not prove force calls | Independent frame bytes/CRC checks and observed real channel adapter | External valid frame, partial reads/writes, journal/snapshot/directory/tail-repair force counts |
| Verification gap: restored private indexes and pagination lacked direct observations | Assert restored results/retries and maker/taker execution windows | Real two-maker fill history, offset/limit/account/cutoff and checkpoint reopen |

Strict command decoding still rejects both directions of scalar coercion, enum ordinals, null primitives, fractional integers, duplicate keys, unknown fields and trailing input. Checksum-valid malformed frames demonstrate independent schema enforcement. Protected identity tests verify original signed claim types after signature verification, preventing coercion into authority.

Snapshot validation preserves the optimization contract: every journal frame is decoded, structural state is validated privately, and a lightweight ledger checks supply/transfers and result identities. Matching executes only the suffix. Consistency checks and prefix hashes do not authenticate storage against an attacker who can rewrite the journal and its hashes. No v1 format migration or journal pruning was introduced.

[Verification status](STATUS.md) records the exact current source and test/mutation outcomes. Surviving mutants remain explicit; numerical threshold success does not imply equivalence. Historical measurements and failures below retain their original scope and identities.

## Historical source review

The earlier review covered the matching engine, journal, gateway, HTTP boundary, and fault tests at production-Java SHA-256 `095e636739cbef837fb7a9783d15601cc625489ee96225a4f8349d5252ad7fb5`. Subsequent validation under JUnit 6.1.3 passed 19 methods and the selected mutation gate; see [historical evidence](../evidence/verification-current.json). A later full run passed 21 methods. These are distinct validation scopes.

| Finding | Correction and regression |
| --- | --- |
| IOC/FOK replacement could enter the priority-preserving GTC reduction path | Reject time-in-force changes before modifying the order or reservations |
| Default JWT validation admitted missing expiry or issued-at | Require signed time claims and audience; test missing and malformed claims over HTTP |
| Nimbus normalized numeric original subjects into strings | Verify signature and trusted claims first, then validate original signed subject, scope and time types |
| Saturation could reset a connection instead of reporting overload | Bound dispatch and request permits; preserve an explicit retryable 429 regression |
| A zero-quantity mutation allocated fills indefinitely | Require positive quantity consumption before changing balances |
| Reduction and depth tests missed useful observations | Assert FIFO, revision, exact reservation release, aggregated depth and cancellation/retry outcomes |

The review also covered force-before-apply/result publication, journal checksums and locking, snapshot fallback, the independent oracle, and all four separate-process SIGKILL boundaries. These tests provide evidence within the stated model; they are not a universal correctness proof or hardware power-loss test.

## Historical mutation analysis

The first run recorded 100 killed, 24 survived, four uncovered, and three memory-error outcomes. The numeric threshold passed, but the stricter evidence gate rejected the runner errors. The original XML was retained with the failed-run evidence.

After the positive-consumption invariant and reduction/depth regressions, the selected `Book` scope recorded **119 killed and 14 survived out of 133**, with no uncovered mutations or runner errors. No target class, difficult branch, or mutator was removed; the threshold remained 70%. [Every survivor](../evidence/mutation-survivors.json) retains its location and transformation.

Survivors include result-code distinctions, risk paths, and finite-run boundaries that the selected core tests do not fully distinguish. Do not assume equivalence: a broader simulator oracle does not automatically execute inside a mutated core-module JVM.

## Historical measurement review

The review recomputed the ten-file packet identity `7f6e9002ce30dc765d3b1081aa9f470c0c6760b1382ca908d62f8a62dcbc1a48` and executable/build-input fingerprint `62fc0b0351864486ffa736bd64f444393e3dda894f93677c7ca95448dab1ce83`. It checked 83 raw-file hashes and sizes, 67 actual runtime-artifact identities, six exact result objects, business histograms, and the common replay digest. Summary statistics, JFR event counts and allocation weights, GC maxima, and three-fork JMH results matched the published packet. No blocking finding remained in artifact lineage, arithmetic, or stated claims.

Material limits remain: the open-order peak was 27 versus a configured 100,000 cap; one fixed seed and closed-loop sampling bound generalization; recovery used a warm process and hot cache; JMH allocation included setup; and force counts were inferred from successful admissions through the one-force journal path. See [performance](PERFORMANCE.md) for every repetition and boundary.

The derivative CSV was later normalized from CRLF to LF without changing parsed values. Both identities remain in the [evidence index](../evidence/reference-20261018/README.md). The unchanged 83-file raw index is separate from that derivative.

## Raw-data preservation and release scope

A later archive preserved all 98 batch files and 15 directories, including the 83 reviewed raw artifacts. Independent physical restoration compared complete paths, binary hashes, sizes, and permission modes before the exact original batch was removed. The [manifest and recovery/removal receipts](../evidence/reference-20261018/README.md#restore-the-original-raw-files) retain the restoration protocol and original bytes.

That storage verification does not constitute a new measurement, a validation of later source changes, or a complete repository release. Clean-checkout reproduction, repository-wide checks, hosted CI, and release publication remain separate.
