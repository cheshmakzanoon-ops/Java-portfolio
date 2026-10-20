# Engineering review

## Current boundary hardening

The HTTP and journal mappers previously accepted numeric enum ordinals and scalar coercion despite the typed command contract. Both use the same `CommandJson` factory, which rejects enum ordinals, scalar coercion in either direction, null primitives, fractional integers, duplicate keys, unknown fields, and trailing documents. Text fields explicitly reject integer, floating-point, and boolean input shapes; disabling the general scalar-coercion feature alone does not cover those conversions. Journal regressions construct frames with correct header and payload CRCs but invalid JSON types, demonstrating that checksum validity cannot bypass schema validation. A checksum-valid JSON null payload is rejected as an I/O decoding failure rather than escaping as a null dereference.

The public journal append method now validates commands before writing. Its regression submits an invalid initial balance, confirms the file and entry list remain empty, then appends a valid command at the same sequence and recovers it successfully.

HTTP query parsing previously overwrote repeated names and ignored encoded parameter names. It now decodes names and values once, rejects unknown or duplicate decoded names, and rejects negative minimum sequences. The HTTP regression checks ambiguous encoded aliases, repeated pagination values, negative bounds, valid encoded values, and non-admission of ill-typed commands.

Current execution results are recorded in [verification status](STATUS.md). Historical benchmark and artifact identities below apply to their original source, not to subsequent hardening.

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
