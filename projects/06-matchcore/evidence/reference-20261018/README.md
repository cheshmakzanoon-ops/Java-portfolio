# Full reference evidence — 2026-10-18

These files record the completed quiet reservation, rather than the earlier shared-host smoke.

- `reference-results.json`: unrounded driver result objects for all three fresh million-command JVMs per mode, preserving every business outcome.
- `system-runs.csv`: selected per-run values with explicit unit suffixes; whole-process resource columns have a broader boundary than measured-loop columns.
- `jmh-results.json`: unchanged JMH output, including nine iteration values, three forks, confidence intervals and setup-inclusive allocation profiling.
- `measurement-envelope.json`: source/protocol/environment, exact reservation, variability, raw-file identities, independently recomputed JFR summaries, GC observations and limitations.
- `verified-artifacts.json`: actual prepared receipt binding 67 compiled/resource/classpath/dependency artifacts. Its read-only integrity check passed after analysis; it is a trusted local build receipt, not signed attestation.

The original journals, recordings, event JSON and GC logs are preserved in the verified compressed archive described in [raw-artifact-storage.json](raw-artifact-storage.json). The historical `.runtime/reference.2lkNWp` directory was removed only after independent physical recovery and an immediate full archive/original recheck. Raw paths in the unchanged envelope and result objects now require restoration; they do not identify currently present files. The envelope still indexes and hashes the same 83 raw files. No original failed or diagnostic run was overwritten. Documentation and analysis evidence are excluded from the executable/build-input fingerprint. The six measured runs use the same fixed seed and peak at only 27 live orders; the 100,000 cap is configuration, not measured depth. Read `docs/PERFORMANCE.md` for the complete boundaries before comparing rates.

`system-runs.csv` is a derived table, outside the 83-file raw index. Its reviewed working serialization used CRLF (1,575 bytes, SHA-256 `f8d764e67c8c284f519330a2ad6262b51c6cbef6db3dae7f52daee12096a26e4`). Git's `text=auto` stored LF; the working derivative now explicitly matches that published serialization (1,568 bytes, SHA-256 `9d135d845c69c1244179e0559c04d299495ddebcbc56b18843e1ef7089f244da`). Parsed rows and values are identical. The envelope preserves both identities; the earlier review packet fingerprint identifies the original working bytes. No measurement or raw-file identity changed.


## Restore the original raw files

The retained local archive is `.work/A06/archive/reference.2lkNWp.0n0jw_63/reference.2lkNWp.tar.gz` from the repository root: **165,957,826 bytes**, SHA-256 `4a12347b29bad6e7ffe4ec149ab1c783bc9dbebde23a70fca0025bceded4f361`. It contains all **98 regular files (1,588,052,903 bytes) and 15 directories**: the reviewed 83 raw files plus 15 batch-level stdout/stderr/result artifacts, with no exclusions or newline conversion. The archive is ignored local data, not included in a Git clone; retain or transfer this exact archive separately.

The [complete manifest](raw-archive-manifest.json) records every original relative path, binary SHA-256, byte size and permission mode. Its original bytes are 50,024 bytes with SHA-256 `618917cde75957e9575246ba9b18492f453dc4e75b409c801f4bfcd3b73ee9c0`. An independent check [physically restored and compared](raw-archive-recovery.json) all 113 entries against both manifest and originals. Immediately before authorized removal, the owner again read the entire archive, checked gzip integrity and all original/archived hashes, modes, sizes and complete sets; [the replacement receipt](raw-archive-replacement.json) lists the exact removed paths and observed free-space delta. No other runtime directory was removed.

From the repository root, with the retained archive available, restore into a fresh directory (allow at least 1.59 GB for restored content):

```bash
archive=.work/A06/archive/reference.2lkNWp.0n0jw_63/reference.2lkNWp.tar.gz
printf '%s  %s\n' 4a12347b29bad6e7ffe4ec149ab1c783bc9dbebde23a70fca0025bceded4f361 "$archive" | sha256sum --check
restore=$(mktemp -d .work/A06/restore-reference.XXXXXX)
tar --extract --gzip --file "$archive" --directory "$restore" \
  --no-same-owner --same-permissions --keep-old-files
```

Verify the restored complete set, regular-file binary hashes/sizes and permission modes:

```bash
python3 - "$restore" <<'PYVERIFY'
import hashlib, json, stat, sys
from pathlib import Path
base = Path(sys.argv[1])
manifest = json.loads(Path('projects/06-matchcore/evidence/reference-20261018/raw-archive-manifest.json').read_text())
expected = manifest['entries']
restored = base / manifest['archiveRoot']
actual = {p.relative_to(base).as_posix(): p for p in [restored, *restored.rglob('*')]}
assert set(actual) == set(expected)
for name, item in expected.items():
    p = actual[name]
    s = p.lstat()
    assert stat.S_IMODE(s.st_mode) == item['mode']
    if item['kind'] == 'directory':
        assert stat.S_ISDIR(s.st_mode)
    else:
        assert stat.S_ISREG(s.st_mode) and s.st_size == item['bytes']
        h = hashlib.sha256()
        with p.open('rb') as f:
            for block in iter(lambda: f.read(1048576), b''):
                h.update(block)
        assert h.hexdigest() == item['sha256']
print('PASS: complete restored paths, modes, sizes and binary hashes')
PYVERIFY
```

Historical raw locators beginning `projects/06-matchcore/.runtime/reference.2lkNWp` map to `$restore/reference.2lkNWp` plus the same suffix. The original reviewed packet identity `7f6e9002ce30dc765d3b1081aa9f470c0c6760b1382ca908d62f8a62dcbc1a48`, unchanged measurement envelope/results, original raw hashes and measured source identity remain historical evidence; archival metadata and this storage update do not replace or recalculate them. Archiving is capacity maintenance, not a new measurement or performance improvement.
