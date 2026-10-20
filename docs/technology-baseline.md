# Technology baseline

| Component | Pinned baseline | Definition |
| --- | --- | --- |
| Java | Eclipse Temurin 25.0.4.1+1 | `scripts/bootstrap-cloud.sh`; compiler release 25 |
| Maven | 3.10.0 | `.mvn/wrapper/maven-wrapper.properties`, distribution SHA-256 pinned |
| Spring Boot | 4.1.1 | Root dependency management |
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
