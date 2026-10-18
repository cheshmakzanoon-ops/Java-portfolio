# Executable invariants

| Rule | Implementation | Verification |
| --- | --- | --- |
| Best price then admission sequence; maker price | Book TreeMaps / linked FIFO | AgreementTest generated histories; exactDemo |
| Cash and lots conserved; no borrowing/shorting | Prechecked reservations and atomic writer transitions | InvariantsTest; AgreementTest after every generated command |
| All risky multiplication validated before mutation | Book.transition preflight | BookTest overflow; InvariantsTest maximumSupplyCannotOverflowDuringTransfers |
| Transfer additions cannot overflow | Initial aggregate supply <= signed long maximum; no mint after initialization except checked INIT | Maximum-supply test transfers Long.MAX_VALUE cash and lots exactly |
| Reduction preserves priority; increase/price change loses it | Book.transition retains node or removes/re-admits | Exact demo and replacementRulesAndFifo |
| Rejected replacement leaves original intact | All risk/FOK/post-only checks precede removal | Maximum-supply replacement rejection; generated agreement |
| FOK changes nothing if full quantity unavailable or self-blocked | Independent preflight | BookTest exactRiskAndFokSelfTrade and generated FOK histories |
| Incoming self-trade remainder cancels; earlier fills stand | Matching loop self blocker | Generated histories and targeted BookTest |
| No published pre-force business result | Gateway force -> apply -> publish -> complete | GatewayTest force barrier; CrashIT durable-prefix comparison |
| One result per account/command/run; terminal IDs never reused | Pending map + committed results + Book.used | Concurrent retries; conflicts; terminal replace/reuse tests |
| Durable log integrity is mandatory | Header and payload CRC, length/version/sequence validation | JournalTest every byte truncation and complete corruption |
| Immutable, sequence-tagged reads | Book.publicView, Gateway.read | GatewayTest minimum-sequence and InvariantsTest historical view checks |
| Bounded queue and finite retention | Gateway queue/pending/maxCommands and Book cap | GatewayTest overload/run-cap |

Business arithmetic cannot throw after mutation under these invariants: each resting maker notional fits a checked initial reservation; executed quantity is no greater than that maker quantity; total cash/lots never exceed the checked initialized aggregate; and a remainder's reservation is bounded by the incoming prechecked notional. `available + released` cannot exceed that account's balance. Timestamps do not affect order allocation or replay.
