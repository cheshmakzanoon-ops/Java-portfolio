# Engineering case study

A leader flag is not evidence that a read is safe: an isolated old leader can retain that flag after another majority has elected a replacement. QuorumKV therefore treats each read as a fresh correlated quorum exchange, after a current-term marker has committed, and waits for the captured safe index to apply. The actual-process demo deliberately keeps the former leader alive while excluding both peers and asserts that its local map cannot produce a successful read.

The first seeded simulator run found a real ordering defect at seed 27: application advanced before its commit persistence acknowledgment, even though client replies were withheld. A crash could then make the observed application index decrease on restart. The correction moved application and received-snapshot activation into the matching persistence-acknowledgment handler. A named crash-before-ack test and the seed27 schedule preserve that counterexample. Coordinator review also found a FIFO-drain defect and unexpired pending reads; both now have named regressions.

Append-only replacement transactions avoid a second class of failure: overwriting a divergent suffix in place can destroy an old complete recovery path before its replacement is durable. Snapshot publication similarly preserves the previous generation until a verified snapshot and fresh WAL are selected by a durable atomic manifest. Complete corrupt records cause startup refusal; only structurally incomplete final records are eligible for tail recovery.

This is educational evidence under explicit stable-storage and non-Byzantine assumptions, not production field experience. See STATUS for actual executed gates, bounded-search outcomes and unfinished acceptance work.
