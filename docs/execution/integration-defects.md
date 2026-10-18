# Coordinator review findings

These are observed review findings, not completed independent project reviews. Owner-reported fixes require frozen-tree revalidation before closure.

| Finding | Owner | State | Required regression evidence |
|---|---|---|---|
| Bound decimal input before regex/BigDecimal allocation | A01 | Fix reported | 64-character bound,100k-character rejection, exact decimal/JPY/scientific-notation tests |
| Replay accepts unknown/wrong-kind task histories; cancellation lacks terminal outcome | A02 | Fix in progress | Reject malformed histories and full cancellation→compensation→terminal sequence |
| Raft deferred events stranded after persistence acknowledgement | A03 | Fix reported | Drain multiple queued events FIFO until next persistence barrier |
| Raft partitioned read correlations never expire | A03 | Fix reported | Saturation then heal must regain read capacity |
| MatchCore bounded command history/resource ownership | A06 | Fix reported | Finite command caps enforced in both Book and gateway; near-Long.MAX conservation tests |
| HTTP resource bounds before deserialization | A01/A02/A04/A05/A06 | Under review | Actual byte/nesting caps, chunked input tests, frame caps before decode |

Worker-discovered regressions are preserved by their owners: QuorumKV apply-before-disk-ack (seed27), MatchCore zero-quantity replacement precedence (seed-6026787), DurableFlow cancellation racing late forward completion, PulseStream progress-record ID fixture collision. See project CASE_STUDY/REVIEW and test fixtures for final outcomes.

A05→A06 preliminary independent review (tree fingerprint13b9ec73ec6f93bd6df355c19a9d92f512b779ff71a7345d5b8f29bba8c57247) found IOC replacement reduction retaining a resting order, signed JWTs missing mandatory expiry accepted by the default timestamp validator, and executor rejection potentially resetting HTTP rather than explicit overload. A06 acknowledged and is fixing. The missing-expiry pattern was also found in A02/A04 and owners were notified. Final regressions pending.

Additional preliminary independent findings:

- A01→A02: repeated NON_IDEMPOTENT dispatch authorization on the same lease; compensation attached to non-activity/null-safety nodes; mandatory token expiry. Owner acknowledged fixes pending rerun.
- A02→A03: snapshot progress retained across compaction; outstanding unknown mutation incorrectly cleared after transient overload; checker memo conflates absent value with literal`"null"`. Owner acknowledged regressions, then found interrupted chunk transfer restart case.
- A03→A04: reference oracle derives admission from production watermark; JSON duplicate members accepted; ambiguous-commit DurableSession reused. Owner implementing independent watermark/reference and strict decode/poisoning.
- A04→A05: missing-audience NPE and absent protected subject; rollback only creates draft rather than required governed copy+publish. Owner implementing null guards/claims and atomic rollback revision publication.

No finding above is closed solely from owner acknowledgement; frozen source and executed regressions remain required.
