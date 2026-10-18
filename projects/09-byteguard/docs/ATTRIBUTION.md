# Attribution

The CFG, stack/local interpreter, lattice, worklists, summaries, witnesses, fixture labels and benchmark generator are project implementation code developed with OpenAI Codex assistance under repository-owner direction. Project agents performed independent read-only review and the owner reviewed corpus semantics before evaluation. Fixtures are synthetic owned sources; no production code or confidential data was scanned.

Supporting libraries (versions are pinned in the repository parent): ASM9.9.1 supplies structural classfile visitors/tree nodes; Jackson2.21.5 serializes reports; picocli4.7.7 parses CLI arguments. Networknt JSON Schema Validator1.5.9 validates the committed OASIS schema during tests. JUnit6.1.3, jqwik1.9.3, JaCoCo0.8.14, PIT1.22.0 and Spotless3.0.0 support verification. Supporting tools do not implement the analyzer algorithm.

The unmodified SARIF2.1.0 schema is from the OASIS SARIF specification repository. See [schema provenance and hash](../contracts/SARIF-SCHEMA.md) and [upstream repository license terms](../contracts/OASIS-LICENSE.md). Refer to dependency-distributed notices for each library's licensing; this document does not replace those notices.
