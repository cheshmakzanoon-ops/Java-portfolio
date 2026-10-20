# Review scope and evidence

## Current hardening

Publication and rollback previously removed all quote characters from `If-Match` before parsing. That accepted malformed representations of a version and contradicted the documented strong-tag contract. Both operations now validate one canonical, quoted, nonnegative int64 version before database access. Regression tests cover malformed tags, weak tags, lists, wildcards, overflow, initial publication, the int64 boundary, and rollback content verification.

The application JSON configuration rejects duplicate object keys and trailing documents in addition to unknown fields, numeric enums and lossy integer coercion. The configuration test loads the actual application properties and exercises the resulting Spring-managed mapper; it does not substitute a test-only parser configuration.

See [verification status](STATUS.md) for the checks executed against this revision. Historical benchmark identities below do not certify these later changes.

## Historical correctness review

The earlier source fingerprint `4bd439735ed2a4399ba8c0f9d7abfa3d28dcbec9bec81130d6472dfa27ea124d` was reviewed against 27 passing methods, including nine PostgreSQL/Keycloak integration tests under JUnit 6.1.3 and the 21-assertion protected demo. The scope included both JWT paths, atomic rollback with an expected prior version, repeatable-read snapshots, tenant epoch locks, membership binding, RLS, and immutable policy-cache keys. Earlier audience, subject-type, and draft-only rollback defects were closed within that scope.

Later image pins, artifact verification, and JMH metadata extraction changed the recorded source fingerprint to `832dfc3ccd0bf9399528721f8a31710c4b0197129b0b183aadcd5a1afeb92c11` without changing production Java. Ordinary 18-method verification, eight artifact-integrity probes, and real JMH discovery passed. The first measurement attempt failed before JMH; the failure remains in `evidence/reference-attempt-5-failed.json`.

## Historical measurement review

The review reproduced the 19-file, 730,510-byte packet with SHA-256 `584579ad789f89a231f64cc7d2dee2e4b46e87d4c11e4dcf3dce9bb74e10e263`. It checked 22 archived raw files, six JFR identities, three executable JARs, eight source-evidence hashes, and the published CSV and statistics. All three 100,000-request runs contained 20,000 denials and zero errors. Both three-fork JMH summaries agreed with the original results.

No blocking finding remained in the packet's artifact identity, arithmetic, or stated claims. Throughput variation of 23.32% and a 38.10% first-to-third decline remain material limitations. No fresh JFR event recount was performed; sample allocation weights and GC-event durations retain their documented boundaries. The packet records one historical execution, not stable capacity, a causal explanation of the slowdown, a performance improvement, or production readiness.

Clean-checkout reproduction, repository-wide checks, hosted CI, and release approval are separate from these scoped reviews. See [performance](PERFORMANCE.md) for the complete measurements and [status](STATUS.md) for current validation.
