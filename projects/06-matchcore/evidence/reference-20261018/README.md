# Full reference evidence — 2026-10-18

These files record the completed quiet reservation, rather than the earlier shared-host smoke.

- `reference-results.json`: unrounded driver result objects for all three fresh million-command JVMs per mode, preserving every business outcome.
- `system-runs.csv`: selected per-run values with explicit unit suffixes; whole-process resource columns have a broader boundary than measured-loop columns.
- `jmh-results.json`: unchanged JMH output, including nine iteration values, three forks, confidence intervals and setup-inclusive allocation profiling.
- `measurement-envelope.json`: source/protocol/environment, exact reservation, variability, raw-file identities, independently recomputed JFR summaries, GC observations and limitations.
- `verified-artifacts.json`: actual prepared receipt binding 67 compiled/resource/classpath/dependency artifacts. Its read-only integrity check passed after analysis; it is a trusted local build receipt, not signed attestation.

The large original journals, recordings, event JSON and GC logs remain under `.runtime/reference.2lkNWp`; the envelope indexes and hashes all 83 raw files. No original failed or diagnostic run was overwritten. Documentation and analysis evidence are excluded from the executable/build-input fingerprint. The six measured runs use the same fixed seed and peak at only 27 live orders; the 100,000 cap is configuration, not measured depth. Read `docs/PERFORMANCE.md` for the complete boundaries before comparing rates.

`system-runs.csv` is a derived table, outside the 83-file raw index. Its reviewed working serialization used CRLF (1,575 bytes, SHA-256 `f8d764e67c8c284f519330a2ad6262b51c6cbef6db3dae7f52daee12096a26e4`). Git's `text=auto` stored LF; the working derivative now explicitly matches that published serialization (1,568 bytes, SHA-256 `9d135d845c69c1244179e0559c04d299495ddebcbc56b18843e1ef7089f244da`). Parsed rows and values are identical. The envelope preserves both identities; the earlier review packet fingerprint identifies the original working bytes. No measurement or raw-file identity changed.
