# Faultline

Faultline is an offline Java bytecode analyzer. It reads explicitly selected classfiles and archives without loading their classes, then traces modeled external inputs into seven kinds of sink: SQL command text, shell `-c` commands, filesystem paths, class names given to `Class.forName`/`ClassLoader.loadClass`, network destinations (`URL`, `Socket`, `HttpRequest`), XPath expressions and LDAP search filters. External inputs are environment and property reads, console input and, since model 1.1.0, servlet request parameters, headers and URL parts (`javax` and `jakarta`). Each finding includes a source, sink, bytecode identity, and bounded witness.

The analyzer implements its own control-flow graph, frame interpreter, taint lattice, fixed-point worklists, and interprocedural summaries. ASM supplies structural classfile parsing. Findings describe the declared model; they do not prove exploitability or establish that a program is secure.

## Build and run

Requirements: JDK 25, Python 3, and the repository Maven wrapper. No Docker, service process, network dependency resolution, or execution of target code is required. Run from the repository root; in the managed cloud workspace, first source `scripts/cloud-env.sh`.

```bash
./mvnw -f projects/09-faultline/pom.xml -B -ntp -Pintegration verify
./projects/09-faultline/scripts/up.sh
./projects/09-faultline/scripts/demo.sh
java -Xmx2g -jar projects/09-faultline/cli/target/faultline-cli-1.0.0-SNAPSHOT.jar scan \
  --input fixture.jar --classpath local-stubs --target-release 25 \
  --rules projects/09-faultline/rules/core.yaml --format sarif --out report.sarif
```

`up` prepares the CLI; there is no server. The asserted demo checks a cross-method SQL flow, a separately bound SQL parameter, partial analysis, and exact baseline matching. Fixture compilation disables annotation processing. `down` retains reports; `reset-demo` removes only owned demo files. Changed source or executable bytes invalidate the scripts' verified-artifact cache.

Keep reports and explanation files outside scanned directories. Output validation refuses aliases of inputs, classpath files, rules, and baselines. ZIP/ZIP64 indexes and actual entry bytes are bounded and verified before analysis; explicit special files are rejected. SARIF, JSON and every DOT explanation replace owned output entries without truncating hard-linked input bytes. An unsafe destination produces exit 2 without writing a failure report over the protected file.

Rule models and SARIF baselines have a [strict, bounded configuration contract](contracts/CLI.md): duplicate keys, trailing documents, numeric coercion, impossible method contracts, and malformed baseline identities fail before analysis. Legal long JVM names remain compatible with Faultline's own reports within the document byte budget.

## Interpret a scan

| Exit | Meaning |
| --- | --- |
| `0` | Analysis completed within the model, with no new findings |
| `1` | Analysis completed with new modeled findings |
| `2` | Invalid input/configuration or analysis failure |
| `3` | Partial analysis with explicit unfinished coverage |

Unknown-library uncertainty differs from clean values and from partial coverage. Native alternatives remain conservative even alongside clean overrides; unsupported/bootstrap or exhausted-budget paths retain explicit unfinished coverage. The supported frame model validates computational kinds, reachable terminators and exception ranges, while allowing valid branch-local reuse through TOP merges. A SARIF baseline matches exact finding fingerprints; it cannot suppress findings with a filename wildcard. Report limitations and unfinished coverage remain relevant even when the new-finding count is zero.

## Code map

| Module | Responsibility |
| --- | --- |
| `analysis-core` | Bounded input reader, CFG, frame/taint analysis, dispatch, summaries, and budgets |
| `report` | Findings, stable identities, witnesses, SARIF, and opt-in IR explanations |
| `cli` | Local input/configuration validation, output protection, and exit policy |
| `evaluation` | Frozen synthetic corpus, adversarial boundaries, and packaged CLI checks |

Read [architecture](docs/ARCHITECTURE.md) and the [engine walkthrough](docs/ENGINE.md) for implementation details, [security](docs/SECURITY.md) for trust boundaries, and [operations](docs/OPERATIONS.md) for scan controls. The [corpus methodology](corpus/README.md) explains fixed labels and accuracy limits. [Verification status](docs/STATUS.md) separates current checks from historical results; [performance evidence](docs/PERFORMANCE.md) applies only to its recorded source revision.

The historical frozen corpus recorded 60 true positives and 60 true negatives for the original three rules. A separate, additive [extension corpus](corpus/README.md#extension-corpus-for-model-110) of 38 hash-pinned fixtures covers the four newer rules and the web sources (20 positives, 18 negatives, 0 misclassified). Neither estimates field accuracy. Object-insensitive aliases and coarse shell-array modeling have known false positives outside that corpus; see the [threat model](docs/THREAT-MODEL.md).
