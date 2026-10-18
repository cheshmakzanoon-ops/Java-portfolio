# Exact contextual rule models and independent labels

Accepted. Rule models use exact method descriptors and argument positions for SQL text, shell command strings and filesystem paths. Sanitizers remove only one context bit and preserve uncertainty. A constant prepared SQL template with separately bound values differs from tainted SQL text supplied to `prepareStatement`; `normalize` alone does not establish path confinement. Process arguments are not universally shell command strings.

The alternative of guessing sanitizer intent from a method name would silently classify unsupported transformations as safe. Unknown/native operations instead preserve operand taint plus uncertainty; unsupported bootstrap or lambda heap effects produce partial coverage. Trusted fixture path guards are lexical, with an owned symlink-free root assumption, not a general filesystem race defense.

The120 labels were frozen before evaluation. Coordinator semantic review required supplied SQL receivers, fixed shell command prefixes and final transformed-path checks; v1 remains archived and reviewed v2 hashes/labels are immutable. Every confusion-matrix row is published. Additional unsupported and false-positive controls stay outside the matrix so engine tuning cannot improve the reported metric by relabeling failures.
