# RouteWeaver security boundaries

RouteWeaver stores synthetic dispatch problems in the demo. A protected deployment would handle tenant schedules, locations and cargo data; that data must not be exposed through another tenant's identifiers, logs or backups. This portfolio has not undergone external security certification or production deployment.

## Trust and access

The application defaults to a stateless protected profile using an explicitly configured OIDC issuer. Nimbus verifies JWS signature, issuer, time and configured audience; tokens require explicit expiry, nonblank subject and a tenant string matching [a-zA-Z0-9_.-]{1,200}. Because Nimbus normalizes some registered claims before conversion, Security.protectedDecoder checks the original signed JSON payload after cryptographic/default validation. Compact JWT length is bounded to 16 KiB. Numeric/list/null/blank subjects and non-string tenants are rejected with 401. The local OIDC test uses actual RSA signatures, discovery and JWKS HTTP rather than bypassing the decoder.

Only `/health`, `/`, `/index.html`, `/app.js`, and `/styles.css` are anonymous. The shell contains no dispatch data; users enter a Bearer token before protected requests. All API GET routes require routeweaver.read; all modifications require routeweaver.write. Verified tenant is combined with problem/job IDs in exposed queries and CAS operations. An absent foreign-tenant resource is not returned to the caller. No cookie session is used; CSRF is disabled for the Bearer API. Protect the deployment with TLS before sending real tokens over a network.

The named dev profile is an explicitly trusted disposable shortcut: it grants the synthetic demo tenant read/write access and refuses a non-loopback bind. up.sh selects it visibly. A loopback Host allowlist rejects DNS-rebinding names. Browser requests with an Origin must exactly match the request scheme, host, and port; opaque origins and cross-site Fetch Metadata are rejected before assigning the demo identity. Command-line clients may omit Origin. Anyone already able to make direct trusted-local requests can modify demo data; this mode is unsuitable for shared remote access. POST /v1/demo/reset requires this profile and demo tenant, refuses active jobs and never resets other tenants.

## Input and output

Authorization runs before request-body buffering. Authorized raw request bodies are limited to 16 MiB before Jackson parsing, including chunked requests. Domain bounds limit jobs, vehicles, matrix cells, IDs, skills, loads and time ranges. Json disables float-to-integer and scalar coercion, null primitives and ordinal enums; numeric/boolean values cannot become text fields. Unknown properties, duplicate keys, trailing values, root null and missing record components are rejected. Problem IDs are URL-safe single path segments. SQL uses prepared parameters. Transaction/connection/lock timeouts and bounded CPU/admission queues prevent accidental unbounded work.

The reference validator recomputes the entire score and physical constraints; client/solver-provided scores never authorize publication. Mutable solver objects never reach HTTP readers. Every authoritative candidate/terminal write checks row-locked state/fence/lease; publication additionally compares active problem and plan revision. This protects integrity across expiry, cancellation and stale results, rather than trusting cancellation polling freshness. Recovery also checks executing versus submitted source identity before planning; mismatches emit no new candidate and retain historical evidence. Source identity is trusted deployment metadata supplied by the verified launcher, not a client-controlled token/URL field. Mixed-source rolling upgrades require draining or explicit new submission.

Unexpected server failures return an opaque incident identifier and are correlated with protected service logs; programming null faults are not classified as input errors.

The UI uses textContent and DOM creation for untrusted strings and shows version/hash provenance. External JavaScript and CSS permit a same-origin Content Security Policy without inline-script permission; object embedding and framing are denied. Tokens remain in page memory rather than local/session storage. Errors return bounded stable codes/messages, never stack traces. Development credentials are clearly disposable and isolated in this project's Compose stack; real credentials must be injected securely. Do not put tokens into query strings, command logs or committed artifacts.

## Data and limits

PostgreSQL input/candidate UPDATE triggers protect application immutability; the development role owns the schema and a database administrator can bypass those protections. A production deployment needs separate migration/runtime roles, explicit grants, TLS, credential rotation, network controls, backup encryption and audit retention. The portfolio does not claim those operational controls merely because local authorization tests pass.

Local state and backups contain full tenant input and solution snapshots. Runtime files and dumps stay outside Git. Down preserves data by design; explicit demo reset removes only trusted seeded state. Source/config/seed/version metadata belongs in evidence; credential values and real identities do not.

Known scope limits: one declared depot, synthetic directed travel, stop-boundary observations, no cargo transfers or road interpolation, cooperative rather than instant cancellation, and bounded single-host/local-issuer tests. There is no commercial dispatch integration, tenant provisioning UI, distributed rate limiter, intrusion detection or production availability claim.

## Evidence

OidcTest exercises real signature/issuer/audience/expiry and original claim-type failures. HttpInputTest proves anonymous shell/assets 200, API 401, authenticated malformed ID/scalar 400, and development-mode cross-origin/rebound-Host 403 without persistence access. PostgresIT exercises protected HTTP scopes/tenants, chunked 413, immutable snapshots, stale-worker and publication races. See exact source fingerprints and discovered test counts in evidence; historical results do not certify later edits. Reviewed findings and resolutions are in [REVIEW.md](REVIEW.md).

## Runtime ownership

Launch receipts bind source and both executable JAR hashes; symbolic-link artifacts and changed bytes are refused. Build/up/down serialize on a lifecycle lock. The launcher identifies its Java process by PID start ticks, executable and exact JAR argument, verifies listening-socket ownership during readiness, and signals through Linux pidfds. A missing/mismatched live ownership record is an error. These controls protect accidental stale launch and PID recycling; they do not defend against a malicious local user able to rewrite source and receipts.

Graceful shutdown fences its generation before requeue; failed database communication falls back to lease expiration. SQL timeouts are supplemented by default PostgreSQL connection/socket limits. Additive schema constraints reject cross-job incumbent references and illegal lease/state shape. The schema-owning development role can still override constraints, so database administration remains a separate trust boundary.

## Pinned container scan

The current official PostgreSQL 18.6 Alpine digest (`77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873`) was scanned with Trivy 0.70.0 and its current official vulnerability database on 20 October 2026. It has no reported HIGH/CRITICAL operating-system findings. Its bundled `gosu` 1.19 executable was built with Go 1.24.6 and has 24 HIGH and one CRITICAL Go standard-library findings. These remain upstream image findings; they are neither suppressed nor resolved by the application's Java dependency updates. The local database stays on loopback with disposable credentials. Re-scan the exact digest before deployment and adopt a maintained upstream rebuild when available.
