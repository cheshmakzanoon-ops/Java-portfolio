# Running and diagnosing Faultline

## Build and prepare

From the repository root, use the provisioned Java25 environment and Maven wrapper:

```bash
source scripts/cloud-env.sh
./mvnw -f projects/09-faultline/pom.xml -Dmaven.repo.local=/workspace/Java-portfolio/.work/A09/m2 -B -ntp -Pintegration,mutation verify
projects/09-faultline/scripts/up.sh
projects/09-faultline/scripts/demo.sh
projects/09-faultline/scripts/down.sh
```

`up.sh` prepares local CLI state; there is no server, database or port. The demo compiles owned fixtures with `-proc:none`, asserts a cross-method SQL finding, corrected parameter binding, a deliberately partial scan and an exact baseline. `down.sh` preserves local reports; `reset-demo.sh` removes only this project's seeded demo directory. The scripts use `.runtime/`; the executable is `cli/target/faultline-cli-1.0.0-SNAPSHOT.jar`. The source/artifact cache keys actual source/configuration and executable bytes; a stale key requires verification again.

The source-cache receipt refuses changes made during verification and atomically publishes only the original verified source hash plus the built artifact hash. A failed build or publication preserves the earlier receipt. Run its controlled, container-free regression suite independently of Maven:

```bash
python3 projects/09-faultline/scripts/test-build-cache.py
```

The five cases cover cache reuse, source drift, failed verification, failed publication and a linked receipt target. They do not execute Maven or analyzed code.

## Scan local inputs

A scan takes explicit local inputs/classpath only:

```bash
java -Xmx2g -jar projects/09-faultline/cli/target/faultline-cli-1.0.0-SNAPSHOT.jar scan \
  --input fixture.jar --classpath local-stubs --target-release 25 \
  --rules projects/09-faultline/rules/core.yaml --format sarif --out report.sarif
```

Keep `--out` and `--explain-dir` outside scanned directories. A report must not alias an input, classpath file, rule model, or baseline, including through a hard link or symbolic parent directory. Rejected destinations return exit 2 without writing an error report over the protected path. Use a separate owned explanation directory. Every DOT/JSON/SARIF output refuses symbolic entries and replaces a sibling temporary file, so hard links cannot truncate their prior target. These checks do not protect against another process racing filesystem changes.

Use `--explain-dir ir-output` and optionally `--explain-method 'owner.method(descriptor)'` to inspect method instructions, CFG/DOT, inferred frames and instantiated summaries. No Graphviz installation is required to read DOT. If parsing exhausts the budget, the frontier includes parser obligations and all concrete methods retained from the completed class prefix; those methods have not been analyzed. Missing debug metadata stays bytecode-only. SARIF contains witness steps, model limitations, diagnostics, coverage/frontier and stable identity. `--baseline prior.sarif` matches exact fingerprints and excludes only unchanged results from the new-finding count.

## Interpret results

Repair malformed rule or baseline files before increasing analysis limits. Rule files require explicit arrays and exact integer fields; strings such as `"1"`, fractional arguments, duplicate declarations, and unknown keys are errors. The rule model is capped at 1 MiB and the baseline at 8 MiB. Both reject duplicate JSON keys and trailing content. The [CLI contract](../contracts/CLI.md) lists the complete structural limits and baseline identity requirements. A SARIF report from another tool or model version cannot silently supply Faultline suppression identities.

| Exit | Meaning | Action |
| --- | --- | --- |
|0|Complete within the model; no new findings|Read limitations/uncertainty before interpreting the result.|
|1|Complete with new modeled findings|Inspect source/sink descriptors and witness; adjudicate in program context.|
|2|Invalid input/configuration or analysis failure|Read terminal and machine diagnostics; repair the explicit input or configuration.|
|3|Partial analysis|Inspect unfinished frontier and limit; absence of findings is incomplete coverage.|

Adjust `--work-budget`, `--deadline-ms`, `--dispatch-limit` and `--provenance-depth` separately. Lowering provenance truncates witnesses; it does not suppress taint. Unknown-library diagnostics differ from an unfinished frontier. No automatic dependency download occurs during scanning; provide dependencies explicitly.

## Diagnose invalid and partial results

| Observation | Boundary and next step |
| --- | --- |
| Corrupt/unsupported archive | Check the complete central directory/end record, CRC/size, manifest ambiguity, special-file type and byte/entry limits. A local ZIP header alone does not establish a complete archive. Rebuild the input archive from its source. |
| Invalid operand/return/frame | Inspect the named method and bytecode ordinal. Correct the producer or supply a supported classfile; increasing work cannot repair invalid computational kinds, missing code or a fall-through terminator. |
| `parser:` frontier entries plus concrete method IDs | Parsing exhausted its work/deadline before summary analysis. Retained classes are structural evidence only. Increase the relevant bound or reduce explicit input scope. |
| Dispatch/bootstrap coverage diagnostic | Inspect unresolved alternatives and supported models. These paths remain partial even when other summaries converged. |
| Unknown/native diagnostic with complete status | The declared conservative model converged with uncertainty; it does not mean native internals were analyzed or found safe. Review low-confidence witnesses and explicit dependencies. |

Keep the original input hashes, command, limits, terminal status and report together when comparing a rerun. A baseline changes the new-finding count, not coverage. Ordinary names retain existing fingerprints; corrected delimiter/unpaired UTF-16 identities may appear as new findings against a historical baseline that merged them.

## Performance measurements

The benchmark command is `projects/09-faultline/scripts/benchmark.sh`; coordinate a quiet host window before execution. It uses three independent JVM forks, a fixed10,000-class JAR and `-Xmx2g`, asserting full coverage,10,000 findings and<=120s each. Do not treat an explicit partial run as the successful completion gate. See [performance](PERFORMANCE.md), [security](SECURITY.md), and [current status](STATUS.md) for observed versus pending results.
