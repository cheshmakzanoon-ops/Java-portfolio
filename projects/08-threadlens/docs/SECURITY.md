# Trust boundaries

A startup instrumentation agent runs with the application's permissions. Install only trusted artifacts. Its explicit package and exact method-name allowlists constrain scope, not JVM privilege. No dynamic attach or network endpoint is provided.

Normalization copies only event name/kind, start/duration, allocation/lock class identifiers and stack signatures. It never exports arguments, returns, HTTP content, system properties, environment variables, exception messages or thread names. Private-event and named-thread fixtures test the exclusion. Class and method names can themselves be sensitive identifiers; reports are not anonymous. Raw input recordings may have been made with a broader configuration.

All HTML dynamic text is escaped. Reports have no JavaScript, external resources or telemetry. A restrictive Content-Security-Policy is included. JSON is data, never inserted into executable script. Untrusted recording files can still incur JVM parser costs; analyze them in an OS-limited process, as the CLI does with bounded heap. Normalized aggregate cardinality limits do not constitute a hard bound on every allocation inside the JDK's JFR decoder.

The only supplied workload values are disposable synthetic fixtures. No credentials are required or logged. Timing ring slots contain primitives only, never application object references. Fatal VM failures, hostile custom classloaders and arbitrary JVM linkage corruption are beyond the supported safety boundary.


Timing artifacts are untrusted input. Registry identities must be unique, and TSV limits are checked while reading rather than after complete line allocation. Instrumentation refuses nonempty or symbolic-link output directories before transformation, preventing old status and summary files from being mistaken for observations from the current run. Use a dedicated directory and OS quotas: unrelated local writers with equivalent filesystem permissions can still race periodic checks.
