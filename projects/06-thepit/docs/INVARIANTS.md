# State and durability invariants

The engine is a single-instrument, finite-run simulator. Every well-formed admitted command receives one sequence, including business rejections. Matching never reads a clock, network response or random generator.

| Invariant | Owning implementation | Executable evidence |
| --- | --- | --- |
| Best price, then admission sequence; trade at maker price | [Book](../matching-core/src/main/java/io/github/cheshmakzanoon/portfolio/thepit/Book.java), indexed price levels and FIFO nodes | Independent [reference matcher](../simulator/src/main/java/io/github/cheshmakzanoon/portfolio/thepit/ReferenceBook.java), 120 seeded histories, exact demo |
| Cash and inventory are conserved; reservations equal resting-order requirements | `Book.transition`, checked initialization supply and preflight | [InvariantsTest](../matching-core/src/test/java/io/github/cheshmakzanoon/portfolio/thepit/InvariantsTest.java), 12,000 generated transitions, signed-long maximum transfers |
| Risk rejection cannot modify the original replacement | Preflight before releasing the old node | [BookTest](../matching-core/src/test/java/io/github/cheshmakzanoon/portfolio/thepit/BookTest.java), overflow and replacement histories |
| Reduction preserves FIFO; price change or increase loses it | `Book.transition`, retained priority or fresh sequence | Exact demo, reduction/revision/depth assertions |
| FOK changes nothing unless fully executable; incoming self-trade remainder cancels | FOK scan and matching loop | Targeted self-trade/FOK assertions and reference comparison after every transition |
| A full open-order book admits a GTC aggressor whenever its fills reclaim enough maker slots or its remainder is cancelled | Indexed and independent flat-list capacity preflight before mutation | Full fills, rejected noncrossing orders, partial fills that free a slot, self-trade cancellation, and reference-model parity tests |
| A malformed checkpoint cannot partially populate an empty book | [BookCheckpoint](../matching-core/src/main/java/io/github/cheshmakzanoon/portfolio/thepit/BookCheckpoint.java), temporary restore then publication | [BookCheckpointTest](../matching-core/src/test/java/io/github/cheshmakzanoon/portfolio/thepit/BookCheckpointTest.java), invalid-state matrix followed by valid restore on the same instance |
| Checkpoint balances and result identities belong to the journal prefix | [Journal.matchesPrefix](../journal/src/main/java/io/github/cheshmakzanoon/portfolio/thepit/Journal.java), initialized supply and execution ledger | CRC-valid snapshot result, cash and inventory tampering; BUY and SELL takers |
| Complete command force precedes apply, publication and acknowledgement | [Gateway.run](../gateway/src/main/java/io/github/cheshmakzanoon/portfolio/thepit/Gateway.java) and `Journal.appendAndForce` | Force barrier, observed real file-channel force, five separate-process kill boundaries |
| An admitted storage failure stops further writes until reopen | `Journal` OPEN/FAILED/CLOSED state | [JournalTest](../journal/src/test/java/io/github/cheshmakzanoon/portfolio/thepit/JournalTest.java), each append boundary and lost force acknowledgement |
| Validation failures before storage admission leave the same next sequence usable | Journal preflight | Invalid command, sequence and oversized payload followed by successful valid append |
| Retry conflict checks use exact command equality; v1 hash collisions cannot substitute a different payload | Gateway committed receipts, pending commands, Book command authority | Pending, committed and checkpoint-restarted null/literal-null collision; exact retry acceptance |
| Each retry owns its response future; durable authority remains private | `Gateway.submit`, private pending future and dependent copies | [GatewayTest](../gateway/src/test/java/io/github/cheshmakzanoon/portfolio/thepit/GatewayTest.java), cancellation, forged completion and exceptional completion of separate callers |
| Each successful admission counts once, even between apply and acknowledgement | `Gateway.admitted`, bounded queue and pending set | Latches at force/apply, exact queue and run-cap boundaries |
| User callbacks run outside the admission monitor | Writer completion after synchronized publication; failure fan-out after lock release | Callback lock assertion and concurrent pending retries |
| Read state is immutable and sequence tagged | [Model.View](../matching-core/src/main/java/io/github/cheshmakzanoon/portfolio/thepit/Model.java), volatile public view | Historical-view invariants; waiting-read publication; map-order-independent digest |
| Private results/executions remain account scoped after snapshot recovery | Gateway result index and execution filters | Restored result lookup/retry, maker/taker filters, pagination and maximum-sequence cutoff |
| Checksums do not bypass typed decoding | [CommandJson](../journal/src/main/java/io/github/cheshmakzanoon/portfolio/thepit/CommandJson.java) | Independent CRC-valid frames with invalid scalar/enum/null shapes |
| Incomplete uploads release bounded handler capacity | [HttpApi.main](../gateway/src/main/java/io/github/cheshmakzanoon/portfolio/thepit/HttpApi.java), five-second JDK request/response defaults | [HttpDeadlineIT](../simulator/src/test/java/io/github/cheshmakzanoon/portfolio/thepit/HttpDeadlineIT.java), four real slow sockets and subsequent successful command |

Business arithmetic cannot overflow after mutation under these invariants: maker notional fits its checked reservation; execution quantity is bounded by the maker and incoming remaining quantity; aggregate initialized cash and lots fit signed long; and replacement's available-plus-released reservation cannot exceed its account balance.

## Recovery validation

A checkpoint must have contiguous unique result sequences, valid identities and hashes, valid execution ownership/sequence, unique open keys and priorities, used identities for every order, nonnegative balances, exact reservations, checked aggregate supply, and an uncrossed resting book. Validation builds private state before publishing it. Prefix validation checks each command/result identity and recomputes balances from initialized supply and recorded transfers; matching runs only for the journal suffix.

This preserves the snapshot optimization: recovery still reads and validates the full retained journal, but does not rematch its snapshotted prefix. Structural validation and ledger verification add work proportional to the checkpoint/history, including sorting result sequences. They provide consistency checks, not authentication against a writer who controls all storage bytes.

## Failure decisions

| Observation | Decision |
| --- | --- |
| Invalid preflight command, sequence or encoded size | Reject without poisoning journal; correct the input |
| Append/write/force or admitted fault-hook failure | Preserve primary cause, mark journal failed, reject append/snapshot; close and reopen retained storage |
| Recovery fails and close also fails | Report recovery failure with cleanup suppressed; attempt lock release and channel close |
| Incomplete final frame | Truncate only that frame and force repaired length |
| Complete corrupt frame, sequence gap or wrong run ID | Refuse startup; retain original bytes for investigation |
| Invalid/missing/oversized snapshot | Ignore hint and replay retained journal |
| Snapshot publication failure | Report failure; journal remains authoritative and usable |
| Response timeout or lost force acknowledgement | Outcome unknown; query/retry the same stable identity |

State-only `Book.restore(checkpoint)` lacks original historical payloads and therefore rejects historical retry lookup. Supply the complete ordered prefix to `restore(checkpoint, originalCommands)` for retry authority. Gateway obtains those immutable commands from the already-validated retained journal; it preserves the checkpoint optimization and v1 hash/format.
