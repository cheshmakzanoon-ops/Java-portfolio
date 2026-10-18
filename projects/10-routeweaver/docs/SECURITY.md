# RouteWeaver security boundaries

RouteWeaver stores synthetic dispatch problems in the demo. A protected deployment would handle tenant schedules, locations and cargo data; that data must not be exposed through another tenant's identifiers, logs or backups. This portfolio has not undergone external security certification or production deployment.

## Trust and access

The application defaults to a stateless protected profile using an explicitly configured OIDC issuer. Nimbus verifies JWS signature, issuer, time and configured audience; tokens require explicit expiry, nonblank subject and a tenant string matching [a-zA-Z0-9_.-]{1,200}. Because Nimbus normalizes some registered claims before conversion, Security.protectedDecoder checks the original signed JSON payload after cryptographic/default validation. Compact JWT length is bounded to 16 KiB. Numeric/list/null/blank subjects and non-string tenants are rejected with 401. The local OIDC test uses actual RSA signatures, discovery and JWKS HTTP rather than bypassing the decoder.

Only /health, / and /index.html are anonymous. The shell contains no dispatch data; users enter a Bearer token before protected requests. All API GET routes require routeweaver.read; all modifications require routeweaver.write. Verified tenant is combined with problem/job IDs in exposed queries and CAS operations. An absent foreign-tenant resource is not returned to the caller. No cookie session is used; CSRF is disabled for the Bearer API. Protect the deployment with TLS before sending real tokens over a network.

The named dev profile is an explicitly trusted disposable shortcut: it grants the synthetic demo tenant read/write access and refuses a non-loopback bind. up.sh selects it visibly. Anyone already able to access that local listener can modify demo data; it is not suitable for shared remote access. POST /v1/demo/reset requires this profile and demo tenant, refuses active jobs and never resets other tenants.

## Input and output

Raw request bodies are limited to 16 MiB before Jackson parsing, including chunked requests. Domain bounds limit jobs, vehicles, matrix cells, IDs, skills, loads and time ranges. Json disables float-to-integer and scalar coercion, null primitives and ordinal enums; numeric/boolean values cannot become text fields. Unknown properties, duplicate keys and trailing values are rejected. Problem IDs are URL-safe single path segments. SQL uses prepared parameters. Transaction/connection/lock timeouts and bounded CPU/admission queues prevent accidental unbounded work.

The reference validator recomputes the entire score and physical constraints; client/solver-provided scores never authorize publication. Mutable solver objects never reach HTTP readers. Every authoritative candidate/terminal write checks row-locked state/fence/lease; publication additionally compares active problem and plan revision. This protects integrity across expiry, cancellation and stale results, rather than trusting cancellation polling freshness. Recovery also checks executing versus submitted source identity before planning; mismatches emit no new candidate and retain historical evidence. Source identity is trusted deployment metadata supplied by the verified launcher, not a client-controlled token/URL field. Mixed-source rolling upgrades require draining or explicit new submission.

The UI uses textContent and DOM creation for untrusted strings and shows version/hash provenance. Errors return bounded stable codes/messages, never stack traces. Development credentials are clearly disposable and isolated in this project's Compose stack; real credentials must be injected securely. Do not put tokens into query strings, command logs or committed artifacts.

## Data and limits

PostgreSQL input/candidate UPDATE triggers protect application immutability; the development role owns the schema and a database administrator can bypass those protections. A production deployment needs separate migration/runtime roles, explicit grants, TLS, credential rotation, network controls, backup encryption and audit retention. The portfolio does not claim those operational controls merely because local authorization tests pass.

Local state and backups contain full tenant input and solution snapshots. Runtime files and dumps stay outside Git. Down preserves data by design; explicit demo reset removes only trusted seeded state. Source/config/seed/version metadata belongs in evidence; credential values and real identities do not.

Known scope limits: one declared depot, synthetic directed travel, stop-boundary observations, no cargo transfers or road interpolation, cooperative rather than instant cancellation, and bounded single-host/local-issuer tests. There is no commercial dispatch integration, tenant provisioning UI, distributed rate limiter, intrusion detection or production availability claim.

## Evidence

OidcTest exercises real signature/issuer/audience/expiry and original claim-type failures. HttpInputTest proves anonymous shell 200, API 401 and authenticated malformed ID/scalar 400 without persistence access. PostgresIT exercises protected HTTP scopes/tenants, chunked 413, immutable snapshots, stale-worker and publication races. See exact source fingerprints and discovered test counts in evidence; historical results do not certify later edits. Independent A09 findings and resolutions are in REVIEW.md.
