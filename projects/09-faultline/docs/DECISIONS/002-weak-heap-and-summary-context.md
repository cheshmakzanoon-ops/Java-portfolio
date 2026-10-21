# Weak heap aliases and bounded summary contexts

Accepted with a documented precision boundary. Method summaries retain source/parameter context bits, sink flows, argument-element writes and scalar-field writes. Parameter symbols carry the method identity so instantiation does not rebind foreign symbols. One caller instruction identifies a summary instantiation context; call-provenance depth is independently bounded and cannot remove taint facts.

Fields are sensitive to owner/name/descriptor while objects are merged. Array and selected collection contents use weak element summaries. Stable field, formal and mutable-return identities connect to allocation references through an iterative budgeted alias graph; this preserves native/helper mutations through field loads and returned containers. Alias state is monotone and conservatively requeues methods when the shared heap grows.

Rejected alternatives are treating void helpers as effect-free, discarding references on return, and pretending context-keyed taint summaries make heap references context-sensitive. Review found concrete missed-flow examples for the first two. A fully context/object-sensitive heap could improve precision but would multiply state and complicate recursion/termination under the resource envelope.

The chosen heap can contaminate a clean invocation of a reused mutable helper. The actual additional control under `evidence/controls/` demonstrates this false positive. It is excluded from the frozen model corpus and disclosed rather than suppressed. Index-sensitive shell arrays and a bounded context-sensitive heap are possible future refinements; no current claim relies on them.
