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
