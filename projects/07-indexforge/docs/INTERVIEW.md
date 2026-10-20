# Engineering walkthrough

Start with the CLI demo, then follow these paths through source and tests.

1. **Stable input identity.** Trace `SafeSourceReader`, `ContentAddressedStore`, `SnapshotManifest`, and `IngestionJob`. Explain why strict UTF-8, canonical repository-relative paths, digests, explicit deletions, and immutable manifests are prerequisites for retry safety.
2. **Snapshot lifetime.** Trace `IngestionCoordinator`, `GenerationBuilder`, `GenerationPublisher`, and `GenerationLease`. Explain staging ownership, first-publication cleanup and why shutdown freezes admission across all tenants before closing readers. Demonstrate recovery from a corrupt current pointer and old-reader validity during replacement. Distinguish injected faults from hardware power-loss tests.
3. **Ambiguous success.** Show how committed job identity and completion-receipt reconciliation stop an old retry from overwriting a newer generation.
4. **Access control.** Trace authenticated context through retrieval, aggregates, suggestions, explanations, cache identity, cursor validation, and permission recheck. Explain the residual BM25 statistical channel described in [security](SECURITY.md).
5. **Conservative source facts.** Show unsupported syntax, unknown receivers, enum overrides, supplementary identifier quotas, and child-process watchdog tests. Syntax observations are not compiler symbol attribution.
6. **Evaluation discipline.** Trace the shared serving/export ranking policy and split-before-parse boundary, then recompute metric examples, explain the frozen development/validation split, and inspect the failed candidate decisions. Discuss why better nDCG did not justify worse identifier MRR, and why the baseline remains selected.

Useful tradeoffs include full-replacement disk cost, bounded 500-candidate pagination, conservative analysis that can miss useful facts, manual content-store retention, and local-filesystem durability assumptions. Lucene owns indexing and BM25; the original work is the lifecycle, policy, bounds, and application algorithms around it.
