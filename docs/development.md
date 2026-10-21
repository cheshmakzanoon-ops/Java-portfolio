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

GitHub Actions uses `.github/actions/setup-toolchain` and the same checksum-pinned bootstrap, with `PORTFOLIO_TOOLS_DIR` under the runner's temporary directory. The exact vendor version `25.0.4.1+1` is not valid SemVer for `actions/setup-java`; do not truncate it or substitute an unpinned major version. The local action exports the verified runtime through `GITHUB_ENV`/`GITHUB_PATH` and verifies the Maven wrapper. Set `PORTFOLIO_TOOLS_DIR` explicitly when testing outside `/workspace`; both bootstrap and `cloud-env.sh` use its `jdk-25` subdirectory. An unset or empty override retains `/workspace/.tools`. Keep the same override when bootstrapping and sourcing the activation script.

## Project verification

Run commands from the repository root. For example:

```bash
./scripts/portfolio.sh verify thepit
./scripts/portfolio.sh integration thepit
./scripts/portfolio.sh mutation thepit
```

The ordinary gate compiles the project, runs unit/property/architecture tests, generates coverage reports, and checks formatting. The integration profile adds Failsafe `*IT` tests; a missing required dependency must fail visibly. Mutation testing is a separate, more expensive check of selected core behavior. Each selected module must independently meet its declared threshold, with a repository minimum of 70%; a strong module cannot mask a weak one. Only `KILLED` is a confirmed kill. Survivors, uncovered mutations and timeouts stay in the denominator; runner and memory errors fail verification. The launcher's report checks reject empty, failed, skipped, malformed or stale test runs. Reports must contain test cases whose outcomes agree with the suite counters. Every selected module containing conventionally named tests needs a fresh report; each integration module needs fresh Failsafe execution. The cutoff records fractional Unix time before Maven starts. A report left by an earlier run cannot certify a module that Maven did not execute.

To use a separate Maven cache or run a project directly:

```bash
./mvnw -f projects/06-thepit/pom.xml \
  -Dmaven.repo.local="$PWD/.work/thepit/m2" -B -ntp verify
```

Use a distinct cache for concurrent builds that install mutable project artifacts. The Linux wrapper limits the shared host to three builds through a FIFO lock queue; always invoke the wrapper so nested builds retain their slot. Limit application Docker stacks to two. Measurements require a quiet host and the project's declared performance protocol.

Format only the changed project:

```bash
./mvnw -f projects/06-thepit/pom.xml -B -ntp \
  com.diffplug.spotless:spotless-maven-plugin:3.0.0:apply
```

## Services and local data

```bash
./scripts/portfolio.sh up daybook
./scripts/portfolio.sh demo daybook
./scripts/portfolio.sh down daybook
```

Read the selected project's operations and security guides before startup. Demo credentials, trusted-local modes, and synthetic fixtures are development conveniences. They are unsuitable for deployment. `reset-demo` is project-specific and destructive to the selected demo state; inspect its contract before use. CLI-first projects may prepare local state without starting a background service.

Generated classes and reports live under module `target/`; local state lives under `.runtime/` or ignored `.work/`. Do not assume that recorded historical `.work/` artifacts are present in a fresh checkout. Historical performance evidence is source-specific and is not a certificate for later changes.

## Shared checks

```bash
python3 -m unittest discover -s scripts -p 'test_*.py' -v
python3 scripts/check-links.py
python3 scripts/check-architecture.py
# After the selected project has been compiled:
python3 scripts/check-architecture.py --bytecode thepit
python3 scripts/container-images.py
```

CI runs project reactors independently. Security, mutation, integration, benchmarks, and release checks have separate workflows and outcomes. A successful ordinary build does not establish that those other gates passed.

## OWASP NVD vulnerability feed

The separate OWASP Dependency-Check gate needs a live NVD API feed. Request an API key at
https://nvd.nist.gov/developers/request-an-api-key and save it as the GitHub Actions
repository secret `NVD_API_KEY`; never put the secret in the POM, workflow command
arguments, generated reports, commits, or shell history. Reusable workflows can receive
it as the optional `workflow_call` secret. Push and manual workflow runs read the same
repository secret. Fork PRs without authorized secrets will fail closed at the OWASP step.

To perform the same local scan after exporting the key securely (for example using a
local credential manager), run:

```bash
./mvnw -B -ntp org.owasp:dependency-check-maven:13.0.0:aggregate \
  -DnvdApiKeyEnvironmentVariable=NVD_API_KEY -DfailBuildOnCVSS=7
```

The Maven plugin reads the environment variable directly without printing its value as
a Maven property. A missing key, unreachable feed, empty vulnerability database or
vulnerability above the configured threshold is a **failed** gate. The independent Trivy
SBOM and container scans remain mandatory; none substitutes for a missing OWASP result.

## Dependency security

```bash
./scripts/security-scan.sh
```

The Linux x86-64 command verifies the pinned Trivy archive and executable, regenerates the actual aggregate CycloneDX inventory, updates the vulnerability database and fails on HIGH or CRITICAL library findings. Results are written to `.work/security/dependency-scan.json`; the SBOM is `target/bom.json`. The validator requires every resolved package to appear in the scan, a complete dependency graph, published license/hash metadata and unchanged POM inputs throughout generation/scanning. Old generated inventories and scan outputs are cleared before their replacement commands run. A failed download or unavailable feed is a failed check. Keep the SBOM and scanner result together when assessing a finding.

The inventory includes compile, runtime, provided and system dependencies. It excludes test dependencies, Maven plugin dependencies and container contents. The security workflow separately runs OWASP Dependency-Check, scans every pinned dependency image, and builds Java for CodeQL analysis. Their results remain separate; a clean library scan does not establish a clean container or source scan. See the [technology baseline](technology-baseline.md) for explicit security overrides.

## Diagnosing a failed gate

Start with the failing module's Surefire, Failsafe, JaCoCo or PIT report rather than a reactor-wide total. Integration tests need their actual dependency services and must not silently skip when Docker or credentials are absent. A test failure followed by a successful narrower command does not certify the original command. Preserve the failure while correcting its cause, then rerun the affected full gate.

The report checkers understand the repository's standard Maven module declarations, `integration`/`mutation` profiles and conventional Java test names. When introducing custom source roots, nonstandard test names, includes/excludes or profile activation, update the checker and its failure fixtures alongside the build. The checkers validate execution evidence; they do not prove that every production path has a useful assertion.

After a successful current scan, refresh the committed inventory and license table explicitly:

```bash
python3 scripts/dependency-inventory.py \
  --pom-inputs .work/security/pom-inputs.json --publish \
  target/bom.json .work/security/dependency-scan.json
```

The publisher records current POM hashes and exact inventory/scan digests. It rejects POM changes made after generation began. Review the resulting dependency and license changes before committing them.
