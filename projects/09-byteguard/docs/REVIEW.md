# Independent review ledger

Reviewer: A08 ThreadLens owner, read-only review of ByteGuard. ByteGuard owner made all changes. Coordinator reviewed corpus labels separately before evaluation.

| Finding | Correction and regression | Status |
| --- | --- | --- |
| Directory walk eagerly sorted/materialized unbounded paths before entry/deadline checks. | Lazy traversal charges every filesystem entry and checks deadline. Reader/adversarial tests. | Implemented; historical integration passed. |
| Unsupported void invokedynamic pushed a TOP result and corrupted frame shape. | Preserve void stack effect; retain unknown/partial diagnostic. `BoundaryTest.unsupportedVoidIndyIsPartialWithoutInvalidFrame`. | Verified in historical integration. |
| Callee parameter-array writes were not substituted onto caller reference identities. | Symbolic argument-element write summaries, actual-reference substitution, transitive effects. `helperArrayAndCollectionWritesReachCaller`. | Verified in historical integration. |
| Deep recursive hierarchy indexing retained transitive O(n²) edges before budgets; recursive SCC could overflow. | Linear direct-child index; iterative budgeted hierarchy/default-interface resolution and iterative Kosaraju. `EngineTest.deepHierarchyAndCallGraphAreIterativeAndBudgeted`, `inheritedDefaultInterfaceIsResolved`. | Verified in historical integration. |
| Native/unresolved calls could be treated as empty summaries; default-interface implementation resolution was missing. | Native/abstract target filtering and explicit unknown transfer, interface-default traversal. Native/default-interface boundary tests. | Verified in historical integration. |
| Unknown/native void mutator discarded uncertainty along with its void return, leaving argument-array contents apparently clean. | Havoc receiver/argument element summaries with joined operands plus unknown; record transitive writes. `unknownVoidMutatorsHavocElementsAndPropagateArgumentTaint`. | Independently verified PASS in current-root Boundary XML; overall checkpoint failed on unrelated lambda dependency. |
| R8: field loads discarded container identities and R9: known call returns discarded formal/allocation identities. | Bounded weak alias closure, stable field/formal/mutable-return identities, scalar field-write effects and method-scoped parameter symbols. Three new boundary regressions include positive mutation cases and matched constant-only cases. Direct bounded probes and actual boundary cases passed. Reused-helper clean control reveals disclosed cross-call heap false positive. | A08 independently verified R8/R9 PASS in current-root XML; overall checkpoint failed on unrelated lambda dependency. |
| R10: unknown object mutation state was bypassed by scalar GETFIELD reads. | Join specific field state with existing receiver-content havoc; native Box/source positive, unknown-not-bottom return, matched known constant-field control. | A08 independently verified PASS in actual20-method Boundary XML. Full release review awaits benchmark. |
| Declarative sanitizer/sink contexts and argument indices needed configuration validation. | Exact method descriptors, rule/context consistency, valid argument index, single sanitizer context. Direct invalid-model assertions and packaged rules path. | Verified in the current clean65-method run. |

Owner adversarial review additionally added independent state-allocation limits (parser facts, frame cells, taint/reference identities, diagnostics), unknown/partial ConstantDynamic behavior, source-metadata path sanitization, exact executable cache integrity, and unsupported lambda heap-effect diagnostics. These changes remain subject to the final frozen-source run and A08 review closure.

The coordinator's v2 preregistration approval is recorded in `corpus/README.md`: supplied SQL interfaces, fixed shell executable, and final transformed path validation. Original v1 sources, labels, hashes and rationale remain available. No labels changed in response to scanner metrics.

Initial targeted mutation score68% was a failed gate, preserved in `evidence/mutation-first.json` with raw XML/HTML under `.work/A09/mutation-first`. Semantic tests were strengthened; the70% threshold and four original algorithm classes were retained. The cancelled mixed-source invocation is not certification evidence.

Final review does not certify general soundness or exploitability. The shell positional-argument false positive, unknown libraries, lexical-only fixture path confinement, unsupported bootstrap/heap behavior, and performance-bound scope remain explicit in the threat model and case study.

Historical pre-benchmark checkpoint:59 methods passed, with final all-evidence review still open at that time. The current closure below supersedes this checkpoint.

## Final current-source closure

A08 independently reviewed the final source `a6055cdfb18245240a3341c4a712c256e5502541dcdb85523e662423d7edf09c`, CLI SHA `390a7a39ef6041fdcbc347b5a0be437eead09a9feb0bb2277b11951a6783db93`, fresh65-method reports,164/177 mutation results, unchanged frozen labels and three actual quiet full-scan raw outputs. R11 descriptor-allocation failure is resolved with linear JVM limits, compact lossless-identity scopes, budgeted entry setup and real64MiB invalid/valid child tests. The exact old invalid fixture now returns2 without OOM. Final scoped closure: PASS, no unresolved concrete finding.

This supersedes the earlier pending checkpoint descriptions above. R7–R10 and sanitizer-model regressions pass in the full current clean run. The earlier enclosing-session exit discrepancy remains historical evidence; a new clean full command returned0. The current artifact's reset/up/demo/reuse/down separately returned0. General soundness, field accuracy and repository-wide clean-checkout/CI release approval are outside this review.
