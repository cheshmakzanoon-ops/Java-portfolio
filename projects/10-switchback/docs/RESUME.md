# Development checklist

1. Read [architecture](ARCHITECTURE.md), [invariants](INVARIANTS.md), and [security](SECURITY.md) before changing domain or publication rules.
2. Run project-scoped ordinary verification. Use the integration profile for persistence, cancellation, recovery, or tenant-isolation changes; Docker is required.
3. Run `python3 projects/10-switchback/scripts/check-ui.py` after editing the browser interface. Its deterministic API fixtures exercise frontend behavior; `scripts/browser-check.py` separately exercises a running service.
4. Use the documented build/up/demo/down lifecycle for live validation. Reset only disposable demo state and preserve other tenants and retained evidence.
5. Treat source fingerprints as an execution boundary. The launcher refuses stale builds, and recovery refuses jobs submitted by incompatible binaries. JavaScript and CSS are included in the launch fingerprint.
6. Rerun a complete quiet performance protocol before attributing historical measurements to changed runtime code. Keep complete outcomes, including partial feasibility and budget overruns.

[Validation status](STATUS.md) records observed checks and remaining release work. [Performance](PERFORMANCE.md) defines the existing benchmark boundaries and source-specific results.
