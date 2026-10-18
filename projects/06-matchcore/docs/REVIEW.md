# Independent engineering review

A05 reviewed the matching engine, journal, gateway, HTTP boundary and failure tests. Its latest scoped closure inspected production-Java SHA-256 `095e636739cbef837fb7a9783d15601cc625489ee96225a4f8349d5252ad7fb5`. The subsequent coordinator validation on the corrected JUnit 6.1.3 baseline passed 19 methods and the targeted mutation gate; see [current evidence](../evidence/verification-current.json). Performance and final clean-checkout release approval remain pending.

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

Remaining review covers the final workload generator/result distribution, measured resource behavior, operational artifacts and clean-checkout/remote validation. No full-release approval is implied by these scoped closures.
