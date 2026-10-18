# Running and diagnosing ByteGuard

From the repository root, use the provisioned Java25 environment and Maven wrapper:

```bash
source scripts/cloud-env.sh
./mvnw -f projects/09-byteguard/pom.xml -Dmaven.repo.local=/workspace/Java-portfolio/.work/A09/m2 -B -ntp -Pintegration,mutation verify
projects/09-byteguard/scripts/up.sh
projects/09-byteguard/scripts/demo.sh
projects/09-byteguard/scripts/down.sh
```

`up.sh` prepares local CLI state; there is no server, database or port. The demo compiles owned fixtures with `-proc:none`, asserts a cross-method SQL finding, corrected parameter binding, a deliberately partial scan and an exact baseline. `down.sh` preserves local reports; `reset-demo.sh` removes only this project's seeded demo directory. The scripts use `.runtime/`; the executable is `cli/target/byteguard-cli-1.0.0-SNAPSHOT.jar`. The source/artifact cache keys actual source/configuration and executable bytes; a stale key requires verification again.

A scan takes explicit local inputs/classpath only:

```bash
java -Xmx2g -jar projects/09-byteguard/cli/target/byteguard-cli-1.0.0-SNAPSHOT.jar scan \
  --input fixture.jar --classpath local-stubs --target-release 25 \
  --rules projects/09-byteguard/rules/core.yaml --format sarif --out report.sarif
```

Use `--explain-dir ir-output` and optionally `--explain-method 'owner.method(descriptor)'` to inspect method instructions, CFG/DOT, inferred frames and instantiated summaries. No Graphviz installation is required to read DOT. Missing debug metadata stays bytecode-only. SARIF contains witness steps, model limitations, diagnostics, coverage/frontier and stable identity. `--baseline prior.sarif` matches exact fingerprints and excludes only unchanged results from the new-finding count.

| Exit | Meaning | Action |
| --- | --- | --- |
|0|Complete within the model; no new findings|Read limitations/uncertainty before interpreting the result.|
|1|Complete with new modeled findings|Inspect source/sink descriptors and witness; adjudicate in program context.|
|2|Invalid input/configuration or analysis failure|Read terminal and machine diagnostics; repair the explicit input or configuration.|
|3|Partial analysis|Inspect unfinished frontier and limit; absence of findings is incomplete coverage.|

Adjust `--work-budget`, `--deadline-ms`, `--dispatch-limit` and `--provenance-depth` separately. Lowering provenance truncates witnesses; it does not suppress taint. Unknown-library diagnostics differ from an unfinished frontier. No automatic dependency download occurs during scanning; provide dependencies explicitly.

The benchmark command is `projects/09-byteguard/scripts/benchmark.sh`; coordinate a quiet host window before execution. It uses three independent JVM forks, a fixed10,000-class JAR and `-Xmx2g`, asserting full coverage,10,000 findings and<=120s each. Do not treat an explicit partial run as the successful completion gate. See [performance](PERFORMANCE.md), [security](SECURITY.md), and [current status](STATUS.md) for observed versus pending results.
