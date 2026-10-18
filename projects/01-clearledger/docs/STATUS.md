# A01 ClearLedger status

Owner:A01. Paths:projects/01-clearledger/** and.work/A01/**. Foundation:d6e0c2b6de4a9681903cbf4b0c70d540c9ee5d6a. Worker has made no Git writes.

At2026-10-18T00:36:36UTC the frozen complete project `-Pintegration verify` passed:9unit/architecture/property methods (including500generated cases),13real-PostgreSQL integration methods,0failures/errors/skips. Core JaCoCo:76/79lines(96.20%),51/56branches(91.07%); explicit85/75gate passed. Exact command, tested source fingerprint, methods and per-acceptance mapping: `evidence/integration.json`. Raw log:.work/A01/integration-resumed.log; standard reports under each module target. Earlier failures and their actual corrections remain in evidence/initial-integration-diagnosis.json and .work/A01/integration-final.log.

M1 domain/architecture, M2 atomic persistence and M3 authenticated adapters are implemented and verified for the recorded scenarios. M4 reconciliation/replay/restore is implemented and verified. M5 release evidence remains IN_PROGRESS.

| Acceptance | Status | Evidence and remaining work |
|---|---|---|
| P01-A1 | PASS | Generated/reference comparison, direct runtime-role denial, SQL invalid journals/overflow, reversal race and domain properties |
| P01-A2 | IN_PROGRESS |64duplicates, closure/reversal races, protected OIDC/twoAPIinstances, tenant isolation and actual JVM crash receipts passed. Literal HTTP-after-commit-before-response process barrier remains to add: present CrashWriter has no HTTP adapter. |
| P01-A3 | PASS | Five row categories, duplicate imports, manual audit, missing-reference non-match, CSV escaping/bounds |
| P01-A4 | PASS | Earlier allocated event committing later, redelivery, verified shadow replay, clean-instance backup restore preserving ACLs/runtime denial |
| P01-A5 | IN_PROGRESS | Script demo currently running; full100k/20tenant/10kaccount workload1/8/32writers×3quiet runs, final mutation and independent review remain |

Initial domain mutation passed87%(27/31),0timeouts/uncovered. Investigation found three non-equivalent test gaps; new accumulation and economic-digest tests passed the full ordinary verification above. Final mutation rerun remains required. Preserve prior HTML at ledger-domain/target/pit-reports and diagnostic evidence/initial-mutation.json. Same-class Jupiter/jqwik XML report collision is explicitly disclosed in integration evidence; console proves all9unit methods. Separate engine class names in a later verified test-layout cleanup.

Commands executed: source scripts/cloud-env.sh; ./mvnw -f projects/01-clearledger/pom.xml -Dmaven.repo.local=/workspace/Java-portfolio/.work/A01/m2 -B -ntp com.diffplug.spotless:spotless-maven-plugin:3.0.0:apply; same wrapper arguments with -Pintegration verify. Script run in progress:projects/01-clearledger/scripts/demo.sh, log.work/A01/demo.log. One Maven/build slot and one Docker stack slot assigned; no concurrent A01 Maven jobs. Four-CPU host; measure full performance only in coordinator's isolated window.

Next: finish concrete script demo; preserve current passing source for coordinator milestone review/commit; then add the missing HTTP crash-boundary adapter and separate JUnit/jqwik report classes, rerun required affected checks, run final mutation and full workload. Preliminary read-only A01→A02 findings were accepted and corrected by A02 but require its own fresh validation; do not claim independent review closure for this project. A10→A01 review remains pending.

Coordinator milestone review (2026-10-18): this is a partial implementation checkpoint. A10 independent review found pending hardening: scope projection freshness/pending counts to the authenticated tenant; restrict global replay to a distinct platform authority; reject blank/coerced subject and tenant JWT claims with signed-token regression tests. The recorded13IT pass does not establish these untested cases. These findings remain open and block final security/release acceptance.
