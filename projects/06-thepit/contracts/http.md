# HTTP v1

The standalone server binds `127.0.0.1:18600` by default (`THEPIT_PORT`). Protected mode requires a bearer token with issuer verification, audience `thepit`, original string subject, valid required `exp`/`iat`, and endpoint scopes. Subject is the account identity. The explicit `trusted-local-demo` profile instead trusts `X-Demo-Account`; it refuses non-loopback binding and supplies all demo scopes. Health is public.

| Method and path | Scope | Response |
| --- | --- | --- |
| `POST /commands` | `thepit:write`; INIT also `thepit:admin` | Durable result with sequence, code, canonical hash and executions |
| `GET /commands/{id}` | `thepit:read` | Subject's durable result; absent/other-account ID returns 404 |
| `GET /book?depth=20&minimumSequence=0` | `thepit:read` | Aggregate bid/ask depth and applied sequence; waits at most two seconds |
| `GET /account` | `thepit:read` | Subject's cash, lots, reservations and applied sequence |
| `GET /executions?offset=0&limit=20` | `thepit:read` | Subject's maker/taker executions and applied-sequence cutoff |
| `GET /health` | Public | 200 healthy or 503 recovery required |

Commands always require subject/account equality, including admin INIT. Cancel/replace order lookup uses the command's account. Read responses contain immutable sequence-tagged state; execution pagination excludes fills above its returned sequence.

## Commands

Identifiers match `[a-zA-Z0-9_-]{1,64}`. Body size is at most 65,536 bytes. Type is INIT, NEW, CANCEL or REPLACE; side is BUY or SELL; time-in-force is GTC, IOC or FOK. Prices are integral ticks of 0.01 quote units, quantities are integral lots, and checked products must fit signed 64-bit integers. Unpriced market orders are excluded.

| Type | Required business fields |
| --- | --- |
| INIT | `clientCommandId`, `accountId`, `type`, nonnegative `initialCash` and `initialLots` |
| NEW | Identity fields, `orderId`, `side`, positive `priceTicks` and `quantityLots`, `timeInForce`; `postOnly` |
| CANCEL | Identity fields and `orderId` |
| REPLACE | Identity fields, `orderId`, `side`, positive `priceTicks`, nonnegative `newRemainingQtyLots`, `timeInForce`; `postOnly` |

All primitive fields must be present, including zero/false values for fields unused by the selected type: `priceTicks`, `quantityLots` (or REPLACE's `newRemainingQtyLots`), `postOnly`, `initialCash` and `initialLots`. Missing or explicit null primitives are rejected; reference/enum fields unused by the type may be omitted. Floating-point numbers in integer fields, text/boolean/numeric coercions, enum ordinals, duplicate keys, unknown properties and trailing documents return 400. NEW with zero quantity is invalid; REPLACE with zero remaining quantity cancels. Positive replacement cannot change side or resting GTC policy; IOC/FOK replacement returns durable `TIF_CHANGE` with the old order unchanged. Post-only is valid only with GTC.

HTTP REPLACE requires `newRemainingQtyLots` and rejects `quantityLots` to prevent confusing remaining quantity with lifetime total. The adapter normalizes it to the internal journal command's `quantityLots`; this does not alter the v1 journal format.

```json
{"clientCommandId":"r-1","accountId":"alice","type":"REPLACE","orderId":"a-1","side":"BUY","priceTicks":10200,"newRemainingQtyLots":2,"timeInForce":"GTC","postOnly":false,"initialCash":0,"initialLots":0}
```

## Queries and outcomes

Query names/values are URL-decoded once. Book accepts only `depth` (1..100) and `minimumSequence` (nonnegative); executions accepts only `offset` (nonnegative signed int) and `limit` (1..100); commands, result lookup and account accept no query parameters. Unknown names and repeated decoded names return 400. Scope checks precede query diagnostics.

| Status | Meaning |
| --- | --- |
| 200 | Durable business success or rejection, such as RISK, OVERFLOW, POST_ONLY, FOK_UNFILLED, TERMINAL, NOT_FOUND, ORDER_ID_USED, SIDE_CHANGE, TIF_CHANGE or OPEN_ORDER_CAP |
| 202 | `OUTCOME_UNKNOWN_QUERY_STABLE_ID`: command response or minimum-sequence wait timed out |
| 400 / 413 | Invalid request / body limit |
| 401 / 403 | Missing/invalid identity / scope or account ownership failure |
| 404 | Unknown route or subject's result ID absent |
| 409 | CONFLICT: an existing account/command identity has a different payload |
| 429 | Pre-admission overload or finite run cap; retain the stable identity |
| 503 | Writer recovery required, interrupted processing or service unavailable |

Identical retries compare the complete decoded command and return the original sequence and result. The v1 result hash remains unchanged for compatibility; hash equality alone cannot authorize a retry. A timeout, disconnect or admitted failure has unknown outcome: query/retry the same ID and payload. It never proves cancellation. Malformed input, authorization failure and pre-admission overload allocate no sequence. Queue saturation permits backoff and retry. Once the finite run cap is exhausted, existing IDs remain queryable/retryable; new commands require a separate run rather than indefinite retries. The current error body uses `OVERLOADED_RETRYABLE` for both admission limits. All responses set `Cache-Control: no-store` and return stable error codes without stack traces.

Dispatch uses four workers, a 64-task queue and four active-handler permits; excess dispatched requests receive retryable overload. Standalone startup sets five-second JDK request/response defaults before server initialization, preserving explicit `-Dsun.net.httpserver.maxReqTime` and `maxRspTime` overrides. These are JVM-wide periodic JDK limits; embedded hosts must configure policy before their first server. A separate-process test fills all four handlers with incomplete bodies and verifies permit recovery. TLS, connection-rate policy and production identity provisioning remain deployment responsibilities.
