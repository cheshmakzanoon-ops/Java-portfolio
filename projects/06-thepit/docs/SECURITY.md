# Security boundaries

The Pit uses synthetic cash and inventory. It has no exchange connectivity, deposits or real-money execution. Journals contain account identifiers, commands and synthetic balances; retain them in a private directory with restricted filesystem permissions.

| Boundary | Enforcement | Remaining responsibility |
| --- | --- | --- |
| Local demonstration | `trusted-local-demo` refuses non-loopback binding and requires `X-Demo-Account` | Any local caller can impersonate an account; do not expose through a tunnel or proxy |
| Protected identity | [StrictIdentityDecoder](../gateway/src/main/java/io/github/cheshmakzanoon/portfolio/thepit/StrictIdentityDecoder.java): issuer/JWKS, RS256, audience, required expiry/issued-at, skew and original signed claim types | Operate the issuer, TLS termination and key lifecycle |
| Account ownership | Subject equals command account, including admin INIT; private reads bind to subject | Provision account identities consistently |
| Authority | `thepit:read`, `thepit:write`, `thepit:admin`; permission checked before route-specific input processing | Deployment-specific access and rate policy |
| Typed input | [CommandJson](../journal/src/main/java/io/github/cheshmakzanoon/portfolio/thepit/CommandJson.java), command preflight, decoded query allowlists | Clients must use the published wire types and stable IDs |
| Storage | Single file lock, bounded frame decoding, CRC32C, snapshot SHA-256/ancestry/ledger checks, atomic restore | Trusted filesystem ownership and retained local storage honoring force |
| Resource use | Bounded dispatch/pending queues, four active HTTP handlers, body/frame/retention limits, CLI request/response deadlines | External connection/rate limits and workload-specific sizing |

[HttpApiTest](../gateway/src/test/java/io/github/cheshmakzanoon/portfolio/thepit/HttpApiTest.java) uses a real local RSA issuer and signed tokens. It checks foreign signatures, incorrect issuer/audience, expired/missing/malformed claims, scopes and cross-account access. This fixture does not implement interactive login or certify issuer operations. Original numeric subject/scope/time claims cannot acquire authority through Nimbus normalization.

HTTP and durable decoders reject numeric enum ordinals, integer/float/boolean-to-text coercion, text-to-number coercion, null primitives, fractional integers, duplicate JSON keys, unknown fields and trailing documents. Names and values in queries are URL-decoded once, with duplicate decoded names and endpoint-unknown parameters rejected. Authentication and scope failures take precedence over query details. Responses use `Cache-Control: no-store` and stable error codes without stack traces.

The standalone CLI sets JDK HTTP server request and response deadlines to five seconds before creating the first server. Explicit `-Dsun.net.httpserver.maxReqTime` / `maxRspTime` settings take precedence. These are JVM-wide JDK settings, checked periodically by the server rather than exact per-route stopwatches. Embedded users must configure equivalent JVM policy before their first JDK HTTP server. [HttpDeadlineIT](../simulator/src/test/java/io/github/cheshmakzanoon/portfolio/thepit/HttpDeadlineIT.java) verifies four incomplete uploads cannot retain all handler permits indefinitely.

Bodies and journal payloads are at most 65,536 bytes; snapshots at most 16 MiB; runs at most 1,100,000 unique commands; books at most 100,000 open orders. Parsed state and journal history are retained in memory within these finite limits. This is not a multitenant, unlimited-history service.

CRC32C and snapshot hashes detect accidental corruption and bind a snapshot to its retained command ancestry. Semantic validation rejects inconsistent balances, reservations, order identities and results. **These mechanisms do not authenticate hostile filesystem changes**: a writer who can replace journal bytes can also recompute checksums and hashes. They do not establish hardware power-loss durability, replication or disk-loss recovery.

No credentials or authentication tokens are persisted in scripts or evidence. A production TLS proxy, access-log retention policy, issuer availability policy, rate limiting and operational monitoring remain deployment work; the local simulator does not imply production trading controls.
