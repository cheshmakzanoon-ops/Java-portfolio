# Local process infrastructure

QuorumKV uses three actual Java processes with independent directory locks and durable directories. No external database, broker or Docker stack is required. `scripts/up.sh` starts them and `scripts/down.sh` preserves their data. A Compose file would add a container runtime without changing the shared physical failure domain and is intentionally unnecessary here. The explicit process configuration is in `processes.properties`; the Java launcher enforces loopback, heaps, fixed membership and per-node paths.
