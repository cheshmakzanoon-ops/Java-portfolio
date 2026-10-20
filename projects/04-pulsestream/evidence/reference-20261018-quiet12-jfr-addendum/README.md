# PulseStream JFR semantic addendum

This addendum follows the completed quiet12 study and retains the original recordings and measured rows. Sixteen bounded official JDK 25 commands ran from 08:20:07 to 08:20:23 UTC on 2026-10-18, taking 15.54 seconds in total. Each analyzer used a 256 MiB heap and one active processor, with a 45-second command budget, 180-second total budget, and 1 MiB output limit. Every process completed and was reaped. This was post-measurement analysis, not another performance run.

[Envelope](envelope.json) binds the five original recording hashes and parent packet. [Activity](activity.json) records commands, times, statuses, and output locators/hashes. [Sampled tables](sampled-tables.json) retain recording durations, event counts, and sampled execution/allocation sites. Exact output remains at the indexed ignored paths.

All five recordings parsed. Durable recordings lasted 1,306/1,271/969 seconds and contained 31,487/30,577/24,889 execution samples. HashMap node creation accounted for 35.30–36.17% of sampled allocation pressure. Map lookup/insertion, serialization, and deflate methods appeared among sampled execution sites. This supports investigating those costs; it does not identify the dominant wall-time cause. Samples omit blocked/off-CPU time and cannot attribute all allocations or container costs. Zero recorded DataLoss entries does not prove complete capture.

The retained original/current engine profiles lasted approximately three/four seconds and contained 276/165 execution samples. Their same-path overwrite limitation remains: every numeric fork sample survives, but separate original recordings do not. The subsequent [quiet14 capture check](../capture-20261018-quiet14/README.md) validated a different per-fork recording setup without replacing these results.

No application build, dependency stack, or new workload ran during this analysis. Independent interpretation and final release approval remain separate from the successful parsing commands.
