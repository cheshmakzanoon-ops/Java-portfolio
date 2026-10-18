import datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess

root = Path('/workspace/Java-portfolio')
project = root / 'projects/05-tenantshield'
runtime = root / '.work/A05/runtime'
manifest = runtime / 'build-manifest.json'
environment = dict(os.environ, TENANTSHIELD_NO_MAVEN='1')
results = []

def check(label, success, script='prepare-runtime.sh'):
    result = subprocess.run([str(project / 'scripts' / script)], env=environment, text=True, capture_output=True, timeout=60)
    assert (result.returncode == 0) == success, (label, result.returncode, result.stdout, result.stderr)
    assert 'BUILD SUCCESS' not in result.stdout and 'Waiting for shared build slot' not in result.stdout
    results.append(dict(name=label, exitCode=result.returncode, expectedSuccess=success))
    print('PASS',label,flush=True)

check('valid exact prepared cache succeeds without Maven', True)
original_manifest = manifest.read_bytes()
try:
    value = json.loads(original_manifest)
    value['sourceRevision'] = 'stale-probe'
    manifest.write_text(json.dumps(value))
    check('stale manifest refuses no-Maven startup before Docker', False, 'up.sh')
finally:
    manifest.write_bytes(original_manifest)

probe = project / '.cache-integrity-probe'
assert not probe.exists()
try:
    probe.write_text('A temporary executable-input change for the stale-source rejection probe\n')
    check('changed executable source refuses no-Maven preparation', False)
finally:
    probe.unlink()

artifact = project / 'demo-resource-server/target/tenantshield-demo-resource-server-1.0.0-SNAPSHOT-exec.jar'
original_artifact = artifact.read_bytes()
try:
    with artifact.open('ab') as stream:
        stream.write(b'actual modified artifact probe')
    check('modified executable JAR refuses no-Maven preparation', False)
finally:
    artifact.write_bytes(original_artifact)

archive = project / 'verification/target/tenantshield-verification-1.0.0-SNAPSHOT-exec.jar'
directory = runtime / 'classpath-v3' / hashlib.sha256(archive.read_bytes()).hexdigest()
cached_class = sorted((directory / 'classes').rglob('*.class'))[0]
original_class = cached_class.read_bytes()
try:
    cached_class.write_bytes(b'actual modified extracted class probe')
    check('modified extracted class refuses cache reuse', False)
finally:
    cached_class.write_bytes(original_class)

extra = directory / 'lib/unverified-extra.jar'
assert not extra.exists()
try:
    extra.write_bytes(b'actual extra classpath dependency probe')
    check('extra classpath dependency refuses cache reuse', False)
finally:
    extra.unlink()

extra = directory / 'classes/unverified-link'
assert not extra.exists()
try:
    extra.symlink_to(cached_class)
    check('symlinked runtime entry refuses cache reuse', False)
finally:
    extra.unlink()

check('restored exact cache succeeds without Maven', True)
source = subprocess.check_output(['python3', str(project / 'scripts/fingerprint.py')], text=True).strip()
out = dict(schemaVersion=1,project='tenantshield',timestamp=datetime.datetime.now(datetime.timezone.utc).isoformat(),sourceRevision=source,environment='environment.json',command='python3 .work/A05/cache-probes.py',rawArtifact='../../../.work/A05/cache-probes.log',exitCode=0,assertions=dict(passed=len(results),failed=0),checks=results,artifactManifest=json.loads(original_manifest),limitations=['Cache integrity checks bind bytes to an actual successful local verify; they are not remote signed supply-chain attestation.','No service or container was started; full workload measurements remain unrun.'])
(project / 'evidence/cache-preparation.json').write_text(json.dumps(out,indent=2)+'\n')
