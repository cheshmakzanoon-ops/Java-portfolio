# Engineering review

## Runtime and policy corrections

The current review follows the complete decision path, every production Java class, the authorization schema, both OIDC paths, publication/revocation transactions and local process ownership. The [architecture source map](ARCHITECTURE.md) identifies all production classes and their boundaries. The changes below address failure modes with executable counterexamples; [status](STATUS.md) records which checks ran.

| Priority | Finding | Correction and evidence |
| --- | --- | --- |
| High | Startup checked ownership of only one table; a damaged deployment could leave another authorization table outside RLS | Verify all nine required tables, ENABLE/FORCE RLS, ownership and assumable bypass roles. Integration independently changes each boundary and restores it. |
| High | Membership enforcement relied on the evaluator callback after snapshot assembly | `PolicyStore` now denies missing/inactive members before invoking any evaluator or reading private policy/resource metadata; a deliberately allowing callback cannot bypass it. |
| High | A protected read trusted an untyped map containing `ALLOW`; incomplete or ambiguous dependency data could grant access | Separate bounded typed decision client; real HTTP-stub regressions cover complete fresh outcomes, malformed/coerced/oversized bodies, status/media errors and disconnection. |
| Medium | Eviction of live quota state could reset a tenant's request budget under name churn | Explicit non-evicting current-window buckets, one bounded overflow budget and owned idempotent leases; fixed-clock and 100-contender tests exercise churn and concurrency. |
| Medium | Caller-provided derived compiled state could mutate/forge the plan's closure/index; grant lookup scanned every grant | Privately owned immutable plan verifies compatibility-constructor inputs and builds typed scope grant indexes; independent multi-scope oracle checks prevent authority crossing. |
| Medium | Stored policy/attribute errors could be reported as caller mistakes, and failed cache loads lacked an explicit contract | Separate snapshot binding and compiled-policy cache; validate metadata/digest, classify stored faults as unavailable, share concurrent loads and retry failed loads. Database corruption/repair and latch tests exercise both paths. PostgreSQL role arrays with null elements now fail as unavailable; duplicate stored roles normalize to their set semantics. |
| Medium | Transaction setup, nested calls and rollback failure could obscure ownership or the original fault | Guard nested calls before acquisition; roll back setup/callback/commit errors, release the connection and retain suppressed rollback failures. Fault injection wraps real JDBC connections and verifies atomic data/audit state. |
| Medium | Finite helper exit and failed startup could leave owned descendants/services/containers | Group cleanup after every terminal outcome; startup tracks acquired PIDs/container IDs and retains failure status. Six process/control tests cover normal exit, deadline, repeated terminal signals, migration and readiness failures. |
| Medium | Malformed UTF-16 literals could share their replacement UTF-8 representation in a policy digest | Reject unpaired surrogates in literals/inputs; retain well-formed supplementary text and established string budgets. Digest counterexamples and exact work-budget/type tests cover the contract. |

The earlier publication correction remains: one canonical quoted int64 `If-Match` is validated before database access for publish/rollback; malformed, weak, multiple, wildcard and overflowing values fail. The application mapper rejects duplicate keys and trailing documents through actual Spring configuration. Neither change substitutes for current tenant administrative membership.

Core mutation selection now includes `PolicyEngine*` and `PolicyLanguage*`, so moving plan logic into nested types cannot place it outside the old exact-class selection. The 70% mutation gate and 85% line/75% branch coverage gates remain unchanged. The six project POMs use readable XML; dependency declarations, versions and execution semantics were preserved apart from the deliberate broader mutation selection.

No benchmark was rerun for these changes. Scope indexes reduce the grant search structurally; no throughput or latency improvement is asserted. Current local fault tests do not establish production deployment security, cluster-wide quotas, remote resource-write atomicity or a completed backup/restore drill.

## Historical correctness review

Source fingerprint `4bd439735ed2a4399ba8c0f9d7abfa3d28dcbec9bec81130d6472dfa27ea124d` was reviewed against 27 passing methods, including nine PostgreSQL/Keycloak integration tests under JUnit 6.1.3 and the 21-assertion protected demo. The scope included JWT paths, atomic rollback, repeatable-read snapshots, epoch locks, membership binding, RLS and versioned cache keys. Audience, subject-type and draft-only rollback defects were closed within that scope.

Later image pins, artifact verification and JMH metadata extraction changed the recorded fingerprint to `832dfc3ccd0bf9399528721f8a31710c4b0197129b0b183aadcd5a1afeb92c11` without changing production Java. Ordinary 18-method verification, eight artifact-integrity probes and real JMH discovery passed. The first measurement attempt failed before JMH; [its evidence](../evidence/reference-attempt-5-failed.json) remains available. Historical receipts do not certify later source or dependency changes.

## Historical measurement review

The review reproduced the 19-file, 730,510-byte packet with SHA-256 `584579ad789f89a231f64cc7d2dee2e4b46e87d4c11e4dcf3dce9bb74e10e263`. It checked 22 archived raw files, six JFR identities, three executable JARs, eight source-evidence hashes and the published CSV/statistics. All three 100,000-request runs contained 20,000 denials and zero errors. Both three-fork JMH summaries agreed with the original results.

No blocking finding remained in that packet's artifact identity, arithmetic or stated claims. Sample throughput CV 23.32% and a 38.10% first-to-third decline remain material limits. No fresh JFR event recount was performed; allocation sampling and GC durations retain their documented boundaries. The packet records one historical execution, not stable capacity, a causal explanation, a performance improvement or production readiness. See [performance](PERFORMANCE.md) for all measurements. Clean-checkout reproduction, repository-wide release checks and hosted CI are separately assessed.
