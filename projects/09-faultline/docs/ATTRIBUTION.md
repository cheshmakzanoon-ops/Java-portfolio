# Dependencies and attribution

Faultline maintains its control-flow graph, frame interpreter, taint lattice, fixed-point worklists, summaries, witnesses, and synthetic evaluation fixtures within this project. Fixture sources contain no customer code or confidential production data. Labels are frozen before evaluation; their methodology and limits are described in the [corpus documentation](../corpus/README.md).

Supporting dependencies are pinned in the repository parent:

| Dependency | Role |
| --- | --- |
| ASM | Structural classfile visitors and tree nodes |
| Jackson | JSON report serialization and configuration parsing |
| picocli | Command-line parsing |
| networknt JSON Schema Validator | Test validation against the retained SARIF schema |
| JUnit, jqwik, JaCoCo, PIT, Spotless | Tests, property checks, coverage, mutation analysis, and formatting |

The [repository dependency inventory](../../../docs/dependencies/LICENSES.md) records versions and published license metadata. Distributed dependency notices continue to apply; this document does not replace them.

The unmodified SARIF 2.1.0 schema comes from the OASIS SARIF specification repository. Its [provenance and hash](../contracts/SARIF-SCHEMA.md) and [upstream license terms](../contracts/OASIS-LICENSE.md) are retained under `contracts/`.
