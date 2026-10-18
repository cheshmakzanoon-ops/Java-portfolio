# Scoped source and measurement review

A04 independently reviewed executable fingerprint `4bd439735ed2a4399ba8c0f9d7abfa3d28dcbec9bec81130d6472dfa27ea124d` against 27 passing methods, including nine real PostgreSQL/Keycloak tests under JUnit 6.1.3 and the 21-assertion protected demo. Both JWT paths, expected-prior atomic rollback, RR/NO KEY UPDATE, membership/resource snapshots, transaction-local RLS and immutable cache keys were inspected. Earlier missing-audience/subject and draft-only rollback findings are closed within that tested correctness/security scope. No new blocking source finding arose.

Later image pins, artifact integrity and the JMH metadata repair changed source to `832dfc3ccd0bf9399528721f8a31710c4b0197129b0b183aadcd5a1afeb92c11` without production Java changes. Ordinary 18-method verification, eight actual integrity probes and JMH discovery passed. Failed quiet attempt 5 is preserved; successful quiet attempt 6 completed the full study. These are distinct evidence scopes, not a new integration rerun of every configuration spelling.

## Measured-artifact packet ready

Final independent measurement review is requested and has not yet been received. Review the envelope, CSV, JMH summary and artifact receipt under `evidence/reference-20261018/`, plus original `http-1..3.json`, `http-server-1..3.json`, `jmh.json` and `environment.json` one directory above. The envelope indexes 22 archived raw files, including all six JFR recordings. The owner verified their hashes/sizes, executable hashes, three full request/error/denial counts, rates/statistics and both JMH histogram means. Server summaries are the original FlightSummary output; analysis did not perform an independent event recount or Java profiling rerun.

Check the progressive slowdown, 23.32% sample CV and 38.10% first-to-third throughput decline. Fresh client JVMs shared the continuing server/database and growing audit history. Per-run percentiles, closed-loop coordinated omission, application prepared-statement counts, cache size 50 versus limit 500, periodic JVM CPU fractions, sampled allocation and GC-event durations all retain their specific boundaries. JMH measures separate in-memory components. No stable capacity, attributed slowdown, production SLA or optimization speedup is claimed.

Clean-checkout reproduction, repository-wide/remote-CI gates and release approval remain coordinator work. This records actual scoped acknowledgments, not an unreceived final approval.

## A04 measured-artifact closure

A04 independently reproduced the reviewed19-file/730510-byte packet SHA584579ad789f89a231f64cc7d2dee2e4b46e87d4c11e4dcf3dce9bb74e10e263 and executable source832dfc3ccd0bf9399528721f8a31710c4b0197129b0b183aadcd5a1afeb92c11. All22 archived raw files/72,670,683bytes, six JFR hashes/70,931,991bytes, actual three JARs, eight source-evidence hashes and every published CSV/statistic matched. Three100k-request/20k-DENY/zero-error runs and both3-fork JMH histogram summaries agree with the raw evidence.

Closure: PASS, no blocking finding in measured-artifact identity, arithmetic or claims. ThroughputCV23.316551% and first-to-third decline38.095545% remain disclosed; no stable capacity, causal slowdown or speedup claim is approved. No fresh JFR event recount was performed; sample/GC boundaries remain explicit. This closes the earlier measurement-review item only; clean-checkout, global/remote CI and final release approval remain separate. The reviewed packet identity refers to the pre-closure files; this note is subsequent review metadata.
