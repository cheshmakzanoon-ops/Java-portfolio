# Technology baseline

| Component | Pinned baseline | Definition |
| --- | --- | --- |
| Java | Eclipse Temurin 25.0.4.1+1 | `scripts/bootstrap-cloud.sh`; compiler release 25 |
| Maven | 3.10.0 | `.mvn/wrapper/maven-wrapper.properties`, distribution SHA-256 pinned |
| Spring Boot | 4.1.1 | Root dependency management |
| Jackson 2 / Jackson 3 | 2.21.7 / 3.1.7 | Explicit BOMs before Spring Boot BOM |
| Embedded Tomcat | 11.0.25 | Explicit root overrides for core, EL and WebSocket |
| LZ4 Java | 1.11.4 | Explicit root dependency management |
| Trivy | 0.70.0 | Checksum-pinned scanner bootstrap |
| JUnit | 6.1.3 | JUnit BOM imported before Spring Boot BOM |
| jqwik | 1.9.3 | Root dependency management |
| JaCoCo | 0.8.14 | Root verification plugins |
| PIT / JUnit platform plugin | 1.22.0 / 1.2.3 | Root mutation profile |

The root [POM](../pom.xml) is authoritative for library and plugin versions. Preview features are disabled. Project POMs declare adapter-specific dependencies and selected mutation/coverage targets. Containers and GitHub Actions use immutable digests or commit pins; `scripts/container-images.py` checks the container inventory.

## Installation and trust

The Linux x86-64 bootstrap verifies the pinned Temurin archive checksum before extraction and verifies the version before reusing an installation. The Maven wrapper verifies its distribution checksum. In the managed cloud environment, `scripts/cloud-env.sh` supplies the HTTP proxy and system Java CA trust store, and mirrors released dependencies to Maven Central. Keep TLS and checksum verification enabled.

Authoritative distributions:

- [Eclipse Temurin JDK](https://github.com/adoptium/temurin25-binaries/releases/tag/jdk-25.0.4.1%2B1)
- [Maven distribution](https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.10.0/)
- [Spring Boot dependency BOM](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/)

## Verification scope

The [JUnit baseline correction](execution/junit-baseline-correction.json) documents the BOM-order fix that selected JUnit 6.1.3 rather than 6.0.3. Earlier results retain their actual dependency baseline. Harness compatibility and deliberately failing probes are available through `scripts/foundation-probes.sh`; they do not substitute for project tests.

Security workflows scan source, dependency metadata, and pinned container images. Scan execution, feed availability, and findings are separate from version pinning. An inventory or a verified upstream tag is not a vulnerability-scan pass. The [dependency inventory](dependencies/LICENSES.md) states its scope; the [release criteria](release.md) require observed results.

## Security overrides

The Spring Boot BOM remains the default dependency set. Four library families have explicit overrides after the 2026-10-20 inventory scan identified HIGH/CRITICAL advisories in the inherited versions: Jackson 2.21.5, Jackson 3.1.5, Tomcat 11.0.24 and LZ4 Java 1.10.1. The replacements are selected through actual BOM resolution, then checked in the generated SBOM and exercised by the project builds. Jackson BOMs precede Spring Boot's BOM; Tomcat and LZ4 use explicit dependency-management entries.

Upstream release references:

- [Jackson 2.21.7 BOM](https://repo.maven.apache.org/maven2/com/fasterxml/jackson/jackson-bom/2.21.7/) and [Jackson 3.1.7 BOM](https://repo.maven.apache.org/maven2/tools/jackson/jackson-bom/3.1.7/)
- [Tomcat 11.0.25 changes](https://github.com/apache/tomcat/blob/11.0.25/webapps/docs/changelog.xml)
- [LZ4 Java advisory GHSA-mcr4-qmvw-px4g](https://github.com/yawkat/lz4-java/security/advisories/GHSA-mcr4-qmvw-px4g)

When Spring Boot incorporates these fixes, compare the effective dependency graph before removing an override. Rebuild the affected reactors, regenerate the inventory and rerun the scanner. Do not replace a security override with a suppression merely to obtain a successful gate. Scanner results are time-specific observations, not a claim that an immutable version will remain free of later advisories.

Runtime dependencies now pin PostgreSQL 18.6 Alpine and Kafka 4.2.2 by digest. The [image inventory](execution/container-images.json) records their resolved identities; the [scan observations](dependencies/security.json) retain remaining upstream findings and earlier image results. In-place PostgreSQL minor-version compatibility does not establish compatibility between Debian and Alpine data volumes. Gatehouse's new Alpine baseline requires a fresh cluster or a validated logical export/restore; do not transplant its former Debian volume.
