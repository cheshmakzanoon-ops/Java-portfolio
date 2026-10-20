# Technical walkthrough

1. Run the CLI demo. Explain why a prepared statement with tainted SQL text is different from a constant SQL template with separately bound input.
2. Follow the indexed `MethodIr` label targets, CFG exception ranges, a category-two DUP form, and valid local TOP merges. Compare wrong-kind/return/handler-maxStack failures with serialized valid controls.
3. State the taint semilattice laws. Show a property seed and why provenance is bounded outside the lattice.
4. Trace a callee sink's symbolic parameter into a caller's concrete source, including a recursive SCC and an argument-element write.
5. Lower only provenance depth and compare finding fingerprints. Then lower global work and inspect the partial frontier and exit3.
6. Explain unknown versus bottom, mixed native/clean dispatch, mutable opaque return aliases and unsupported bootstrap coverage. Point to the sentinel-initializer and isolated-clean regressions.
7. Read the frozen labels and every confusion-matrix row. Explain why these do not estimate production accuracy or prove exploitation.
8. Show the pinned SARIF schema check and exact baseline matching. Add an unrelated method and retain an ordinary fingerprint; then inspect delimiter/lone-UTF-16 identity controls and protected-input DOT alias tests.
9. Read the historical 10,000-class benchmark envelope and its exact source identity. Explain setup work charging, direct hierarchy indexing, shared-heap requeue cost, and the next memory bottleneck.
10. Reproduce the disclosed shell positional-argument false positive. Discuss a bounded index-sensitive array refinement without expanding sanitizer claims.


Dependency roles and upstream notices are described in [attribution](ATTRIBUTION.md).
