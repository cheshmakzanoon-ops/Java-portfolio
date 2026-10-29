# Faultline CLI/output contract version1

`faultline scan --input PATH [--input PATH ...] --out PATH` scans explicit local classfiles, directories, JARs or ZIPs. `--classpath` supplies local dependencies; no network resolution occurs. `--target-release` supports8 through25. `--rules` accepts the versioned declarative rule document in `rules/core.yaml` (JSON syntax, also valid YAML1.2). Only exact source/sink/sanitizer descriptors and supported library models are accepted.

## Rule configuration

The rule document is one JSON object, with a maximum encoded size of **1 MiB**, nesting depth **64**, and numeric token length **32**. Duplicate object keys, trailing documents, unsupported fields, nonregular files, and symbolic file entries are rejected. Both the file size and actual bytes read are bounded. String values may use the document's remaining byte budget; a smaller arbitrary string limit must not exclude a valid JVM identity.

`model` must be `faultline-core`. `version` selects the rule set and must be `1.0.0` or `1.1.0`. A `1.0.0` document declares exactly the original rules (`BG-SQL-TEXT` 1, `BG-SHELL-COMMAND` 2, `BG-PATH-ACCESS` 4) and cannot declare, sink or sanitize anything else, so documents written for the first release keep their meaning. A `1.1.0` document declares all seven: those three plus `BG-CLASS-LOAD` 8, `BG-URL-FETCH` 16, `BG-XPATH-QUERY` 32 and `BG-LDAP-FILTER` 64. The built-in model used when `--rules` is absent is `1.1.0` and `rules/core.yaml` is its exact serialization (tested for equality). `rules`, `sources`, `sinks`, and `sanitizers` are required arrays, each limited to **4,096 entries**. Empty source/sink/sanitizer arrays are explicit operator choices. Every rule of the document's version must appear exactly once with its matching context mask; a sanitizer clears exactly one context bit. Duplicate descriptors, missing fields, scalar coercion, fractions, and integers outside the signed 32-bit range are invalid.

Sources and sanitizers require exact, well-formed method descriptors with a returned value. A sink's rule, mask, and zero-based argument must agree with that descriptor. Constructors return `V` and their parameter-slot limit includes the implicit receiver. Rule descriptions (`sink`, `safe`, and `trustedImplementation`) and the allowlisted `libraryModels` descriptions are documentation strings; they do not install new executable analysis semantics. An unsupported spelling is rejected instead of being silently ignored.

Configuration validation happens before target parsing. A failure returns exit **2** and a diagnostic that does not echo configuration values. Output ownership checks still apply to any machine-readable failure report.

## Baseline configuration

`--baseline` accepts one bounded **8 MiB** SARIF **2.1.0** object containing **1–64** explicitly identified Faultline runs. Each run must identify a supported model version (`1.0.0` or `1.1.0`; fingerprints do not depend on it) and supply an explicit `results` array. Each result must identify a supported rule and an exact, lowercase, 64-character hexadecimal `faultline/v1` fingerprint. Missing or malformed identity fields cannot silently become an empty baseline. Duplicate JSON keys and trailing documents are rejected; unrelated SARIF extension fields remain allowed.

An explicit empty results array is a valid empty baseline. A partial prior report can contribute exact identities, but does not establish complete earlier coverage. Duplicate identities across runs are deduplicated. Legal long JVM names remain accepted within the overall byte budget, including names in reports emitted by Faultline itself.

## Scan output

`--format sarif` is the default; the pinned normative schema is `sarif-schema-2.1.0.json` with provenance/license in `SARIF-SCHEMA.md`. Reports include rules, detailed results, codeflows, stable partial fingerprints, invocation diagnostics, partial coverage, unfinished frontier and work/input/application counts. `--format json` emits schemaVersion1, partial, findings, diagnostics, unfinishedFrontier and workUnits. Machine findings expose rule/version/severity/confidence/model limitations, exact source/sink descriptors and instruction identities, optional source locations, bounded call path and fingerprint. Missing debug lines are not fabricated.

Exact fingerprint baselines retain results as unchanged while excluding them from the new-finding exit count. No filename wildcard baseline is accepted. Exit0 means complete with no new findings,1 complete with new findings,2 invalid input/configuration or analysis failure,3 partial coverage. Diagnostics accompany both terminal status and machine output; an unknown library differs from an incomplete analysis.

Independent controls are `--work-budget`, `--deadline-ms`, `--dispatch-limit` and `--provenance-depth`. Work/deadline/dispatch coverage limits produce partial results; provenance truncates explanation only. `--explain-dir` writes inspectable IR/CFG/frame/summary files with hashed filenames; `--explain-method` filters by a method signature and requires that directory option.

Tests validate actual complete/error/partial SARIF against the pinned schema and assert all four packaged exit codes. See `demo/README.md` for scenarios and `docs/THREAT-MODEL.md` for supported model boundaries.
