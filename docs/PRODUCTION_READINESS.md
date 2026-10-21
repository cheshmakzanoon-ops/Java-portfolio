# Production readiness work register

Started 2026-10-21 from `d8741ff567357e716ac2f6b9f4c5e4acc1914e37` on `main`.
This register distinguishes current execution from historical evidence. It is not a release certificate.

## Release contract

All 63 criteria in [release.md](release.md) and [acceptance-matrix.md](acceptance-matrix.md) remain mandatory. No threshold, finding, failure, or required test may be suppressed to obtain a pass. Preserve APIs, persistence compatibility, independent project boundaries, and historical measurements. Commit verified milestones directly to `main` and push them. The existing cloud checkout is used; no worktrees or feature branches are needed.

## Dependency-ordered execution

1. Audit shared toolchain, wrapper, CI/integration/mutation/security/release workflows and release evidence controls. Correct the invalid four-component setup-java version while retaining the checksum-pinned Temurin build. Verify the harness rejects deliberate failures.
2. Inspect actual implementation and tests in all ten projects in independent parallel scopes. Run current ordinary/integration tests; diagnose failures; fix concrete defects with regression coverage. Run strict existing mutation gates after affected changes.
3. Exercise deployment/demo/stop/restart/recovery paths, required database/broker/issuer integration, malformed input, resource limits, authorization and failure handling. Record supported environments and limits.
4. Regenerate SBOM and scan dependencies and pinned images without exclusions. Resolve actionable HIGH/CRITICAL findings; record external feed/access blockers separately.
5. Freeze measured source and artifacts before capacity or overhead certification. Run documented full protocols in an exclusive quiet window. Preserve failures and raw results; exposed held-out data cannot become a new training set followed by a claimed independent certification.
6. Run clean-checkout validation, all standalone demos, simultaneous services, architecture/link/tooling checks, and release audit. Observe hosted Actions on the pushed source. Update acceptance only from current, criterion-specific evidence.

## Ownership and current status

| Scope | Work | Current state |
| --- | --- | --- |
| Shared tooling, workflows, security, integration, release | Primary agent | Baseline inspection; Git HTTPS read and dry-run push succeeded; Docker 28.4.0 available |
| 01 ClearLedger / 02 DurableFlow | Independent project agent | Source audit and real-PostgreSQL integration underway |
| 03 QuorumKV / 04 PulseStream | Independent project agent | Source audit and verification underway |
| 05 TenantShield / 06 MatchCore | Independent project agent | Source audit and verification underway |
| 07 IndexForge | Independent project agent | Current functional baseline underway; relevance/performance gates open |
| 08 ThreadLens | Independent project agent | Current functional baseline underway; overhead gate open |
| 09 ByteGuard / 10 RouteWeaver | Independent project agent | Source audit and verification underway |

## Baseline issues and dependencies

| Severity | Finding | Affected criteria | Required resolution/evidence |
| --- | --- | --- | --- |
| Release blocker | Existing matrix has 43 historical PASS, 18 IN_PROGRESS and 2 FAIL; none is inherited as current certification | All 63 | Current-source test and criterion evidence, then strict release audit |
| High | Workflows pass `25.0.4.1+1` to setup-java's semver parser | R-A02/R-A03/R-A07/R-A10 | Checksum-verified exact Temurin installation using a supported action configuration |
| High | IndexForge published held-out relevance misses .75 nDCG / .90 identifier MRR; existing corpus was exposed | P07-A4 | Valid independent protocol and corpus, model frozen before evaluation, full baseline/ablation/per-query evidence |
| High | ThreadLens historical 84-JVM overhead study fails thresholds | P08-A5 | Diagnose/repair and rerun full prescribed 84×90-second study on quiet host (126 minutes minimum observation) |
| High | Hosted Actions success is unverified; GitHub REST is denied by the current egress proxy and CLI authentication is unusable | R-A02/R-A10 | Observe actual runs through an accessible supported channel; Git push does not prove Actions success |
| High | Mandatory current container/dependency security scans not yet executed | R-A07 and security release prerequisite | Current scanner database, exact pinned images and regenerated complete SBOM |
| Medium | Windows/macOS execution unavailable on this Linux host | R-A02 | Run available platform checks; state native platforms remain unverified |

## Evidence policy and resumption

Store current machine-readable command outcomes with source/input hashes, timestamps, report counts and log hashes. Keep generated classes, dependency caches, recordings and large raw output in ignored paths; commit compact source-bound evidence and reproducible commands. Zero-test runs, skipped integration, partial studies, source changes after measurement and historical PASS entries cannot certify a current gate.

The ongoing task remains incomplete until required checks finish or concrete external blockers exhaust useful independent work. See the latest sections below for milestones and exact resumption commands as they are completed.

## Milestone: reproducible workflow toolchain

Replaced every invalid `setup-java` version request with a shared local action that invokes the existing exact checksum-pinned Temurin bootstrap. The bootstrap now accepts a tools-directory override so it works on GitHub runners without writing `/workspace`. Existing cloud activation remains supported.

Current local evidence: [toolchain receipt](execution/production-toolchain-20261021.json). A fresh temporary installation verified the pinned archive and ran Java/javac 25.0.4.1; Maven 3.10.0 runs with the existing wrapper checksum. All six workflows pass actionlint 1.7.7. Fifty tooling tests pass, including rejection of corrupted JDK archives and mismatched installed runtimes; documentation links and source architecture pass. The baseline release audit correctly exits 1 with 20 unresolved historical rows. Hosted execution remains unverified.

## Milestone: current-source release evidence control

The original release audit accepted historical PASS rows solely when their referenced files existed. It now also requires a current `docs/execution/release-current.json` receipt covering exactly all mandatory criteria, bound to a SHA-256 fingerprint of source, tests, configuration and release contracts. Every PASS needs existing evidence with matching hashes. Missing, edited, or escaping evidence, missing source files, changed source, non-PASS criteria and duplicate matrix rows fail closed. Evidence/output directories and this progress register are excluded from the source fingerprint to avoid self-reference.

[Release-control evidence](execution/production-release-controls-20261021.json): 56 tooling tests pass, including six failure-boundary regressions. The release audit still fails as intended: 20 historical rows remain incomplete and a complete current release receipt does not yet exist. This control verifies identity and completeness; it does not replace review of the actual criterion-specific results.
