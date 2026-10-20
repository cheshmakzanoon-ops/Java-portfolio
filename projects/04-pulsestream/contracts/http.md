# Query contract v1
All application endpoints are GET and accept no body (including chunked bodies). Limits: headers 16 KiB, identifiers 1–64 ASCII alphanumeric/underscore/hyphen, page 1–1000 rows; SQL statement budget 15 seconds. Database unavailability returns 503 `{ "code": "STORE_UNAVAILABLE" }`; invalid filters/pages return 400. Error responses never contain stack traces.

`GET /health`: operational readiness and current queue/source/checkpoint metrics, 200 or 503. SQL availability is checked even when source consumption is disabled, and is reported as `storeAvailable`. No input payloads or secret values.

`GET /v1/runs/{run}/windows?tenant=t&sensor=a&type=TUMBLING&after=-9223372036854775808&limit=100`: ascending start-time keyset pagination. Type is TUMBLING or SLIDING. Each row has tenant, sensor, type, start, end (epoch milliseconds), count, sum (milliunits), minimum, maximum, revision, finalized.

`GET /v1/runs/{run}/status?tenant=t`: SQL-committed watermark, partition activity and next-offset checkpoints, duplicate count, pane count, checkpoint age and tenant-filtered per-window late counts. Late metrics are limited to 100 windows, with `lateContributionsTruncated` explicit.

`GET /v1/runs/{run}/quarantine?tenant=t&after=0&limit=100`: ascending diagnostic ID pagination. Each row has id, eventId, reason, windowId. Completely undecodable records have reserved invalid tenant marker `!unattributed` and are available only through local SQL operations, rather than exposed to an arbitrary tenant.

Local profile requires loopback and `X-Dev-Tenant` matching the query tenant. Protected profile requires a verified RS256 JWT from configured issuer/JWKS, audience, expiry, `telemetry:read` scope and tenant claim. Run presence is authorized through durable seen-event membership; unknown/nonmember runs return 404. Never expose the local profile remotely.
