# Explicit persistence and generation publication

The transition core emits immutable persistence effects instead of calling a disk directly. Sending a granted vote or successful append before flush is a safety defect that a synchronous in-memory test cannot detect. Matching persistence acknowledgments release dependent effects and application; seeded virtual storage can crash between issuance, write completion and acknowledgment.

Rejected alternative: mutating log files in place when repairing a divergent suffix. A checksum does not make a partially overwritten acknowledged prefix safe. The WAL instead appends one checksummed replacement transaction. Snapshot compaction uses a fresh generation and atomically published manifest, with the prior recovery path retained until directory durability.

Commit metadata is persisted even though Raft can recover commitment from a new leader. This trades an extra flush for a simple explicit restart rule: apply only the commit index established and flushed before failure. A retained suffix never proves commitment by itself.
