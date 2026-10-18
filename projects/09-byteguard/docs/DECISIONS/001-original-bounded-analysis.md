# Original bounded analysis with structural ASM

Accepted. The project must expose its own CFG, typed computational values, monotone solver and interprocedural summaries. ASM9.9.1 supplies Java25 structural facts and instruction nodes; its built-in analyzer and turnkey taint engines are not used. The alternative of delegating analysis would reduce implementation work but would not demonstrate ownership of the core algorithms or their uncertainty semantics.

The engine operates on explicit local bytes without loading target classes. This prevents application initialization side effects and avoids dependency resolution/execution. Owned fixture compilation uses `-proc:none`; a sentinel regression checks that analyzed initialization does not run.

One shared work/deadline budget covers parser facts, hierarchy/CFG/SCC setup, alias traversal and solving. Independent entry/byte/ratio/state limits protect other resource dimensions. The consequence is explicit partial coverage on exhaustion rather than a clean result. Resource-limited behavior appears in the machine report and terminal exit3. A successful full supported10,000-class run is still required independently of safe partial termination.
