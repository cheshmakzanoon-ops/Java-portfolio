# ADR 004: distinguish provider refusal from an uncertain response

Status: accepted.

## Context

A provider can commit an external effect and then return an error because response construction, a downstream dependency, or an intermediary failed. Treating every HTTP error as a definitive rejection would let the engine retry a non-idempotent effect. Redirects also cannot acknowledge the requested effect merely because redirect following is disabled.

The local provider simulator previously rejected all error modes before applying an effect, hiding this distinction. Its fault model now includes a committed effect followed by HTTP 500.

## Decision

A dedicated fixed-destination transport classifies provider responses:

| Response | Engine-facing result |
| --- | --- |
| HTTP 2xx with a bounded valid UTF-8 receipt | Completed call; typed result validation still applies |
| HTTP 503 with `X-Effect-Outcome: NOT_APPLIED` | Explicit pre-effect refusal; bounded retry is permitted |
| HTTP 409 | Parameter conflict; never accepted as a receipt |
| Other HTTP error or redirect | Uncertain failure |
| Timeout, disconnect, oversized body, or malformed receipt encoding | Uncertain failure |

The simulator emits `NOT_APPLIED` only in branches that return before recording an effect. This is a contract with a trusted configured provider, not an inference from an HTTP status. A real adapter must establish an equally strong refusal contract before using this classification.

The transport accepts only a configured HTTP(S) `/effects` URI without user information, query, or fragment. It never follows redirects. The response subscriber limits the body to 4 KiB before aggregation, and UTF-8 decoding rejects malformed bytes.

## Consequences

For a dispatched non-idempotent task, an uncertain failure enters `UNKNOWN_OUTCOME` and requires reconciliation; it cannot create a successor dispatch. Pure and provider-idempotent work retain their existing retry policy. Oversized error bodies may conservatively become uncertain even if their status would otherwise describe a refusal.

Regression tests exercise actual fixed-length and chunked HTTP responses, redirect refusal, encoding failures, explicit refusal headers, and a PostgreSQL workflow whose provider records one non-idempotent effect before returning HTTP 500. The latter must reach manual review with one effect and no new claim.
