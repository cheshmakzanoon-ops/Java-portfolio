"""Bind successful reactor outputs to the unchanged source and exact executable JAR bytes."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

project = Path(__file__).resolve().parents[1]
runtime = project.parents[1] / '.work/A05/runtime'
manifest = runtime / 'build-manifest.json'
artifacts = [project / module / 'target' / ('tenantshield-' + module + '-1.0.0-SNAPSHOT-exec.jar')
             for module in ('decision-service', 'demo-resource-server', 'verification')]

def source():
    return subprocess.check_output(['python3', str(project / 'scripts/fingerprint.py')], text=True).strip()

def hashes():
    result = {}
    for path in artifacts:
        if not path.is_file() or path.is_symlink():
            raise ValueError('Missing or symlinked verified artifact: ' + str(path))
        result[str(path.relative_to(project))] = hashlib.file_digest(path.open('rb'), 'sha256').hexdigest()
    return result

try:
    if sys.argv[1] == 'record':
        before = sys.argv[2]
        if source() != before:
            raise ValueError('Source changed during successful verification; no build stamp written')
        value = {'schemaVersion': 1, 'sourceRevision': before, 'artifactsSha256': hashes()}
        if source() != before:
            raise ValueError('Source changed during artifact capture; no build stamp written')
        temporary = manifest.with_suffix('.tmp')
        temporary.write_text(json.dumps(value, indent=2) + '\n')
        temporary.replace(manifest)
        (runtime / 'build.fingerprint').write_text(before + '\n')
    elif sys.argv[1] == 'verify':
        if manifest.is_symlink():
            raise ValueError('Symlinked build manifest')
        value = json.loads(manifest.read_text())
        if value.get('schemaVersion') != 1 or value.get('sourceRevision') != source() or value.get('artifactsSha256') != hashes():
            raise ValueError('Source or verified executable bytes differ from successful build manifest')
    else:
        raise ValueError('Expected record or verify')
except (OSError, ValueError, KeyError, IndexError) as failure:
    print('Verified build cache rejected: ' + str(failure), file=sys.stderr)
    raise SystemExit(1)
