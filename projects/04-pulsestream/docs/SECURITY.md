# Security model

## Authentication and deployment

The default `local` profile binds HTTP to `127.0.0.1` and rejects a configured non-loopback address. It trusts `X-Dev-Tenant` and disposable Kafka/PostgreSQL credentials. This is a developer fixture, not remote authentication. Compose publishes dependencies on loopback only; do not expose the local API through a public reverse proxy.

For remote access select `--pulse.security=protected`, configure `pulse.oidc.issuer`, `pulse.oidc.jwks`, and `pulse.oidc.audience`, and provide HTTPS ingress. Spring Security/Nimbus validates the RSA signature, issuer, audience, and expiry. A strict bounded parser checks original signed identity types. Each query requires `telemetry:read` and a tenant claim matching the requested tenant.

The integration issuer uses an ephemeral loopback port with a real RSA key, JWKS, and signed tokens. Its negative fixtures exercise invalid signatures and claims. It is not a deployable identity provider.

## Input boundaries

Identifiers contain only ASCII letters, digits, underscore, and hyphen, with a maximum of 64 characters. Kafka values are bounded to 16 KiB before JSON parsing. Unknown fields, duplicate keys, wrong scalar types, trailing content, routing mismatches, and invalid source-progress values are quarantined.

`recordType` may be omitted for legacy measurements. When present it must be the string `MEASUREMENT` or `PROGRESS`; null, containers, and scalar coercions are rejected. A malformed envelope advances its durable source checkpoint and records a rejection without contributing aggregates or consuming a logical event identity. Future source times have a configurable clock-skew limit.

The query API accepts no request bodies, including chunked bodies. Pages contain 1–1,000 rows. Parameterized SQL, statement timeouts, connection timeouts, and socket timeouts bound reads. A tenant needs durable run membership before querying status. Diagnostic rows and late-window counts filter tenant identifiers; completely undecodable records use a reserved unattributed marker and require local SQL inspection.

## Storage and operational trust

Crash hooks require explicit local process configuration and are disabled in protected mode. HTTP exposes no administrative mutation, archive, or destructive operation. Run archival requires local database access.

PostgreSQL, Kafka topic/routing administration, deployment configuration, and the local snapshot filesystem are trusted. GZIP-compressed snapshots admit only the concrete engine state/result graph and required collections/scalars. Depth, references, array lengths, and decompressed bytes are bounded, including bytes skipped by deserialization. A root of the wrong type or an unrelated Java class is rejected. The V1 encoding is produced internally; HTTP and Kafka never supply serialized snapshots. Restore currently requires the same application version. Application error responses exclude JDBC messages and stack traces. Commit cleanup cannot replay a known decision; rollback diagnostics remain secondary to the primary failure. Shutdown waits for owner termination before releasing its resources and lease.

## Local process authority

Lifecycle operations require Linux kernel process identity. Records bind boot ID, process start time, and the exact `-jar` argument to the selected artifact; current-runtime reuse also checks its launch hash and verified source fingerprint. A mismatched live PID is never signalled. Stop uses a PID file descriptor when available and retains the record if termination cannot be established. Startup rollback preserves preexisting running services and named volumes and removes only acquired resources. A bounded command retains its unreaped group leader until all group signals are complete, so exit does not authorize targeting a reused process-group ID.

## Limits and reporting

The local stack uses plaintext transport and public fixture credentials. Production Kafka SASL/TLS and external secret provisioning are not configured by the demo. An authorized Kafka writer can consume a finite run's durable quotas. This is a bounded single-processor implementation, not a certified or distributed execution service.

Report vulnerabilities according to the repository's [security policy](../../../SECURITY.md), using synthetic input and no credentials.

The current official Kafka 4.2.2 image retains seven reported HIGH upstream findings (libexpat, internal LZ4, and internal Jackson). PostgreSQL 18.6 removes the earlier image OS HIGH/CRITICAL findings, while its upstream `gosu` binary retains separately reported Go-standard-library advisories. Digest pinning preserves identity; it does not resolve those upstream findings. Application dependency scan results have their own boundary.

Executable identity is mandatory when publishing a new process record. If a managed procfs later denies only executable dereference with `EACCES`/`EPERM`, boot/start identity and the hash of every exact command argument remain mandatory; a readable executable must still match. Other read failures refuse ownership. Two kernel-stat reads bracket the argument/executable observation, and a changed kernel start identity refuses control. Argument hashes avoid retaining command configuration or secrets in the record.
