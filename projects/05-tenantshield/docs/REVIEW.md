# Final scoped source review

A04 independently re-reviewed frozen executable fingerprint `4bd439735ed2a4399ba8c0f9d7abfa3d28dcbec9bec81130d6472dfa27ea124d` against the final 27-method suite, including nine real PostgreSQL/Keycloak tests on JUnit 6.1.3. The reviewer inspected both strict JWT paths, atomic expected-prior rollback, tenant membership/resource snapshots, transaction-local RLS and immutable cache keys. Earlier missing-audience/subject and non-atomic rollback findings are closed within that tested scope. No new confirmed blocking source finding arose.

The coordinator also inspected the original typed language/compiler/evaluator, conservative three-valued combining and role closure, plus the repeatable-read transaction/RLS boundary and NO KEY UPDATE writer lock. Raw results and full preparation/demo records remain linked from STATUS.

Quiet JMH/three-run HTTP measurements, review of those measurements and clean-checkout/remote validation remain open. This scoped acknowledgment does not approve a complete release.

## Historical findings and corrections


Awaiting coordinator-assigned read-only review after tested implementation handoff. No independent approval is claimed. Reviewer should inspect the strict UNKNOWN behavior, publication immutability/digest, trusted JWT sources, per-request membership snapshot, mandatory audit failure, RLS pooled connections, body allocation bounds and the protected resource's unavailable/denied behavior. Findings and evidence-backed resolutions will be recorded here by the owner after receiving review.

A04's preliminary moving-tree review used production Java SHA256 `3a83ea859014c961d6f5982fc5d8a27e641f8705041341fb2bd9f52702b06dde` and identified three concrete issues: missing-audience claims could throw rather than reject, the protected service omitted a required nonblank subject, and rollback only created a draft. Both decoders now reject signed missing audience/subject/time claims, including original signed JSON subject-type checks; the complete 18-method unit run passed these cases. Rollback now copies and publishes a new monotonic immutable version and epoch atomically; its real PostgreSQL/Keycloak test passed in integration-3. These are evidence-backed individual resolutions, not a frozen final approval.

The first fully executing integration run independently exposed the administrative-lock/audit-FK conflict documented in CASE_STUDY and STATUS. A real PostgreSQL proof isolated the exact SQLSTATE 40001; the minimal NO KEY UPDATE repair passed the unchanged full nine-method real integration suite at 01:29:50 UTC and again under final JUnit 6.1.3 at 02:02:23 UTC (27 total methods each), including held older snapshots and concurrent publication readers. Final reviewer must check the successful frozen source, all nine integration outcomes, actual protected-resource demo and measured workload evidence before approving release.

Final read-only review handoff: executable fingerprint `working-tree-sha256:4bd439735ed2a4399ba8c0f9d7abfa3d28dcbec9bec81130d6472dfa27ea124d`; ordered production Java path/NUL/bytes/NUL SHA256 `f6319acff465bf9ec68033ee10d6726769f5710c3f9426270d50f7e8dd7195fc`. Final full real integration passed all 27 methods under actual JUnit6.1.3; full seed/demo/reset/repeat passed separately. Quiet performance and final A04 review are pending, and no final independent approval is claimed.

## Maintenance boundary after scoped review

The later image/cache maintenance changes source to `6e348ba58afa9df692b5b74f0edd66c71798ea6a4c3fe6cb0b09db0457567d13`, with no production Java change. Ordinary 18-method verification and eight actual integrity probes passed; exact image IDs are unchanged. The A04 acknowledgment above remains the reviewed correctness/security scope of `4bd4397…`, and is not silently extended to claim a new full integration/performance certification. Runtime-cache maintenance is presented for coordinator read review before measurement.

A05 also independently reviewed A06's precompiled identifier Pattern, v2 live-target history and capture boundaries. The source-only artifact stamp finding led to a coordinator manifest repair; follow-up identified an empty leading classpath element and ignored symlink directories. Coordinator accepted and is verifying those repairs. These are scoped findings and closures, not approval of A06's still-unrun full performance protocol.
