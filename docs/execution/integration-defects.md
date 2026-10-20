# Historical cross-project review findings

This record preserves findings and their reported state at the time of review. It is not a current issue tracker or a validation receipt. Consult project review/status pages and executed regression evidence for subsequent outcomes. A reported fix alone does not establish closure.

| Finding | Project | State | Required regression evidence |
|---|---|---|---|
| Bound decimal input before regex/BigDecimal allocation | ClearLedger | Fix reported | 64-character bound,100k-character rejection, exact decimal/JPY/scientific-notation tests |
| Replay accepts unknown/wrong-kind task histories; cancellation lacks terminal outcome | DurableFlow | Fix in progress | Reject malformed histories and full cancellation→compensation→terminal sequence |
| Raft deferred events stranded after persistence acknowledgement | QuorumKV | Fix reported | Drain multiple queued events FIFO until next persistence barrier |
| Raft partitioned read correlations never expire | QuorumKV | Fix reported | Saturation then heal must regain read capacity |
| MatchCore bounded command history/resource ownership | MatchCore | Fix reported | Finite command caps enforced in both Book and gateway; near-Long.MAX conservation tests |
| HTTP resource bounds before deserialization | ClearLedger/DurableFlow/PulseStream/TenantShield/MatchCore | Under review | Actual byte/nesting caps, chunked input tests, frame caps before decode |

Additional preserved regressions include: QuorumKV apply-before-disk-ack (seed27), MatchCore zero-quantity replacement precedence (seed-6026787), DurableFlow cancellation racing late forward completion, PulseStream progress-record ID fixture collision. See project CASE_STUDY/REVIEW and test fixtures for final outcomes.

TenantShield→MatchCore preliminary independent review (tree fingerprint13b9ec73ec6f93bd6df355c19a9d92f512b779ff71a7345d5b8f29bba8c57247) found IOC replacement reduction retaining a resting order, signed JWTs missing mandatory expiry accepted by the default timestamp validator, and executor rejection potentially resetting HTTP rather than explicit overload. MatchCore acknowledged and is fixing. The missing-expiry pattern was also found in DurableFlow/PulseStream and affected projects were identified. Final regressions pending.

Additional preliminary independent findings:

- ClearLedger→DurableFlow: repeated NON_IDEMPOTENT dispatch authorization on the same lease; compensation attached to non-activity/null-safety nodes; mandatory token expiry. The implementation review reported fixes pending rerun.
- DurableFlow→QuorumKV: snapshot progress retained across compaction; outstanding unknown mutation incorrectly cleared after transient overload; checker memo conflates absent value with literal`"null"`. The implementation review reported regressions, then found interrupted chunk transfer restart case.
- QuorumKV→PulseStream: reference oracle derives admission from production watermark; JSON duplicate members accepted; ambiguous-commit DurableSession reused. The implementation review proposed independent watermark/reference and strict decode/poisoning.
- PulseStream→TenantShield: missing-audience NPE and absent protected subject; rollback only creates draft rather than required governed copy+publish. The implementation review proposed null guards/claims and atomic rollback revision publication.

No finding above is closed solely from a reported fix; frozen source and executed regressions remain required.
