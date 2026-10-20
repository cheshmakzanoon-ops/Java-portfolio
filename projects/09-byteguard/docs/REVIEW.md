# Design review record

Review concentrates on adversarial input handling, bytecode identity, frame consistency, uncertainty, and fixed-point termination. Findings below describe development defects and their regression coverage, not production incidents.

## Current hardening

| Finding | Correction | Regression |
| --- | --- | --- |
| Source-label parsing treated `|` as a delimiter even though it is legal in JVM method names. | Length-prefixed internal source identities retain the full method, instruction ordinal, and exact source descriptor. Existing ordinary finding fingerprints remain unchanged. | Actual bytecode with a `read|source@17:label` method produces the expected finding and report. |
| Individually selected classfiles bypassed the entry counter. | Explicit classfiles share the global entry budget with classpath, directory, and archive entries. | One-file budget accepts one class and rejects a second application or classpath class. |
| Report and failure-report paths could overwrite an input, baseline, or rule model. | Resolve existing filesystem components before normalizing unresolved suffixes, and check hard-link aliases before scanning; unsafe destinations cannot receive failure reports. | Direct alias, hard link, symbolic parent with `..`, baseline, rule, and explanation-location controls preserve the original bytes. |
| Serialization failure could truncate an earlier complete report. | Serialize to a sibling temporary file, then replace the destination after success. | Forced serializer failure preserves the previous report and removes temporary output. |

Current executed results are recorded in [verification status](STATUS.md).

## Earlier findings and corrections

- Directory enumeration now walks lazily and charges entry/deadline budgets before retaining unbounded paths.
- Unsupported void `invokedynamic` preserves the void stack effect and reports partial coverage. Unsupported/native lambda implementations never become apparently clean empty summaries.
- Symbolic argument-element writes are substituted onto caller references. Native void mutation retains uncertainty in receiver and argument contents.
- Hierarchy, strongly connected component, and weak-alias traversals are iterative and budgeted. Hierarchy indexing retains direct edges instead of a quadratic transitive closure.
- Field and returned-container identities survive transfer. Scalar field reads include unknown-object mutation state. Matched clean controls accompany positive regressions.
- Declarative sanitizer/sink contexts, exact descriptors, and argument indices receive configuration validation.
- Parser facts, frame cells, taint/reference identities, diagnostics, descriptors, and entry-frame setup have independent allocation/work bounds.
- Source metadata is sanitized for report locations; executable cache validation binds actual source and artifact bytes.

The retained source/report review for fingerprint `a6055cdfb18245240a3341c4a712c256e5502541dcdb85523e662423d7edf09c` examined 65-method results, 164 / 177 mutation results, unchanged frozen labels, and three full-scan outputs. That historical review covered its recorded source and measured artifact, not later changes.

An initial mutation score of 68% failed the unchanged 70% gate. Additional semantic tests strengthened coverage while retaining the same four targeted algorithm classes and survivor denominator; [the first result](../evidence/mutation-first.json) remains available. Earlier alias/lambda checkpoints and the enclosing-session exit discrepancy remain historical failures.

## Precision and review limits

The heap remains object-insensitive across calls. A source-filled array and a constant-only array passed through the same helper can contaminate the nominally clean call. Coarse shell-array modeling can also flag a positional argument that is not command text. These reproduced false positives remain outside the frozen corpus rather than being relabeled to improve its score.

Review does not establish universal soundness, exploitability, filesystem race protection, general Java support, or production accuracy. Frozen corpus methodology, remaining model boundaries, and benchmark scope are described in the [corpus guide](../corpus/README.md), [threat model](THREAT-MODEL.md), and [performance protocol](PERFORMANCE.md).
