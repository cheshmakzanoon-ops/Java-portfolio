# Security and trust boundaries

Protected mode uses actual OIDC discovery and JWKS RSA signature validation, issuer, audience `clearledger`, required expiry, nonblank bounded string subject and canonical UUID string tenant claim. After cryptographic validation, a bounded strict parser also checks original signed JSON identity types because Nimbus can coerce registered subject claims. Spring Security verifies operation scopes: ledger.read, ledger.write, ledger.reconcile, tenant reporting ledger.admin, and distinct platform-only ledger.system for global shadow replay. The verified principal supplies tenant identity; DTOs have no tenant field. SQL always binds tenant identifiers and uses composite foreign keys.

The local OIDC integration fixture serves discovery and public JWKS over loopback and signs real RSA tokens. It exercises wrong signature, issuer, audience, expiry, tenant isolation and scopes. Two HTTP application instances share PostgreSQL and preserve a common idempotency receipt.

`up.sh` explicitly activates `dev`: a known disposable HS256 key and loopback-only issuer enable a repeatable local demo. The application rejects a non-loopback server.address in that profile. Do not expose the development profile through a public proxy. Protected mode is the default and requires reachable trusted issuer configuration; real credentials never belong in Git.

The database runtime role is a trusted application capability, not a login exposed to end users. It can SELECT tenant data for the application and manage reconciliation/projections, but cannot modify immutable posted source or authoritative balances. Cross-tenant isolation is enforced in verified adapter identity and parameterized repository predicates; DBA/host compromise and direct misuse of an application credential are outside tenant-user threat model. Privileged functions have fixed schema resolution and restricted grants.

Jackson coercion from numeric/boolean scalars to monetary/name strings and ordinal enums is disabled; malformed DTO types fail before journal creation.

The adapter bounds actual POST bytes to1MiB, including chunked bodies; journal lines≤100, statement page≤200, CSV rows≤5000 and decimal text≤64 characters. Terminal errors produce structured codes, not stack traces. CSV import records rejection reasons; export prefixes spreadsheet-trigger characters and quotes fields. Names and references use bound SQL, never query concatenation.

No card, bank-network or personal data is processed. All accounting data is synthetic. This is not a security certification or regulatory product.
