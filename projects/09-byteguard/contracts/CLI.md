# ByteGuard CLI/output contract version1

`byteguard scan --input PATH [--input PATH ...] --out PATH` scans explicit local classfiles, directories, JARs or ZIPs. `--classpath` supplies local dependencies; no network resolution occurs. `--target-release` supports8 through25. `--rules` accepts the versioned declarative rule document in `rules/core.yaml` (JSON syntax, also valid YAML1.2). Only exact source/sink/sanitizer descriptors and supported library models are accepted.

## Rule configuration

The rule document is one JSON object, with a maximum encoded size of **1 MiB**, nesting depth **64**, and numeric token length **32**. Duplicate object keys, trailing documents, unsupported fields, nonregular files, and symbolic file entries are rejected. Both the file size and actual bytes read are bounded. String values may use the document's remaining byte budget; a smaller arbitrary string limit must not exclude a valid JVM identity.

`model` must be `byteguard-core` and `version` must match the supported model version. `rules`, `sources`, `sinks`, and `sanitizers` are required arrays, each limited to **4,096 entries**. Empty source/sink/sanitizer arrays are explicit operator choices. Every supported rule must appear exactly once with its matching context mask. Duplicate descriptors, missing fields, scalar coercion, fractions, and integers outside the signed 32-bit range are invalid.

Sources and sanitizers require exact, well-formed method descriptors with a returned value. A sink's rule, mask, and zero-based argument must agree with that descriptor. Constructors return `V` and their parameter-slot limit includes the implicit receiver. Rule descriptions (`sink`, `safe`, and `trustedImplementation`) and the allowlisted `libraryModels` descriptions are documentation strings; they do not install new executable analysis semantics. An unsupported spelling is rejected instead of being silently ignored.

Configuration validation happens before target parsing. A failure returns exit **2** and a diagnostic that does not echo configuration values. Output ownership checks still apply to any machine-readable failure report.

## Baseline configuration

`--baseline` accepts one bounded **8 MiB** SARIF **2.1.0** object containing **1–64** explicitly identified ByteGuard runs. Each run must identify the current model version and supply an explicit `results` array. Each result must identify a supported rule and an exact, lowercase, 64-character hexadecimal `byteguard/v1` fingerprint. Missing or malformed identity fields cannot silently become an empty baseline. Duplicate JSON keys and trailing documents are rejected; unrelated SARIF extension fields remain allowed.

An explicit empty results array is a valid empty baseline. A partial prior report can contribute exact identities, but does not establish complete earlier coverage. Duplicate identities across runs are deduplicated. Legal long JVM names remain accepted within the overall byte budget, including names in reports emitted by ByteGuard itself.

## Scan output

`--format sarif` is the default; the pinned normative schema is `sarif-schema-2.1.0.json` with provenance/license in `SARIF-SCHEMA.md`. Reports include rules, detailed results, codeflows, stable partial fingerprints, invocation diagnostics, partial coverage, unfinished frontier and work/input/application counts. `--format json` emits schemaVersion1, partial, findings, diagnostics, unfinishedFrontier and workUnits. Machine findings expose rule/version/severity/confidence/model limitations, exact source/sink descriptors and instruction identities, optional source locations, bounded call path and fingerprint. Missing debug lines are not fabricated.

Exact fingerprint baselines retain results as unchanged while excluding them from the new-finding exit count. No filename wildcard baseline is accepted. Exit0 means complete with no new findings,1 complete with new findings,2 invalid input/configuration or analysis failure,3 partial coverage. Diagnostics accompany both terminal status and machine output; an unknown library differs from an incomplete analysis.

Independent controls are `--work-budget`, `--deadline-ms`, `--dispatch-limit` and `--provenance-depth`. Work/deadline/dispatch coverage limits produce partial results; provenance truncates explanation only. `--explain-dir` writes inspectable IR/CFG/frame/summary files with hashed filenames; `--explain-method` filters by a method signature and requires that directory option.

Tests validate actual complete/error/partial SARIF against the pinned schema and assert all four packaged exit codes. See `demo/README.md` for scenarios and `docs/THREAT-MODEL.md` for supported model boundaries.
