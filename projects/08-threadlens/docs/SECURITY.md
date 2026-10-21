# Trust boundaries

A startup agent executes with the application's permissions. Install trusted artifacts and explicitly allowlist application packages/methods. This limits observation scope, not JVM privilege. ThreadLens provides no dynamic attach or remote endpoint.

Only the first startup attempt owns the process-global probe. Installation establishes the writer, consumer and shutdown hook before enabling publication; failure attempts every owned cleanup and preserves suppressed errors. Constructor/initializer/native/abstract methods, named modules and non-system loaders are excluded. Frame resolution reads bounded, identity-checked class resources and never initializes a target type. Unsupported metadata skips the class rather than guessing a common superclass.

Normalization copies event kind/time/duration, class identifiers and stack signatures. It excludes application arguments/returns, messages, HTTP data, arbitrary properties/environment and thread names. Private-event and named-thread fixtures verify these exclusions. Method/class names can themselves be sensitive. Raw recordings may contain broader data collected by other active recordings; normalized reports are not anonymous.

Timing ring entries contain primitives. Event ingress enforces both slot and conservative payload bounds, then seals during shutdown. Snapshots cannot race a still-active consumer. Selection/queue/overflow/clipping/unknown-status losses stay visible; failure does not establish that the application was inactive.

Output directories must be dedicated. Existing agent artifacts cannot supply stale status to a later run. JFR roots reject symbolic-link ancestry; dump paths must name direct children and cannot replace existing files. Scans reject unvisited deep subtrees. These controls do not protect against an equally privileged process changing files during a check; use OS permissions and quotas.

Untrusted timing artifacts and reports undergo incremental/file/structure/cardinality validation. JSON rejects duplicate fields and inconsistent denominators, counts and quantiles before rendering or comparison. HTML escapes every dynamic identifier, has a restrictive CSP and includes no scripts, external resources or telemetry. Chromium checks exercise literal hostile identifiers, keyboard navigation and responsive scrolling.

Producer settings manifests accept only bounded JFR setting declarations. They are not authenticated proof of how a recording was produced. Analyzer platform metadata and selected-timing intervals are labelled separately from recording identity/time. Unknown settings prevent comparison.

Report JSON and producer manifests each contain exactly one document. Producer-setting
values must be JSON strings; booleans and numbers are not silently converted into
declarations. Report integer and boolean fields reject null, fractional numbers and
scalar coercion. Cross-window sample totals and disjoint time intervals are checked so
duplicated buckets cannot be rendered as independent measurements.

Comparison output rejects file aliases of either input, including existing hard links.
The CLI rejects symbolic-link files at the final input/output component and stages
complete renders before individually atomic publication. These checks do not prevent
an equally privileged concurrent writer from replacing filesystem objects after
validation; dedicated directories remain required. HTML and optional JSON publication
are two individual moves, not a multi-file transaction.

Aggregate limits do not bound every JDK JFR-decoder/native allocation or eliminate parser CPU costs. The supplied CLI launcher sets a heap bound; analyze untrusted recordings/reports in an OS-limited process as well. Fatal VM/resource failures, invalid installation, hostile custom loaders and arbitrary JVM/native corruption are outside the recoverable host guarantee. Supported behavior and actual evidence are recorded in [operations](OPERATIONS.md) and [STATUS](STATUS.md).
