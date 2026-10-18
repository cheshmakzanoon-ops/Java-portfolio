#!/usr/bin/env python3
"""Save changed authored scratch sources, never generated runtime artifacts."""
import datetime
import hashlib
import json
import pathlib
import stat
import subprocess

root = pathlib.Path(__file__).resolve().parents[2]
base = root / "docs/execution/checkpoint-sources"
prior = {}
for manifest in sorted(base.glob("*/manifest.json")):
    for row in json.loads(manifest.read_text())["files"]:
        prior[row["originalPath"]] = row["sha256"]
candidates = set()
for directory in [root / ".work/coordinator", *sorted((root / ".work").glob("A[0-9][0-9]"))]:
    candidates.update(path for path in directory.iterdir()
                      if path.is_file() and not path.is_symlink()
                      and path.suffix in {".py", ".sh", ".ps1", ".md", ".java"})
for name in ["wave3-representation-audit", "wave3-identifier-usage-amendment"]:
    candidates.update(path for path in (root / ".work/A07" / name).rglob("*")
                      if path.is_file() and not path.is_symlink()
                      and path.suffix in {".py", ".sh", ".md", ".java"})
candidates.update(path for path in (root / ".work/A03/durableflow-quiet18-postmortem").glob("*")
                  if path.is_file() and not path.is_symlink()
                  and path.suffix in {".py", ".sh", ".md", ".java"})
stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
destination = base / stamp
rows = []
for source in sorted(candidates):
    data = source.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    original = str(source.relative_to(root))
    if prior.get(original) == digest:
        continue
    if len(data) > 2 * 1024 * 1024:
        raise RuntimeError(f"Authored source quota: {original}")
    relative = pathlib.Path("files") / source.relative_to(root / ".work")
    if source.suffix == ".md":
        relative = relative.with_name(relative.name + ".txt")
    target = destination / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("xb") as stream:
        stream.write(data)
    assert target.read_bytes() == source.read_bytes(), original
    rows.append(dict(originalPath=original, snapshotPath=str(relative), bytes=len(data),
                     sha256=digest, originalMode=oct(stat.S_IMODE(source.stat().st_mode))))
if not rows:
    raise RuntimeError("No changed authored sources")
manifest = dict(schemaVersion=1, capturedAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                baseCommit=subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
                scope="Changed authored helper/handoff byte snapshots. Uncompiled or unfinished work is not certified. Generated runtime data remains local.",
                files=rows)
(destination / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
(destination / "README.md").write_text(
    "# Authored work checkpoint\n\nByte-preserving snapshots listed in [manifest.json](manifest.json). "
    "Original paths, hashes and modes are recorded. Markdown source snapshots use `.md.txt`. "
    "This checkpoint preserves unfinished work and does not establish build or acceptance success. "
    "Generated caches, binaries, databases and profiles remain local.\n")
print(json.dumps(dict(directory=str(destination.relative_to(root)), files=len(rows),
                      bytes=sum(row["bytes"] for row in rows))))
