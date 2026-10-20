# Security model

TenantShield trusts the configured identity provider, the service processes and their SQL credentials, PostgreSQL administration, and the configured decision-service endpoint. Caller tokens, paths, bodies and policy definitions are untrusted. The document API is a fictional read service, not a production deployment template.

## Trust boundaries

| Boundary | Enforcement | Residual constraint |
| --- | --- | --- |
| Caller → identity | Both services use Spring/Nimbus with a fixed issuer/JWKS URI, configured audience (default `tenantshield`), RS256, required expiry/issued-at and zero timestamp leeway | Issuer administration, key distribution and service configuration remain trusted |
| Verified token → principal | After signature verification, original signed JSON must contain a bounded nonblank string subject equal to the verified identity; duplicate claims are rejected | Tenant and role claims never substitute for database membership |
| Caller → policy inputs | Context accepts only bounded `purpose` and int64 `hour`; membership and resource fields are assembled from PostgreSQL | Caller `hour` is an untrusted annotation, not a server clock; schedules need a server-owned time attribute |
| Service → tenant data | Prepared SQL, composite tenant foreign keys and transaction-local RLS context; no session tenant survives pool reuse | Anyone with raw runtime SQL credentials can set tenant context; RLS is not a defense against hostile credential holders |
| Deployment → database | Startup checks all nine protected tables, RLS/FORCE RLS and ownership; runtime cannot be a superuser, BYPASSRLS, CREATEROLE, CREATEDB, or assume an RLS-bypassing role | The guard runs at startup; privileged database administration remains trusted after startup |
| Decision → document read | One fresh HTTP call, complete typed response, strict JSON and bounded bytes/time; no decision cache | A remote response cannot make a later resource write atomic with revocation |
| Authorization → audit | Redacted audit and decision commit together; audit/commit failure refuses an allow | Audit identifiers remain sensitive tenant data; retention is a deployment responsibility |

The [decision decoder](../decision-service/src/main/java/io/github/cheshmakzanoon/portfolio/tenantshield/DecisionApplication.java) and [document decoder](../demo-resource-server/src/main/java/io/github/cheshmakzanoon/portfolio/tenantshield/ProtectedDocuments.java) perform additional original-claim shape checks because Nimbus can normalize a numeric subject into a string. These checks run after mature signature verification; they do not implement cryptography or derive discovery URLs from unverified claims. Compact tokens over 16 KiB are refused before decoding; the original-payload parser also limits depth, tokens, strings and numbers. The local password grant exists only to obtain real disposable Keycloak tokens for the demonstration.

The route tenant resolves membership for `(issuer, subject)`. JWT roles and caller headers confer no authority. Administrative operations recheck the active database administrative flag in their transaction. Nonmembers cannot resolve resource or policy metadata; a retained revoked membership may observe the tenant epoch, while a nonexistent membership receives zero public versions/epoch. Tenant existence is not a secret in the fictional SaaS.

## Bounded work and protocol validation

| Surface | Bound or rejection |
| --- | --- |
| Decision HTTP body | Read at most 262,145 bytes; reject over 262,144 before Jackson, including unknown/dishonest Content-Length |
| Decision concurrency | 64 owned request leases, 64 Tomcat threads, 128 connections, accept queue 64 |
| Quotas | 5,000 accepted requests/second per fixed-window bucket; 1,000 tracked names plus one shared overflow bucket; current-window/live counters cannot be evicted |
| Policy syntax | 16,384 UTF-8 bytes, 512 nodes, parser recursion depth 32, 64 set literals, 256 UTF-16 units per string, well-formed Unicode |
| Policy definition | 100 roles, 200 rules, 2,000 grants; 262,144 serialized bytes; 500-plan Caffeine maximum-size policy |
| JDBC | Pool eight, 3-second acquisition/lock timeouts, 5-second statement/socket limits |
| Protected service | 32 threads, 64 connections, accept queue 32; authorization connect/read limits 3/5 seconds |
| Decision response | HTTP 200 + JSON, 65,536 bytes, depth eight, 4,096 tokens; no duplicate/trailing/unknown/missing/null fields or scalar coercion |
| Audit reads | Current tenant administrator, latest 100 entries |

Fixed windows allow a boundary burst of up to twice the nominal quota. Overflow names share a budget to keep tenant churn bounded; tenant metrics do not expose the shared aggregate. These are local process limits, not a cluster-wide rate limiter or fair-share guarantee.

The application request mapper rejects duplicate keys, trailing documents, unknown properties, lossy integer conversion and numeric enum values. The outbound [authorization client](../demo-resource-server/src/main/java/io/github/cheshmakzanoon/portfolio/tenantshield/DocumentAuthorization.java) additionally disables scalar coercion and validates every decision field. ALLOW requires `MATCHED_ALLOW`, positive epoch/versions, a UUID and sorted unique rule IDs. Incomplete, contradictory, malformed, oversized and failed responses become 503, never an allow.

The [policy language](../policy-core/src/main/java/io/github/cheshmakzanoon/portfolio/tenantshield/PolicyLanguage.java) has no reflection, imports, regex operators, dynamic execution, loops, network or arbitrary functions. Static types prevent boolean ordering and mixed-type comparisons. Missing attributes remain UNKNOWN through negation. Compiler-owned plans cannot be forged through supplied indexes; cache identity includes tenant, immutable revision and digest. Persisted policy corruption is unavailable, not an invalid caller request.

Publication and rollback accept exactly one strong quoted nonnegative int64 `If-Match` version. Weak tags, lists, wildcards, inserted quotes, signs, leading zeroes and overflow return 400 before database access. A valid stale value returns 409. Current administrative membership is independently required. Rollback creates a higher immutable revision rather than rewinding a pointer.

## Audit, credentials and deployment

Audit stores a random decision UUID, SHA-256 principal reference `(issuer,NUL,subject)`, action/resource references, epoch, result and reason. It contains no bearer token or complete attribute set. Principal hashes are pseudonymous, not anonymous; resource/action identifiers remain visible to current tenant administrators. Runtime can insert/read audit but cannot update/delete it, create/delete tenants, or migrate the schema.

Committed passwords are disposable loopback fixtures. Compose ports and service bindings default to 127.0.0.1. Use separate migration/runtime credentials; keep migration credentials outside the application environment. Replace fixture credentials and issuer settings before nonlocal use. Production TLS termination, secret rotation, retention/erasure, keyed pseudonyms, remote artifact attestation, multi-region consistency and certifications are outside this example. See the repository [security policy](../../../SECURITY.md) for reporting and [operations](OPERATIONS.md) for recovery.

Pinned runtime image identities are in the repository [container inventory](../../../docs/execution/container-images.json). Digest pinning establishes selected content, not freedom from vulnerabilities; unresolved upstream findings and repository-wide scans remain separate from authorization regression results.
