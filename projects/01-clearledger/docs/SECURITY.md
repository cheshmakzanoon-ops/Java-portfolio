# Security model

ClearLedger protects tenant operations through verified identity, operation scopes, tenant-qualified SQL, and restricted database write capabilities. It is an educational accounting system, not a security certification or a regulatory product. Use synthetic data only.

## Identity and authorization

Protected mode validates OIDC discovery, JWKS RSA signatures, issuer, audience `clearledger`, and expiry. Subjects must be nonblank strings of at most 255 characters; the tenant claim must be a canonical UUID string. A bounded strict parser checks the original signed JSON claim types after cryptographic validation, preventing claim coercion from changing their meaning.

Spring Security requires `ledger.read`, `ledger.write`, or `ledger.reconcile` according to the operation. Tenant reporting requires `ledger.admin`. Global shadow replay is a platform operation requiring the separate `ledger.system` scope. The verified principal supplies tenant identity; request DTOs have no tenant selector.

Integration tests use a loopback discovery/JWKS issuer and real RSA signatures. They exercise invalid signatures, issuer, audience, expiry, scopes, and cross-tenant identifiers. Two HTTP application instances share PostgreSQL to verify durable idempotency across instances.

## Development authentication

`up.sh` explicitly selects the `dev` profile, which uses a known disposable HS256 key and local issuer. Startup rejects a non-loopback `server.address` in that profile. A reverse proxy can defeat the intent of a loopback binding: do not expose this profile through one.

Protected mode is the default and requires a reachable trusted issuer. Credentials and signing keys belong in runtime configuration, never in Git.

## Database boundary

The runtime database role is a trusted application capability, not a tenant login. It may read tenant data for the application and manage reconciliation and projections. It cannot directly modify immutable posted journals, postings, or authoritative balances. Privileged functions use fixed schema resolution and restricted grants.

Tenant-user isolation relies on verified adapter identity, parameterized tenant predicates, and composite tenant-aware foreign keys. A database administrator, compromised host, or direct misuse of the application database credential is outside this boundary. Database access is not a substitute for HTTP authorization.

## Input and output handling

The adapter bounds actual POST bodies, including chunked requests, to 1 MiB. Journal batches contain at most 100 lines, statement pages at most 200 rows, CSV imports at most 5,000 rows, and decimal strings at most 64 characters. JSON scalar-to-string coercion, ordinal enums, and duplicate keys are rejected.

CSV imports must be valid UTF-8 and contain no NUL characters. Invalid encoding is rejected before a transaction starts, so replacement characters cannot silently change financial references or source identities. Within a valid file, malformed rows receive explicit rejection reasons. Export quotes cells and neutralizes spreadsheet formula prefixes.

SQL values are bound parameters. Terminal validation errors return structured codes rather than stack traces. Database rollback failures are retained as suppressed diagnostics without replacing the original transaction error or changing its retry classification.

## Reporting an issue

Follow the repository's [security reporting policy](../../../SECURITY.md). Include the affected operation, required privileges, and a synthetic reproducer. Do not publish credentials or sensitive financial input.
