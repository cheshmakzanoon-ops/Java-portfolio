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

Command fields are the Java Model.Command record: clientCommandId/accountId/type/orderId/side/priceTicks/quantityLots/timeInForce/postOnly/initialCash/initialLots. Identifiers are restricted to 1..64 ASCII letters, digits, underscores or hyphens; enums have the values documented in the HTTP contract. INIT records initial synthetic balances. NEW quantity is initial remaining lots. Internal REPLACE quantity is normalized **new remaining lots**; the HTTP field is deliberately named newRemainingQtyLots. CANCEL has no price/quantity effect. The persisted v1 hash covers the fully decoded record-text representation, so JSON whitespace/property order do not change it. Because nullable text and invalid UTF-16 can collide under that legacy representation, hash equality is compatibility metadata rather than retry authority: retries compare exact immutable commands retained from the journal. The persisted hash and frame format are unchanged. Numeric enum ordinals, scalar coercion, explicit null primitives, fractional integers, duplicate keys, unknown fields and trailing JSON tokens reject. Public append operations validate the command before writing any bytes, so rejected input leaves the journal reusable at the same next sequence.

All admitted well-formed inputs, including business rejections, receive a sequence. Identical retries never append another record. Queue overload and malformed/unauthorized requests reject before admission. No timestamps or fresh random values are replay inputs. Seeded workloads record each generated command, including every random choice that affects business state.

Snapshot envelope JSON is `{ "payload": "<serialized snapshot JSON>", "checksum": "<SHA-256 of exact payload string>" }`, at most 16 MiB. The snapshot payload contains version, lastSequence, prefixDigest and checkpoint. Prefix digest hashes run ID plus every preceding sequence and canonical command hash. Checkpoint contains the immutable full View (accounts, orders with revision/priority, executions and results) and all used order identities, including terminal orders. Missing, oversized, corrupt, mismatched-version or mismatched-prefix snapshots fall back to the complete retained log. Every journal frame still validates before checkpoint use. A candidate checkpoint is validated in private before Book state is published: result sequence/hash/identity, order identity/priority, execution references, nonnegative checked balances, exact reservations and uncrossed prices must hold. Each prefix result is bound to its command hash and sequence; initialized balances plus recorded execution transfers must reproduce account balances. Invalid hints fall back to full replay. Prefix matching transitions are not replayed when a valid checkpoint is accepted.

CRCs and checkpoint hashes detect accidental corruption; they do not authenticate hostile filesystem changes. The journal is finite at 1,100,000 entries. There is no log pruning or cross-version migration in v1.

The journal lifecycle is OPEN → FAILED → CLOSED, with OPEN → CLOSED for normal shutdown. Invalid input/sequence/encoded-size preflight leaves OPEN reusable. Once storage append work starts, any write/force/fault failure marks FAILED and subsequent append/snapshot refuses until close and reopen. A lost force acknowledgement has unknown outcome. Complete unforced frames may survive a process crash; complete forced frames must survive within the retained-storage model. Cleanup preserves the original failure and attaches secondary failures as suppressed.

Snapshot publication forces the temporary file, requires atomic rename, then forces the containing directory. Publication failure reports an error while retaining journal authority. Snapshot validation adds bounded structural and ledger work; it is not cryptographic authenticity and does not change the v1 wire format.
