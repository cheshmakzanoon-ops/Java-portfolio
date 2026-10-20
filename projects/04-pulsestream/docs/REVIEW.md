# Review record

Reviews cover named source identities and executed evidence. They do not establish final release approval, production capacity, or correctness outside the bounded model. See [verification status](STATUS.md) for current outcomes.

## Resolved correctness findings

An earlier review identified three consequential defects:

| Finding | Resolution | Regression boundary |
| --- | --- | --- |
| The property oracle consumed the production watermark instead of deriving progress independently | The oracle tracks its own partition maxima, source activity, idle state, and progress deduplication | Generated record, observation, and restart schedules compare independent states |
| Duplicate JSON members were silently accepted | `EventCodec` enables strict duplicate detection | Malformed envelopes are quarantined |
| A session could continue after an ambiguous database exception | `DurableSession` requires SQL-authoritative restart after a failed commit | A real database outage preserves committed state and refuses subsequent session reuse |

The initial follow-up covered Java fingerprint `72a7d5d015c406f50b075ff3f0f7f7d7006166b6d17623f39735fd4f266735a8`. Later storage/startup review covered tree `d56210af3b61490dfe5f5c31f2a746d9040c705514efde1b58f79b596ce94f42`, including client-before-lease startup, unstarted-consumer cleanup, broker log-volume alignment, and orderly container recreation with retained topic UUID and SQL offsets.

The subsequent engine optimization avoids rescanning all pending windows/panes when the watermark is unchanged. Newly due late windows publish directly. Review checked finality, exact-end revisions, and the frozen `1dcc6557f80d9025111654ae5041d99c2bd6321752a6caa39db207103838fb99` inputs. Its verification evidence contains 15 ordinary methods, 8 real integration methods, and 131 strict mutation kills of 133 outcomes. These are historical source-bound results.

## Wire-type finding, 2026-10-20

`JsonNode.asText(default)` treats null and container nodes as the supplied default. The envelope decoder therefore accepted an explicitly supplied null, object, or array `recordType` as a measurement, despite the schema requiring a recognized string.

The decoder now checks the original node type before applying the omission default. Records without `recordType` remain valid legacy measurements. Supplied values must be `MEASUREMENT` or `PROGRESS`. Regressions verify malformed-type quarantine, checkpoint advancement without aggregate/identity mutation, and legacy omission compatibility. A real Kafka/PostgreSQL regression passed quarantine and a valid same-identity retry through restart. Current full verification passed 26 methods with zero failures, errors, or skips; see [status](STATUS.md).

## Measurement interpretation

The three-round quiet12 packet retains all 25 indexed raw files and original source/input identities. Review qualified two overbroad raw labels: recovery equality compares a defined semantic projection, and the SQL oracle checks aggregate/key/end/finality fields with positive revisions rather than independently deriving revision sequences. The [scope clarification](../evidence/reference-20261018-quiet12/scope-clarifications.json) preserves raw bytes and states those limits.

JMH retained all three forks' numeric samples but originally overwrote recordings within each variant. A separate capture-only repair verified six distinct PID-named recordings without changing application bytes. That later profiling window is not interchangeable with the earlier scores.

Final review must assess the current source diff, fresh integration results, tenant query boundaries, snapshot compatibility, retained positions/topic identity, ownership fences, and the precise scope of published measurements. Clean-checkout and repository release checks remain separate.
