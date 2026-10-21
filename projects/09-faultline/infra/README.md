# Offline CLI infrastructure

Faultline requires no runtime service, database, container, port, paid account or network dependency resolver. There is intentionally no Compose service to start. `scripts/up.sh` prepares only owned local CLI state; `scripts/down.sh` is a local CLI teardown message. Tests and demonstrations compile trusted fixture sources with the JDK and scan their bytes without executing target archives.

Use the repository's Java25/cloud environment setup for building; scanning requires only the JDK and shaded CLI artifact. Report/output directories are explicit caller-owned filesystem paths. See `docs/OPERATIONS.md` for commands and failure interpretation.
