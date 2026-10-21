# Transactional demonstration

`projects/07-stacks/scripts/demo.sh` uses an isolated, retained runtime directory and the CLI's deterministic seed7007 generator. Default20,000 UTF8 documents are distributed across three tenant-owned indexes. CI sets `STACKS_DOCUMENTS=2000`. The real process asserts exact identifier retrieval, prose retrieval, hidden-repository invisibility, complete snapshot replacement with explicit deletion under concurrent search, interruption before commit, process-level close/reopen recovery, and post-restart authorization.

The executable reports its actual source-byte count and assertion outcome. It throws/exits nonzero on any unexpected result. The interrupted build is injected live, not prerecorded; the scenario does not claim an actual hardware power cut. Authenticated HTTP/JWKS behavior runs independently through the integration suite. Run with the source fingerprint validated by scripts/verify.sh. Down preserves data; reset-demo removes only seeded CLI runs.


The authenticated [HTTP workflow](http_workflow.py) exercises durable ingestion, polling, signed pagination, suggestions, completed-job resume, reindex and tenant metrics. The [operations guide](../docs/OPERATIONS.md) provides the two source files and exact service/client commands. The client reads the disposable token file without printing its contents and exits nonzero on an unexpected response. Stacks exposes JSON HTTP and CLI surfaces; it has no browser UI.
