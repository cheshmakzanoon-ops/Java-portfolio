# Technical walkthrough

1. Run the CLI demo. Explain why a prepared statement with tainted SQL text is different from a constant SQL template with separately bound input.
2. Follow `MethodIr`, CFG exceptional edges, and a category-two DUP form. Show a missing-debug fixture and its bytecode-only identity.
3. State the taint semilattice laws. Show a property seed and why provenance is bounded outside the lattice.
4. Trace a callee sink's symbolic parameter into a caller's concrete source, including a recursive SCC and an argument-element write.
5. Lower only provenance depth and compare finding fingerprints. Then lower global work and inspect the partial frontier and exit3.
6. Explain unknown versus bottom, missing dependencies, unsupported bootstrap behavior, and native methods. Point to the sentinel-initializer regression.
7. Read the frozen labels and every confusion-matrix row. Explain why these do not estimate production accuracy or prove exploitation.
8. Show the pinned SARIF schema check and exact baseline matching. Add an unrelated method and retain the existing fingerprint.
9. Read the actual 10,000-class benchmark envelope. Explain setup work charging, direct hierarchy indexing, shared-heap requeue cost, and the next memory bottleneck.
10. Reproduce the disclosed shell positional-argument false positive. Discuss a bounded index-sensitive array refinement without expanding sanitizer claims.

Assisted development: implemented with OpenAI Codex agents under repository-owner direction; independent project-owner review found concrete defects and informed regressions. ASM, Jackson, picocli, networknt, JUnit, jqwik, JaCoCo and PIT are third-party supporting tools; the analyzer algorithm is original project code.
