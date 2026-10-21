# Historical cross-project review findings

This record preserves findings and their reported state at the time of review. It is not a current issue tracker or a validation receipt. Consult project review/status pages and executed regression evidence for subsequent outcomes. A reported fix alone does not establish closure.

| Finding | Project | State | Required regression evidence |
|---|---|---|---|
| Bound decimal input before regex/BigDecimal allocation | Daybook | Fix reported | 64-character bound,100k-character rejection, exact decimal/JPY/scientific-notation tests |
| Replay accepts unknown/wrong-kind task histories; cancellation lacks terminal outcome | Handoff | Fix in progress | Reject malformed histories and full cancellation→compensation→terminal sequence |
| Raft deferred events stranded after persistence acknowledgement | Majority | Fix reported | Drain multiple queued events FIFO until next persistence barrier |
| Raft partitioned read correlations never expire | Majority | Fix reported | Saturation then heal must regain read capacity |
| The Pit bounded command history/resource ownership | The Pit | Fix reported | Finite command caps enforced in both Book and gateway; near-Long.MAX conservation tests |
| HTTP resource bounds before deserialization | Daybook/Handoff/Late Arrival/Gatehouse/The Pit | Under review | Actual byte/nesting caps, chunked input tests, frame caps before decode |

Additional preserved regressions include: Majority apply-before-disk-ack (seed27), The Pit zero-quantity replacement precedence (seed-6026787), Handoff cancellation racing late forward completion, Late Arrival progress-record ID fixture collision. See project CASE_STUDY/REVIEW and test fixtures for final outcomes.

Gatehouse→The Pit preliminary independent review (tree fingerprint13b9ec73ec6f93bd6df355c19a9d92f512b779ff71a7345d5b8f29bba8c57247) found IOC replacement reduction retaining a resting order, signed JWTs missing mandatory expiry accepted by the default timestamp validator, and executor rejection potentially resetting HTTP rather than explicit overload. The Pit acknowledged and is fixing. The missing-expiry pattern was also found in Handoff/Late Arrival and affected projects were identified. Final regressions pending.

Additional preliminary independent findings:

- Daybook→Handoff: repeated NON_IDEMPOTENT dispatch authorization on the same lease; compensation attached to non-activity/null-safety nodes; mandatory token expiry. The implementation review reported fixes pending rerun.
- Handoff→Majority: snapshot progress retained across compaction; outstanding unknown mutation incorrectly cleared after transient overload; checker memo conflates absent value with literal`"null"`. The implementation review reported regressions, then found interrupted chunk transfer restart case.
- Majority→Late Arrival: reference oracle derives admission from production watermark; JSON duplicate members accepted; ambiguous-commit DurableSession reused. The implementation review proposed independent watermark/reference and strict decode/poisoning.
- Late Arrival→Gatehouse: missing-audience NPE and absent protected subject; rollback only creates draft rather than required governed copy+publish. The implementation review proposed null guards/claims and atomic rollback revision publication.

No finding above is closed solely from a reported fix; frozen source and executed regressions remain required.
