# Contributing

Changes should make a documented behavior easier to understand, safer to operate, or more reliable to verify. Read the affected project's README, invariants, architecture, and security model before editing its implementation.

## Development workflow

1. Use JDK 25 and the checksum-pinned `./mvnw`. See [development setup](docs/development.md).
2. Keep changes within the affected project unless a shared build or tooling change is necessary. Projects must remain independently buildable.
3. Add a regression test for a behavioral defect. Prefer externally observable assertions and independent expected results over tests that repeat implementation details.
4. Format the affected project, run its ordinary verification, and run integration tests when changing persistence, authentication, network protocols, or process recovery.
5. Update the public contract and operations guide when behavior or configuration changes. Record the exact validation performed and any remaining limitations in the change description.

Do not weaken assertions, skip required integration tests, change expected checksums, or reduce coverage and mutation thresholds to obtain a passing build. Keep prior benchmark outcomes and failed evaluations traceable; new source requires its own validation.

## Java conventions

Use Google Java Format, explicit module boundaries, and the existing package namespace. Keep domain logic independent of HTTP, JDBC, and Spring. Make ownership of mutable state and resources explicit. Bound untrusted input before buffering or parsing it; reject malformed encoding and ambiguous protocol values. Use checked arithmetic for money and externally supplied numeric products.

Preserve the primary exception when cleanup also fails. Close resources deterministically. Give blocking operations deadlines and give background processes an explicit shutdown path. Document behavior at retries, cancellation, partial writes, and unknown outcomes.

## Review and security

A change description should explain the trigger, resulting behavior, relevant tradeoffs, and observed test results. Security reports follow [SECURITY.md](SECURITY.md). Never commit credentials, confidential data, recordings, heap dumps, local data stores, or large build outputs. Preserve upstream licenses and attribution for third-party material.
