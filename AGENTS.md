# Engineering execution rules
Read docs/JAVA_PORTFOLIO_MASTER_EXECUTION_BRIEF.md and your full project contract.
Coordinator owns root, scripts, catalog, docs, CI, dependency pins, and ALL Git writes.
A01–A10 exclusively own projects/NN-slug and .work/ANN. No cross-project writes.
Use existing checkout on main; do not create worktrees. Never stage/commit/push as worker.
Use JAVA_HOME=/workspace/.tools/jdk-25; prepend its bin to PATH.
Set MAVEN_USER_HOME=/workspace/Java-portfolio/.work/wrapper and MAVEN_OPTS=-Xmx512m.
Run ./mvnw -f projects/NN-slug/pom.xml -Dmaven.repo.local=/workspace/Java-portfolio/.work/ANN/m2 -B -ntp verify.
Run spotless:apply in YOUR project only before verify. Unit suites must have real assertions.
Integration profile adds Failsafe *IT tests; missing required Docker must fail, not skip.
Keep source format Google Java Format, package io.github.cheshmakzanoon.portfolio.slug.
Project aggregator inherits ../../pom.xml; modules inherit ../pom.xml and unique artifact IDs.
Use docs/STATUS.md for exact commands, observed tests, acceptance IDs and next steps.
No fabricated evidence, metrics, gate passes, or blanket skips. A partial milestone is not completion.
Reviews are read-only outside your owned project. Send findings to owner and coordinator.
Before coordinator commits, acknowledge write pause. Do not change paused files until released.

In this cloud environment source scripts/cloud-env.sh before Maven (sets required proxy settings and trusted system CAs). Maven settings at .work/settings.xml were generated from HTTPS_PROXY; never disable TLS checks. Use fully qualified com.diffplug.spotless:spotless-maven-plugin:3.0.0:apply if prefix resolution causes unnecessary lookups.

Mutation convention: use a project-local mutation profile to neutralize inherited default PIT execution for noncritical adapters, then explicitly activate PIT for selected original core classes with nonzero mutations and a70% threshold. Do not exclude difficult branches or pretend no-test adapters prove mutation strength. Preserve survivor reports.

Cloud settings now mirror selected released dependencies to Maven Central; source cloud-env.sh before builds to regenerate secure proxy settings. Tool/classpath versions were not changed by the mirror; the new mirror ID causes one-time local cache provenance revalidation.

The ordinary ./mvnw wrapper acquires one of three shared flock build slots on Linux. Always use it; do not invoke the Maven installation directly or set PORTFOLIO_BUILD_SLOT_HELD yourself. Nested inherited invocations retain their parent slot. Windows uses the ordinary Maven wrapper without this Linux-only coordination. At most two Docker integration stacks; obtain coordinator scheduling before starting a stack. Benchmarks require an explicitly quiet host with other builds/stress/services stopped.
