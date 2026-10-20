# IndexForge

Transactional search over local source snapshots and artifact metadata, built with Java 25 and Apache Lucene. IndexForge owns safe ingestion, immutable manifests, index publication and recovery, generation leases, access control, signed pagination, and reproducible relevance evaluation. Lucene supplies the inverted index, BM25, postings, and segments.

## Build and run

Run from the repository root with JDK 25 and the checksum-pinned Maven wrapper. This project requires no Docker daemon, external search server, or paid service.

```bash
source scripts/cloud-env.sh # managed cloud workspace
projects/07-indexforge/scripts/verify.sh
projects/07-indexforge/scripts/up.sh
projects/07-indexforge/scripts/demo.sh
projects/07-indexforge/scripts/down.sh
```

`up` prepares local CLI state; it does not start a daemon. The demo creates 20,000 deterministic documents across three tenants and asserts retrieval, explicit deletion during concurrent search, failed-build recovery, and denied-repository isolation. Use `INDEXFORGE_DOCUMENTS=2000` for the smaller CI workload. Optional authenticated HTTP startup is documented in [operations](docs/OPERATIONS.md).

## Design

- Each request leases one committed index generation for results, counts, facets, snippets, and pagination.
- Publication validates and warms a replacement before atomically changing a checksummed pointer. Recovery preserves the last valid generation.
- Authorization applies before retrieval and aggregations. Permission versions are checked again before returning a response.
- Source analysis is bounded and conservative. Unsupported syntax or uncertain dispatch produces unknown observations, rather than guessed bindings.

## Evidence and limits

The [validation status](docs/STATUS.md) distinguishes current checks from historical measurements. The published relevance experiments failed the stated quality targets; the baseline remains the selected configuration. Historical performance results apply to their frozen artifact and application boundary, excluding HTTP transport. This is a portfolio implementation, with no production deployment or certification claim.

[Architecture](docs/ARCHITECTURE.md) · [security](docs/SECURITY.md) · [testing](docs/TESTING.md) · [performance](docs/PERFORMANCE.md) · [case study](docs/CASE_STUDY.md) · [CLI contract](contracts/cli.md) · [HTTP contract](contracts/http.md)
