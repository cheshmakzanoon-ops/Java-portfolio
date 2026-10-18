# Command log and checkpoint schema v1

All integer frame fields are big-endian. A record is exactly:

| Offset | Width | Field |
| --- | --- | --- |
| 0 | 4 | Magic 0x4d434f52 |
| 4 | 4 | Schema version 1 |
| 8 | 8 | Sequence, starting at one with no gaps |
| 16 | 4 | JSON payload length, 1..65536 bytes |
| 20 | 4 | CRC32C of the preceding twenty header bytes |
| 24 | length | UTF-8 JSON `{ "runId": "...", "command": {...} }` |
| 24 + length | 4 | CRC32C of payload bytes |

Command fields are the Java Model.Command record: clientCommandId/accountId/type/orderId/side/priceTicks/quantityLots/timeInForce/postOnly/initialCash/initialLots. Identifiers are restricted to 1..64 ASCII letters, digits, underscores or hyphens; enums have the values documented in the HTTP contract. INIT records initial synthetic balances. NEW quantity is initial remaining lots. Internal REPLACE quantity is normalized **new remaining lots**; the HTTP field is deliberately named newRemainingQtyLots. CANCEL has no price/quantity effect. Canonical payload identity hashes the fully decoded immutable record representation, so JSON whitespace/property order do not create new identities. Floating-point coercion and trailing JSON tokens reject.

All admitted well-formed inputs, including business rejections, receive a sequence. Identical retries never append another record. Queue overload and malformed/unauthorized requests reject before admission. No timestamps or fresh random values are replay inputs. Seeded workloads record each generated command, including every random choice that affects business state.

Snapshot envelope JSON is `{ "payload": "<serialized snapshot JSON>", "checksum": "<SHA-256 of exact payload string>" }`, at most 16 MiB. The snapshot payload contains version, lastSequence, prefixDigest and checkpoint. Prefix digest hashes run ID plus every preceding sequence and canonical command hash. Checkpoint contains the immutable full View (accounts, orders with revision/priority, executions and results) and all used order identities, including terminal orders. Missing, oversized, corrupt, mismatched-version or mismatched-prefix snapshots fall back to the complete retained log. Every journal frame still validates before checkpoint use.

CRCs and checkpoint hashes detect accidental corruption; they do not authenticate hostile filesystem changes. The journal is finite at 1,100,000 entries. There is no log pruning or cross-version migration in v1.
