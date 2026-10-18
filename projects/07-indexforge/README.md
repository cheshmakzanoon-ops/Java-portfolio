# IndexForge

Local-first, transactional search over explicitly selected UTF8 source snapshots and artifact metadata. Original work covers safe ingestion, manifest idempotency, index-generation publication/recovery, generation leases, bounded source-aware query planning, tenant/repository authorization, signed pagination, and relevance evaluation. Apache Lucene provides the inverted index, BM25, postings and segments.

Requires JDK25 and repository Maven wrapper. No Docker or external search server is needed.

```bash
source scripts/cloud-env.sh # managed cloud environment only
./mvnw -f projects/07-indexforge/pom.xml -Dmaven.repo.local="$PWD/.work/A07/m2" -B -ntp -Pintegration verify
projects/07-indexforge/scripts/verify.sh
projects/07-indexforge/scripts/up.sh
projects/07-indexforge/scripts/demo.sh
```

The demo uses20,000 deterministic documents over three tenant-owned directories and asserts retrieval, explicit deletion under search load, failed-build recovery and inaccessible-repository isolation. Set `INDEXFORGE_DOCUMENTS=2000` for CI. `up` prepares local CLI data and starts no daemon. Optional authenticated Boot HTTP startup and protected OIDC configuration are in [operations](docs/OPERATIONS.md).

[Architecture](docs/ARCHITECTURE.md) · [security](docs/SECURITY.md) · [testing](docs/TESTING.md) · [performance](docs/PERFORMANCE.md) · [case study](docs/CASE_STUDY.md) · [exact execution status](docs/STATUS.md) · [CLI](contracts/cli.md) · [HTTP](contracts/http.md).

Acceptance remains governed by observed evidence in STATUS; the benchmark targets are not assumed achieved.
