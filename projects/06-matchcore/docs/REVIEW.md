# Independent engineering review

A05 reviewed the matching engine, journal, gateway, HTTP boundary and failure tests. Its earlier scoped closure inspected production-Java SHA-256 `095e636739cbef837fb7a9783d15601cc625489ee96225a4f8349d5252ad7fb5`. Subsequent coordinator validation on the corrected JUnit 6.1.3 baseline passed 19 methods and the targeted mutation gate; see [historical evidence](../evidence/verification-current.json). The later measured-artifact closure is recorded below. Final clean-checkout/global release approval remains pending.

| Finding | Correction and observed regression |
| --- | --- |
| Positive IOC/FOK replacement could preserve a GTC order through the reduction path. | Reject TIF changes before modifying the old order or its holds; original order and reservations remain unchanged. |
| Default JWT validation admitted absent expiry/issued-at, and missing audience could throw. | Require signed time claims and audience explicitly; malformed/missing-time/audience HTTP negatives pass. |
| Numeric original JWT subjects were coerced to strings by Nimbus. | Validate cryptography, issuer, time and audience first, then parse the bounded original signed payload. Require an original nonblank string subject and typed scope/times; signed null, blank, numeric and array subjects return 401. |
| Saturation could reset a connection instead of returning a bounded rejection. | Bounded HTTP dispatch and explicit request permits produce visible 429 responses; saturation tests retain their assertions. |
| Zero-quantity matching mutants allocated fills indefinitely. | Require every matching iteration to consume positive quantity before changing balances. The strict mutation gate now has no memory or runner errors. |
| Critical-module tests missed useful reduction/depth observations. | Assert equal/reduced quantities retain FIFO priority, increment revisions, release exact holds, expose aggregated depth, fill makers in order and cancel/retry correctly at zero. |

A05 acknowledged the last three corrections against the current source and coordinator logs. It also inspected force-before-apply/result publication, checksummed journal/locking/snapshot behavior, the independent oracle and all four actual SIGKILL boundaries. This is a source-scoped review rather than a universal correctness proof.

## Mutation investigation

The first run had 100 killed, 24 survived, four uncovered and three memory-error outcomes. Maven accepted its numeric threshold; the stricter evidence gate rejected the unexplained runner errors. The original XML remains in `.work/coordinator/matchcore-first-pit/mutations.xml`.

After the positive-consumption invariant and meaningful reduction/depth assertions, the selected original Book scope has 119 killed and 14 survived out of 133. No target class, difficult branch or mutator was excluded; the threshold remains 70%. [Every survivor](../evidence/mutation-survivors.json) is retained with its exact location and transformation.

The survivors include result-code distinctions, held-resource/risk paths and the finite-run boundary that the selected core tests do not fully distinguish. The zero-quantity guard boundary is defensive for a healthy book. Do not describe all survivors as equivalent: the simulator's broader oracle checks do not automatically exercise a mutated core-module JVM. Additional evidence is needed for any individual equivalence claim.

## Final measured-artifact review

A05 closed the final read-only measurement review with **no blocking finding**. It independently recomputed the reviewed ten-file packet identity `7f6e9002ce30dc765d3b1081aa9f470c0c6760b1382ca908d62f8a62dcbc1a48` (138,424 bytes), confirmed executable/build-input fingerprint `62fc0b0351864486ffa736bd64f444393e3dda894f93677c7ca95448dab1ce83`, checked all 83 raw-file hashes/sizes and all 67 actual runtime-artifact hashes against the copied receipt, and verified the six exact result objects, counts, business histograms and common digest.

The review also independently verified summary medians/coefficient-of-variation values, all six JFR event counts/top frames/allocation weights and GC maxima, and JMH's three forks by three measurement iterations, raw means, confidence intervals, allocation and GC results. Published claims agree with [the measurement envelope](../evidence/reference-20261018/measurement-envelope.json) and [PERFORMANCE.md](PERFORMANCE.md).

The approved scope includes the disclosed 27-order peak versus the configured 100,000 cap; fixed seed, no excluded system-driver warmup and closed-loop sampling; per-run percentile summaries; whole-process profiling versus partial live-thread allocation counters; warm-process/hot-cache recovery; setup-visible JMH allocation; and force counts inferred from successful admissions through the one-force journal path. The reviewer performed no owned writes or Java/build/runtime starts.

This closes measured-artifact lineage and claim review only. Clean-checkout, repository-wide validation, hosted CI and release publication remain coordinator gates. The two closure-only documentation edits do not change the reviewed measurements, evidence files or executable source; their updated small-packet hashes are recorded in the coordinator handoff.
