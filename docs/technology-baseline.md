# Pinned baseline

Maven Central metadata was rechecked 2026-10-17. Maven 3.10.0 and Spring Boot 4.1.1 are released; JUnit Jupiter 6.1.3 is released. Eclipse Temurin 25.0.4.1+1 archive checksum was verified from its GitHub release. Maven 3.10.0 SHA-512 was verified from Central. Compiler release is 25; preview features prohibited.

JDK: https://github.com/adoptium/temurin25-binaries/releases/tag/jdk-25.0.4.1%2B1
Maven: https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.10.0/
Boot: https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/

Dependency/plugin pins are in root pom.xml. Dependency resolution and foundation gate probes are required before fan-out. Container image pins remain project-specific and must be recorded by each owner.
