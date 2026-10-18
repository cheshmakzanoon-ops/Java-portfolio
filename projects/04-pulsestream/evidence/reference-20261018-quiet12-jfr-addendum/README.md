# Separate quiet12 JFR semantic addendum — pending review

This addendum follows the completed measured study and leaves its sealed19file packet unchanged. The coordinator explicitly authorized low-cap official JDK25 analysis during another project's functional-stress window. Sixteen sequential help/summary/view commands succeeded08:20:07–08:20:23UTC,15.54seconds total, with256MiB heap/one active processor,45seconds/command,180seconds total and1MiB combined output/command. Every analyzer completed and was reaped. This was declared shared functional background load after quiet12ended, not a quiet performance measurement.

[Envelope](envelope.json) binds the five original recording hashes and the parent packet. [Activity](activity.json) gives exact commands/timestamps/statuses and output hashes/locators; [tables](sampled-tables.json) retain recording durations/event counts and top sampled CPU/allocation sites. Exact stdout/stderr files remain at the indexed ignored scratch paths.

All five retained recordings parsed. Durable durations were1306/1271/969seconds, with31487/30577/24889execution samples. HashMap node creation is35.30–36.17%of sampled allocation pressure; map lookup/insertion, serialization and deflate methods appear among sampled execution sites. This supports investigating those costs, without identifying a dominant wall-time cause. Samples omit blocked/off-CPU time and cannot attribute all allocations or container resources. Zero recorded DataLoss entries is an observed event count, not a completeness guarantee.

The original/current engine records are short3/4second retained profiles with276/165execution samples. The known same-path overwrite limitation still applies: all numeric forks are retained, but distinct recordings for all forks are unavailable. This addendum does not repair or relabel them. A separate per-fork recording proposal is awaiting implementation authorization/scheduling; it would change the recording boundary and retain its own source/results.

No product/runtime/runner/READY or prior public packet changed. No Maven/build/Docker/new benchmark ran. Final independent interpretation and release approval remain separate.
