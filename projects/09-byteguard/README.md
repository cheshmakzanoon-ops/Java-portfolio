# ByteGuard

ByteGuard inspects explicitly selected local Java classfiles and JARs without loading their classes. Its original bytecode interpreter traces modeled external inputs to SQL text, shell `-c` command construction, and filesystem access paths. Findings explain a bounded model; they are neither proof of exploitability nor a security certification.

Requires JDK 25, Python 3, and the repository Maven wrapper. No Docker, network resolution of target dependencies, service process, or target execution is required.

```bash
./mvnw -f projects/09-byteguard/pom.xml -B -ntp verify
./projects/09-byteguard/scripts/up.sh
./projects/09-byteguard/scripts/demo.sh
java -Xmx2g -jar projects/09-byteguard/cli/target/byteguard-cli-1.0.0-SNAPSHOT.jar scan \
  --input fixture.jar --classpath corpus/stubs --target-release 25 \
  --rules projects/09-byteguard/rules/core.yaml --format sarif --out report.sarif
```

The demo compiles trusted sources with annotation processing disabled, traces a cross-method SQL finding, verifies separately bound SQL parameters are clean, demonstrates an explicit partial frontier, and checks an exact baseline. `up` prepares local CLI state; `down` preserves reports; `reset-demo` removes only owned demo files. Scripts rebuild and test when the source fingerprint changes, and reuse the verified artifact otherwise.

Exit codes: `0` complete with no new findings; `1` complete with findings; `2` invalid configuration/input or analysis failure; `3` partial. An exact SARIF fingerprint baseline marks unchanged findings; it cannot suppress a new identity through a filename wildcard. Unknown calls produce uncertainty diagnostics, distinct from partial analysis or clean values.

Read [architecture](docs/ARCHITECTURE.md), [engine walkthrough](docs/ENGINE.md), [threat model](docs/THREAT-MODEL.md), [corpus methodology](corpus/README.md), [case study](docs/CASE_STUDY.md), [interview guide](docs/INTERVIEW.md), and [observed status](docs/STATUS.md). Performance uses `scripts/benchmark.sh` and publishes actual measurements in `evidence/benchmark.json` after a successful full run.

Current observed checks:65 methods pass in a fresh clean integration/mutation run; core94.31%line/83.54%branch coverage;164/177 targeted mutants killed; frozen corpus60TP/60TN with zeroFP/FN. Three full10,000-class scans took3.9325/4.0275/4.3934seconds in the reserved four-core host, and independent A08 review closed. The current packaged demo passes twice with exact artifact reuse. These results cover the declared bounded model; repository-wide clean-checkout and final release checks remain separate. Historical failures and the old enclosing-shell exit discrepancy remain preserved in the evidence.
