# Third-party components

The executed [CycloneDX SBOM](docs/dependencies/portfolio.cdx.json) and [license inventory](docs/dependencies/LICENSES.md) record 113 external dependencies, their published licenses and exact versions. The [execution envelope](docs/dependencies/evidence.json) identifies all dependency POM inputs and scope. This is a dependency inventory; security scan outcomes are tracked separately.

Original repository code is Apache-2.0. Dependencies are resolved from published artifacts rather than copied into project source. Maven/Maven Wrapper, Spring, Lucene, Kafka and Timefold supply their documented infrastructure; JUnit/jqwik, Testcontainers, JMH, JaCoCo, PIT and ArchUnit supply build/verification tools. Tool and transitive licenses remain those published by their respective projects. The SBOM excludes build plugins, test-only scope and container contents; the inventory states these boundaries explicitly.

ByteGuard bundles the pinned OASIS SARIF schema under its [upstream license](projects/09-byteguard/contracts/OASIS-LICENSE.md), with [schema provenance](projects/09-byteguard/contracts/SARIF-SCHEMA.md). Project-specific acknowledgments and consequential mechanism references are in each case study, decision record or security/operations guide. No third-party search, matching, consensus or workflow implementation is presented as original code.

Financial, trading, identity, workflow and routing fixtures are synthetic. IndexForge's small relevance collection uses attributed original repository source with frozen authored judgments. The master brief is user-provided. No commercial deployment or employment history is implied.
