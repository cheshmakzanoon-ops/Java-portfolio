# Pinned baseline

Maven Central metadata was rechecked 2026-10-17. Maven 3.10.0 and Spring Boot 4.1.1 are released; JUnit Jupiter 6.1.3 is released. Eclipse Temurin 25.0.4.1+1 archive checksum was verified from its GitHub release. Maven 3.10.0 SHA-512 was verified from Central. Compiler release is 25; preview features prohibited.

JDK: https://github.com/adoptium/temurin25-binaries/releases/tag/jdk-25.0.4.1%2B1
Maven: https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.10.0/
Boot: https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/

Dependency/plugin pins are in root pom.xml. Dependency resolution and foundation gate probes are required before fan-out. Container image pins remain project-specific and must be recorded by each owner.

All cloud Maven requests now use the platform HTTP proxy and the system Java CA trust store with TLS verification enabled. Released dependencies are mirrored to Maven Central to avoid dependency-declared snapshot repositories and prefix metadata delays; no snapshot dependencies are selected. The mirror identifier change causes one-time cache-origin revalidation.

The PIT1.22.0/JUnit-platform plugin1.2.3 combination was executed on Java25 using the temporary foundation fixture:4 mutations,3 killed,75%. This is harness compatibility evidence only. `scripts/foundation-probes.sh` reproduces controlled test/format/coverage failures in an isolated temporary module.

GitHub action pins were resolved read-only from official repositories: checkout v5 fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09; setup-java v5 b6effb05e454b25005698d916606bdc6ffcbf961; upload-artifact v5 330a01c490aca151604b8cf639adc76d48f6c5d4. CI execution remains separately verified.

On 2026-10-18, an effective-dependency audit found that importing Spring Boot’s BOM first selected JUnit 6.0.3 despite the declared 6.1.3 property. An isolated Java 25 fixture passed one Jupiter test, 20 jqwik cases and three PIT mutations on resolved JUnit 6.1.3. The coordinator drained all three build slots before moving the JUnit BOM first. Historical project results retain their actual 6.0.3 dependency selection; final project runs must use 6.1.3. Exact evidence is in [the baseline correction record](execution/junit-baseline-correction.json). Production library versions are unchanged.
