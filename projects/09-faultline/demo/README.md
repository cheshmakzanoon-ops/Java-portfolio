# Reproducible CLI scenarios

Run `../scripts/demo.sh` from this directory, or `projects/09-faultline/scripts/demo.sh` from the repository root. The executable script owns the exact Java fixture generators and assertions; it compiles them with `-proc:none` into project-local `.runtime/demo/` and never executes their bytecode.

| Scenario | Expected observable result |
| --- | --- |
|External input returned from a helper, concatenated into SQL text in another helper|Exit1; one BG-SQL-TEXT result, exact source/sink bytecode identities, at least three witness steps.|
|Constant SQL template with external input separately bound through PreparedStatement|Exit0; no modeled finding.|
|Positive input with work budget1|Exit3; explicit partial flag and nonempty unfinished frontier.|
|Positive input with its exact prior SARIF fingerprint baseline|Exit0; retained result marked unchanged.|

`up.sh` creates local state. `down.sh` preserves reports. `reset-demo.sh` removes this demo's seeded directory only. There is no server/health endpoint or background process. The scanner's packaged integration test additionally asserts invalid-input exit2 and validates actual output against the pinned SARIF schema.

The broader120-row source corpus and deterministic benchmark generator are independently inspectable under `../corpus/` and `../evaluation/`. Current actual execution evidence is recorded in `../evidence/` and `../docs/STATUS.md`; the expectations here do not substitute for observed results.
