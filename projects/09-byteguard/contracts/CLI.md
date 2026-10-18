# ByteGuard CLI/output contract version1

`byteguard scan --input PATH [--input PATH ...] --out PATH` scans explicit local classfiles, directories, JARs or ZIPs. `--classpath` supplies local dependencies; no network resolution occurs. `--target-release` supports8 through25. `--rules` accepts the versioned declarative rule document in `rules/core.yaml` (JSON syntax, also valid YAML1.2). Only exact source/sink/sanitizer descriptors and supported library models are accepted.

`--format sarif` is the default; the pinned normative schema is `sarif-schema-2.1.0.json` with provenance/license in `SARIF-SCHEMA.md`. Reports include rules, detailed results, codeflows, stable partial fingerprints, invocation diagnostics, partial coverage, unfinished frontier and work/input/application counts. `--format json` emits schemaVersion1, partial, findings, diagnostics, unfinishedFrontier and workUnits. Machine findings expose rule/version/severity/confidence/model limitations, exact source/sink descriptors and instruction identities, optional source locations, bounded call path and fingerprint. Missing debug lines are not fabricated.

Exact fingerprint baselines retain results as unchanged while excluding them from the new-finding exit count. No filename wildcard baseline is accepted. Exit0 means complete with no new findings,1 complete with new findings,2 invalid input/configuration or analysis failure,3 partial coverage. Diagnostics accompany both terminal status and machine output; an unknown library differs from an incomplete analysis.

Independent controls are `--work-budget`, `--deadline-ms`, `--dispatch-limit` and `--provenance-depth`. Work/deadline/dispatch coverage limits produce partial results; provenance truncates explanation only. `--explain-dir` writes inspectable IR/CFG/frame/summary files with hashed filenames; `--explain-method` filters by a method signature and requires that directory option.

Tests validate actual complete/error/partial SARIF against the pinned schema and assert all four packaged exit codes. See `demo/README.md` for scenarios and `docs/THREAT-MODEL.md` for supported model boundaries.
