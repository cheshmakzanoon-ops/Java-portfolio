# Design review record

Review concentrates on adversarial input handling, bytecode identity, frame consistency, uncertainty, and fixed-point termination. Findings below describe development defects and their regression coverage, not production incidents.

## Current hardening

| Finding | Correction | Regression |
| --- | --- | --- |
| Source-label parsing treated `|` as a delimiter even though it is legal in JVM method names. | Length-prefixed internal source identities retain the full method, instruction ordinal, and exact source descriptor. Existing ordinary finding fingerprints remain unchanged. | Actual bytecode with a `read|source@17:label` method produces the expected finding and report. |
| Individually selected classfiles bypassed the entry counter. | Explicit classfiles share the global entry budget with classpath, directory, and archive entries. | One-file budget accepts one class and rejects a second application or classpath class. |
| Report and failure-report paths could overwrite an input, baseline, or rule model. | Resolve existing filesystem components before normalizing unresolved suffixes, and check hard-link aliases before scanning; unsafe destinations cannot receive failure reports. | Direct alias, hard link, symbolic parent with `..`, baseline, rule, and explanation-location controls preserve the original bytes. |
| Serialization failure could truncate an earlier complete report. | Serialize to a sibling temporary file, then replace the destination after success. | Forced serializer failure preserves the previous report and removes temporary output. |

The deeper runtime review traced every production class across input/structural parsing, CFG/frames, taint/heap transfers, hierarchy/call scheduling, reporting and CLI ownership. The following additional defects had executable counterexamples; the corrections preserve the frozen corpus labels and declared model boundaries.

| Priority | Finding and correction | Independent regression outcome |
| --- | --- | --- |
| High | Native dispatch alternatives disappeared when a clean override had a summary. Native targets now remain explicit and use opaque effects. | A native/clean dispatch mixture retains the external-input flow; the isolated clean receiver stays clean; FIFO/LIFO agree. |
| High | Mutable opaque results had no heap identity, dropping later array stores and possible aliases. `InvocationEffects` owns stable call-result identity, conservative operand aliases and effects. | Fresh-array, alias-return and helper-write positives retain concrete source dependencies; constant-only controls stay clean. |
| High | A local-header-only or four-byte ZIP could report a complete empty scan; directory payloads bypassed actual-read limits. Bounded ZIP/ZIP64 index validation precedes indexed, checksummed entry reads. | Truncated end/index, count/CRC corruption, metadata cap, directory payload and symlink entries fail; an independently JDK-readable ZIP64 archive succeeds. |
| High | Direct DOT writes could truncate a scanned class through a preexisting symbolic/hard link. Every output now uses `OwnedOutput`. | Actual class bytes survive both aliases; symbolic output is refused and hard-link output is replaced without changing the input inode. |
| High | UTF-8 replacement and delimiter concatenation could merge distinct method/finding identities. Structured lossless special-name hashes preserve ordinary hashes. | Independent collision controls separate tuple boundaries and lone UTF-16 code units; actual bytecode produces two findings and two DOT files. |
| Medium | Supported transfers accepted wrong arithmetic/receiver/return kinds, incoming handler stack overflow, missing concrete bodies and reachable fall-through. Typed checks now validate both incoming and outgoing states. | Serialized real classfiles with malformed computations fail in both schedules; valid loops, category-two forms and branch-local reuse succeed. |
| Medium | Local INT/REF merges rejected otherwise valid bytecode even when the slot was unused. Incompatible locals now become TOP while stack merges stay strict. | A javac-shaped shared-slot branch succeeds; loading the unusable merged slot fails. |
| Medium | Exception ranges were checked only while visiting throwing instructions. Range shape is validated independently. | Empty/reversed/out-of-bounds ranges fail even around nonthrowing code. |
| Medium | Parser exhaustion omitted all unstarted analysis work in completed classes. Frontier now includes every retained concrete method. | Deterministic budget completes A then stops in B; result retains A facts, no summaries, A's method obligation and `parser:B.class`. |
| Medium | Branch/switch/range label resolution repeatedly walked uncharged metadata. `MethodIr` now builds a reverse target index once. | 16,384 indexed lookups across 4,096 metadata nodes require zero forward traversals; foreign labels fail explicitly. |
| Medium | Duplicate member declarations silently replaced earlier bodies; descriptor prefixes were accepted by permissive type parsing. | Duplicate methods/fields and invalid whole field types fail; legal overload/type controls retain their semantics. |
| Medium | Explicit named pipes could block before the deadline; baseline/rule reads also lacked a regular-file boundary. | Input/baseline FIFO controls return promptly before opening; bounded reads recheck actual configuration length. |
| Medium | Provenance joined paths by ambiguous delimiter text, making witness choice schedule dependent. | A deliberate joined-text collision now yields the same exact tuple witness in both join orders and repeated fixed-point joins. |

The extracted helpers isolate input-index preflight, invocation effects, identity encoding and output publication from their callers. They establish testable responsibilities rather than adding another configuration layer. Expanded mutation targets cover changed pure transfer, hierarchy, summary, archive-index and report identity/publication components alongside the existing lattice/frame/CFG targets. Database or scheduler claims do not apply to this offline program.

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

An initial historical mutation score of 68% failed the unchanged 70% gate. Additional semantic tests strengthened that historical four-class scope while retaining its full survivor denominator; [the first result](../evidence/mutation-first.json) remains available. Earlier alias/lambda checkpoints and the enclosing-session exit discrepancy remain historical failures.

## Precision and review limits

The heap remains object-insensitive across calls. A source-filled array and a constant-only array passed through the same helper can contaminate the nominally clean call. Coarse shell-array modeling can also flag a positional argument that is not command text. A native/clean receiver pair also reproduces that contamination when both call through a shared receiver formal; its isolated clean control remains clean. These reproduced false positives stay outside the frozen corpus rather than being relabeled to improve its score. An initial deeper-pass regression expected that shared-call control to be clean and failed; the test now explicitly records the conservative result and separately asserts the isolated clean case.

Review does not establish universal soundness, exploitability, filesystem race protection, general Java support, or production accuracy. Frozen corpus methodology, remaining model boundaries, and benchmark scope are described in the [corpus guide](../corpus/README.md), [threat model](THREAT-MODEL.md), and [performance protocol](PERFORMANCE.md).
