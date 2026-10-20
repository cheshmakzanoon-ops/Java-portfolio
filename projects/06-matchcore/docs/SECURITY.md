# Security model

This is a simulator with synthetic cash and inventory. It has no exchange connectivity, deposits or real-money execution. Journals contain account identifiers, command payloads and synthetic balances; keep local files private and treat them as untrusted during decoding. Payload lengths and HTTP bodies are bounded to 64 KiB.

The CLI and `trusted-local-demo` HTTP profile trust local callers. This profile refuses non-loopback binding; its `X-Demo-Account` header is not authentication. The launcher clearly labels it. Do not expose that profile through a tunnel or reverse proxy.

Protected mode uses Spring Security NimbusJwtDecoder with live issuer discovery/JWKS, RS256 verification, default timestamp/issuer validation and an audience validator. JWT subject is the account identity. Scopes `matchcore:read`, `matchcore:write`, and `matchcore:admin` control reads, submission and account initialization. Even admin cannot submit for another subject. Cancel, replace, result lookup, account reads and execution pagination bind to the authenticated subject. Book depth is aggregate only.

HttpApiTest runs a local OpenID discovery/JWKS issuer with an actual RSA key and signed tokens; it rejects foreign signatures, wrong issuer/audience, expired tokens, missing scope and cross-account submission/private-result reads. This fixture does not implement interactive login or prove production issuer operations. The server binds loopback; a production TLS proxy, issuer availability policy, access logging policy and rate limiting remain deployment responsibilities. HTTP executor and pending queues are bounded. Responses use stable error codes, never stack traces.

No secrets or authentication tokens are persisted in scripts or evidence. API demo initialization is synthetic and explicitly authorized in local mode. Production identity provisioning and real trading are outside scope.

HTTP and durable command decoding reject numeric enum ordinals, scalar coercion, null primitive fields, fractional integers, duplicate JSON keys, unknown properties and trailing documents. Journal append validates a command before writing, and recovery checks both frame integrity and the decoded command shape. A valid CRC is not sufficient to admit an ill-typed payload. Query parameters are URL-decoded before endpoint-specific validation; duplicate decoded names, unknown keys and negative minimum sequences are rejected.

CRC32C and snapshot SHA-256 checks detect accidental corruption; they do not authenticate hostile filesystem changes. Protect journal directories with filesystem permissions and retain a cold copy before recovery experiments. A user with write access to journals is inside the storage trust boundary.
