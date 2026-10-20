# Design review record

Reviews concentrate on boundaries that can create inconsistent history or duplicate external effects. The findings below describe development defects and their regression coverage; they are not production incident reports.

| Finding | Correction and regression coverage |
| --- | --- |
| The same current lease could authorize a non-idempotent call twice. | Dispatch intent uses a compare-and-set; independent provider `CALL` and `EFFECT` records check that repeated authorization is refused. |
| Compensation could be attached to a non-activity node without a safety contract. | Definition validation rejects unsupported compensation targets. |
| Default JWT timestamp validation did not require expiry, and registered-claim conversion could coerce subjects. | Required original claim types and expiry are checked after signature verification; HTTP tests cover missing claims, wrong types, forged signatures, scope, and tenant boundaries. |
| Cancellation could select compensation before another forward lease acknowledged its result. | Cancellation waits for forward leases to settle, preserving reverse recorded-completion order. |
| Replay could accept unknown task identities or a terminal failure cancellation that the store rejected. | Replay validates task identity and transition legality. Semantics version 3 records a bounded forward-lease observation; frozen versions 1 and 2 retain their historical behavior. See [ADR 003](DECISIONS/003-cancellation-evidence.md). |
| Prepared artifacts could be rebuilt while an older runtime remained live. | Preparation refuses owned live processes, including those with missing PID files, and binds source, JARs, and verification evidence before startup. |

The expanded source review at `a8a4692a96226a6d45f3f42f1a8780b28e6d5ade834389d8c83ea8a2b0ec48f5` was accompanied by 67 ordinary and 13 PostgreSQL test methods. A later prepared-cache review at `104e648ae642e84a02ce3031ac7439e4a71f20767285404b87a96b4019254187` recorded 68 ordinary methods and nine preparation refusal cases. These historical scopes do not certify later changes. Original [verification evidence](../evidence/verification.json) and [prepared-build evidence](../evidence/preparation-verification.json) remain available.

The current transport review additionally addresses unbounded provider responses, redirect acknowledgements, malformed receipt encoding, and credential markers hidden by line separators. The new tests use actual loopback HTTP exchanges for both fixed-length and chunked responses. Current executed results belong in [verification status](STATUS.md).

Review coverage is finite. It does not establish a production threat assessment, full reference performance baseline, or successful hosted release pipeline.
