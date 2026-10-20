# Security policy

These projects use synthetic data to demonstrate engineering techniques. They have no production security certification, service-level guarantee, or established vulnerability-response window. Do not use demo credentials or trusted-local modes on a public network.

## Reporting

For a sensitive vulnerability, use GitHub's **Report a vulnerability** action in the repository's Security tab when available. If private reporting is unavailable, open an issue requesting a private contact without including exploit details, credentials, personal data, or confidential input. Public, non-sensitive defects can use the normal issue tracker.

Include the affected project and revision, prerequisites, impact, a minimal synthetic reproduction, and any relevant logs with secrets removed. Do not submit real account data or production recordings.

## Trust boundaries

- Bind demos to loopback. A loopback address alone does not provide authentication or browser request protection.
- Use each project's protected authentication mode before exposing HTTP endpoints. Validate issuer, audience, signature, expiry, scopes, and resource ownership as documented by that project.
- Keep database and issuer credentials in local configuration or secure environment bindings. Check in variable names and examples only.
- Treat source archives, class files, policy documents, CSV, JSON, and recordings as untrusted input. Limits and strict validation remain necessary even for offline tools.
- Preserve TLS verification, artifact checksums, pinned container identities, and third-party license notices.

Project-specific guides document authorization, tenant isolation, parser limits, durable state, and recovery assumptions. Development dependencies and containers also have an attack surface; the [dependency inventory](THIRD_PARTY.md) is separate from a vulnerability scan. Missing vulnerability-feed access or an unexecuted workflow must remain visible as an unverified result.

## Validation scope

Regression tests cover specific failure modes; they are not a penetration test. Historical security evidence applies to the identified source and environment. See the [acceptance matrix](docs/acceptance-matrix.md) for repository-wide checks and each project's status page for current limits.

## Security checks and dependency updates

Run `./scripts/security-scan.sh` to resolve the current reactor inventory and scan libraries with the current Trivy database. The command verifies the scanner distribution and fails on HIGH/CRITICAL findings or unavailable data. [Technology baseline](docs/technology-baseline.md) records explicit dependency overrides and their upstream sources. [Dependency metadata](docs/dependencies/evidence.json) binds the committed inventory to its POM inputs.

The hosted security workflow also runs OWASP Dependency-Check, pinned-image scans and CodeQL with an explicit source severity gate. Those checks cover different inputs and have separate results. Report feed failures and scanner execution errors as incomplete validation, retain the original finding, and rerun after applying a fix. Do not reduce severity thresholds, disable TLS, exclude a dependency or silently count an unavailable scan as successful.

For changes to durable state or authorization, review both the normal path and the failure boundary: a lost response after commit, a stale lease after restart, malformed authoritative metadata, tenant-scoped cache reuse and interrupted shutdown. Include a regression at the boundary where the previous behavior failed. Local fixture credentials and synthetic attacks must remain isolated from real services and data.

The [2026-10-20 scanner observations](docs/dependencies/security.json) retain the original findings and selected replacement image results. Application library findings were corrected by explicit version overrides. The selected PostgreSQL 18.6 image still has 25 HIGH/CRITICAL Go standard-library findings in its upstream `gosu` binary; the Kafka 4.2.2 image still has seven HIGH findings across bundled Java libraries and the OS package `libexpat`. Keycloak and Ryuk had none at those severities in the observed database. These image findings remain unresolved release failures. Local-only exposure and an unused vulnerable feature do not replace an upstream fix or a documented reachability assessment.
