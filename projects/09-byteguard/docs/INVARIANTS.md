# Invariants and failure boundaries

ByteGuard reasons about explicit local classfiles under its declared model. A complete scan establishes that modeled fixed points were reached within bounds; it does not establish program safety or exploitability.

| Invariant | Enforcement | Evidence |
| --- | --- | --- |
| Target code is never loaded or initialized. | The input layer reads owned bytes; ASM supplies structural facts only. The analyzer has no target classloader/reflection/execution path. | Packaged `CliProcessIT` and corpus sentinel test use a side-effecting initializer and assert that its marker remains absent. |
| Archive bytes, entries and paths stay bounded. | Streaming ZIP input, no extraction, actual expanded-byte accounting; reject traversal, duplicate logical names, symlinks and nested archives. | `EngineTest` archive bomb/corruption/duplicate/traversal tests. |
| Joins only grow information. | Source/context-bit union plus independent unknown; method/frame/heap joins are weak. Sanitization removes only the modeled context bit. | Fixed-seed `LatticePropertiesTest`, exact `EngineTest` semantic assertions and schedule-independent corpus comparison. |
| Stack/local categories agree at supported joins. | Explicit computational values, category-two local widths, typed loads/invocations, handler stack replacement and CFG successor validation. | `OpcodeTest`, loop/wide/handler core cases and every reachable corpus frame. |
| Unknown never becomes clean through an unsupported call. | Unknown/native receiver and argument elements receive uncertainty plus operand taint, including void calls. Unsupported bootstrap/lambda effects produce partial diagnostics. | `BoundaryTest` unknown/native/void-indy/ConstantDynamic/lambda boundary cases. |
| Field and container mutations retain reference connections. | Weak field identities; bounded iterative allocation/formal/mutable-return alias closure; element and scalar-field write summaries. Method-qualified symbolic labels prevent foreign parameter rebinding. | R8/R9 positive and constant-only controls; direct controls and full current clean certification pass. |
| Provenance cannot delay fixed-point termination. | Call-path growth is limited separately from lattice state. Truncation affects explanation, not source taint. | Corpus provenance-zero comparison and recursion tests. |
| Resource exhaustion stays visible. | Shared work/deadline budget covers parsing, hierarchy/CFG/SCC setup and solving; explicit unfinished frontier and exit3. | Parser/frame/work/deadline/dispatch tests and packaged partial CLI case. |
| Baselines match exact identities. | Rule/source/sink fingerprint set; no filename wildcard suppression. | Unrelated-method stability and exact baseline tests. |

Default input limits are25,000 entries,200MiB expanded,8MiB per entry/class, expansion ratio100, no nesting, and120s. Analysis defaults are50million work operations,120s,32 dispatch targets and5 provenance steps. Additional bounds are2million retained visitor/alias facts,8million cumulative frame cells,4096 identities per taint/reference/alias value and10,000 diagnostics. These are conservative resource envelopes, not asymptotic guarantees. Exceeding a supported analysis bound produces partial coverage; invalid archive/configuration produces failure. Full benchmark completion remains an independent release gate.

One-callsite taint-summary contexts do not imply context-sensitive heap aliases. Reused mutable helpers can contaminate an independent clean call; the actual false-positive control is preserved in `../evidence/controls/`. See [threat model](THREAT-MODEL.md) and [review](REVIEW.md).
