# Development

## Toolchain

The supported baseline is Linux x86-64, Eclipse Temurin 25.0.4.1+1, Maven 3.10.0 through the included checksum-pinned wrapper, Bash, and Python 3. Preview features are disabled. Native Windows and macOS execution have not been verified. Docker with Compose is needed for projects using PostgreSQL, Kafka, or an OIDC issuer.

The managed cloud bootstrap installs the pinned JDK without changing project source:

```bash
cd /workspace/Java-portfolio
bash scripts/bootstrap-cloud.sh
source scripts/cloud-env.sh
./mvnw -version
```

`cloud-env.sh` sets `JAVA_HOME`, wrapper storage, Maven heap limits, secure proxy settings, and the system Java trust store. Preserve its `MAVEN_OPTS` when adding JVM options. Never disable TLS or checksum verification to resolve a dependency failure. Outside the managed workspace, install JDK 25 using your normal trusted distribution and use the wrapper directly.

## Project verification

Run commands from the repository root. For example:

```bash
./scripts/portfolio.sh verify matchcore
./scripts/portfolio.sh integration matchcore
./scripts/portfolio.sh mutation matchcore
```

The ordinary gate compiles the project, runs unit/property/architecture tests, generates coverage reports, and checks formatting. The integration profile adds Failsafe `*IT` tests; a missing required dependency must fail visibly. Mutation testing is a separate, more expensive check of selected core behavior. The launcher's report checks reject empty, failed, skipped, or stale test runs.

To use a separate Maven cache or run a project directly:

```bash
./mvnw -f projects/06-matchcore/pom.xml \
  -Dmaven.repo.local="$PWD/.work/matchcore/m2" -B -ntp verify
```

Use a distinct cache for concurrent builds that install mutable project artifacts. The Linux wrapper limits the shared host to three builds through a FIFO lock queue; always invoke the wrapper so nested builds retain their slot. Limit application Docker stacks to two. Measurements require a quiet host and the project's declared performance protocol.

Format only the changed project:

```bash
./mvnw -f projects/06-matchcore/pom.xml -B -ntp \
  com.diffplug.spotless:spotless-maven-plugin:3.0.0:apply
```

## Services and local data

```bash
./scripts/portfolio.sh up clearledger
./scripts/portfolio.sh demo clearledger
./scripts/portfolio.sh down clearledger
```

Read the selected project's operations and security guides before startup. Demo credentials, trusted-local modes, and synthetic fixtures are development conveniences. They are unsuitable for deployment. `reset-demo` is project-specific and destructive to the selected demo state; inspect its contract before use. CLI-first projects may prepare local state without starting a background service.

Generated classes and reports live under module `target/`; local state lives under `.runtime/` or ignored `.work/`. Do not assume that recorded historical `.work/` artifacts are present in a fresh checkout. Historical performance evidence is source-specific and is not a certificate for later changes.

## Shared checks

```bash
python3 -m unittest discover -s scripts -p 'test_*.py' -v
python3 scripts/check-links.py
python3 scripts/check-architecture.py
python3 scripts/container-images.py
```

CI runs project reactors independently. Security, mutation, integration, benchmarks, and release checks have separate workflows and outcomes. A successful ordinary build does not establish that those other gates passed.
