# Bounded startup observer

Status: implemented, validation evidence in STATUS.

Use an explicit startup agent and system-loader unnamed-module scope. Dynamic attachment and arbitrary application classloader support multiply linkage/circularity risk without improving the stated controlled-workload objective. ASM handles bytecode mechanics; ThreadLens implements selection, handler precedence, safe metadata resolution, timing boundaries and fault isolation.

A custom primitive MPSC ring keeps host probes free of consumer locks and per-call event allocation. An unbounded concurrent queue would retain arbitrary application activity; a blocking bounded queue would transfer observer slowness to application latency. CAS contention can therefore drop an event even when some capacity remains. We expose drops rather than retry indefinitely.

Prefix/sample data and timing histograms remain separate. Flame widths represent observed execution samples; timing rows count selected observed exits and wall duration within instrumentation boundaries. Conflating the two would manufacture exact call frequencies from a sampler.

Live RecordingStream.stop is required before final dump to drain all recording events. Abrupt close is reserved for observer budget/error termination and explicitly marks missing windows. Producer settings are supplied with recording manifests; event schema alone does not establish the collection period or thresholds.
