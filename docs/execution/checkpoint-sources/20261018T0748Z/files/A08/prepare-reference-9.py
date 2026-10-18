#!/usr/bin/env python3
"""Prepare certified snapshots only; never launches Java or Maven."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import io
import json
import os
import subprocess
import tarfile

ROOT = Path('/workspace/Java-portfolio')
INPUTS = ROOT / '.work/A08/reference-study-9-inputs'
OUTPUT = ROOT / '.work/A08/reference-study-9'
CHECKPOINT = ROOT / '.work/A08/repair-v3-checkpoint.json'
EXPECTED_CHECKPOINT_SHA256 = 'fe9d4c6ad70294937d5d9f38f0bb83caf26a354e2d0f57affc35ac98370fe473'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def snapshot(path, expected=None):
    data = Path(path).read_bytes()
    if expected is not None and digest(data) != expected:
        raise RuntimeError('Certified identity changed: ' + str(path))
    return data


def write_verified(path, data, expected):
    if digest(data) != expected:
        raise RuntimeError('Snapshot identity mismatch: ' + str(path))
    with Path(path).open('xb') as output:
        output.write(data)
    if digest(Path(path).read_bytes()) != expected:
        raise RuntimeError('Written identity mismatch: ' + str(path))


def main():
    if INPUTS.exists() or OUTPUT.exists():
        raise SystemExit('Refusing to replace previous prepared inputs or trial output')
    checkpoint_bytes = snapshot(CHECKPOINT, EXPECTED_CHECKPOINT_SHA256)
    checkpoint = json.loads(checkpoint_bytes)
    if checkpoint['exitCode'] != 0:
        raise SystemExit('Successful functional/mutation certification required')
    # Every copy below comes from these initially validated bytes, not a later reread.
    sources = {name: snapshot(ROOT / name, expected)
               for name, expected in checkpoint['sourceFiles'].items()}
    artifacts = [(artifact, snapshot(artifact['path'], artifact['sha256']))
                 for artifact in checkpoint['runtimeArtifacts']]
    agent_data = snapshot(checkpoint['agentArtifact']['path'], checkpoint['agentArtifact']['sha256'])
    proofs = []
    for directory, result_name in [('repair-cause-v3', 'summary.json'),
                                   ('repair-observer-stress-v3', 'completed.json')]:
        directory = ROOT / '.work/A08' / directory
        context_data = snapshot(directory / 'context.json')
        result_data = snapshot(directory / result_name)
        context, result = json.loads(context_data), json.loads(result_data)
        if (context.get('exitCode') != 0
                or context['sourceFingerprint'] != checkpoint['buildInputFingerprint']
                or context['agentSha256'] != checkpoint['agentArtifact']['sha256']
                or context['collectionConfiguration'] != checkpoint['collectionConfiguration']):
            raise SystemExit('Runtime proof is incomplete or identifies different inputs: ' + str(directory))
        if result_name == 'summary.json':
            if not result['passed'] or result['freshJvmRuns'] != 42:
                raise SystemExit('All required fresh cause pairs must pass')
        elif not result['stress']['passed'] or result['stress']['elapsedSeconds'] < 1200:
            raise SystemExit('Full 1200-second observer proof required')
        proofs.extend([(directory / 'context.json', context_data), (directory / result_name, result_data)])
    java_home = Path(os.environ['JAVA_HOME'])
    java = java_home / 'bin/java'
    if not java.is_file():
        raise SystemExit('Source scripts/cloud-env.sh before preparing')
    jdk_data = snapshot(java_home / 'release')
    helper_data = {name: snapshot(ROOT / '.work/A08' / name) for name in
                   ('prepare-reference-9.py', 'execute-prepared-reference-9.py', 'reference_owned_process.py')}
    INPUTS.mkdir()
    runtime = INPUTS / 'runtime'
    runtime.mkdir()
    copied = []
    for index, (artifact, data) in enumerate(artifacts):
        original = Path(artifact['path'])
        target = runtime / f'{index:02d}-{original.name}'
        write_verified(target, data, artifact['sha256'])
        copied.append({'original': str(original), 'runtimeCopy': str(target), 'sha256': artifact['sha256']})
    agent = INPUTS / 'threadlens-agent.jar'
    write_verified(agent, agent_data, checkpoint['agentArtifact']['sha256'])
    with tarfile.open(INPUTS / 'source.tar.gz', 'w:gz') as archive:
        for name, data in sorted(sources.items()):
            if Path(name).is_absolute() or '..' in Path(name).parts:
                raise RuntimeError('Invalid certified archive name: ' + name)
            info = tarfile.TarInfo(name)
            info.size, info.mode, info.mtime = len(data), 0o644, 0
            archive.addfile(info, io.BytesIO(data))
    # Reopen and check each member against the initial certificate, including exact entry set.
    with tarfile.open(INPUTS / 'source.tar.gz', 'r:gz') as archive:
        members = archive.getmembers()
        if len(members) != len(sources) or {m.name for m in members} != set(sources):
            raise RuntimeError('Archive entry set differs from certified source')
        for member in members:
            if not member.isfile() or digest(archive.extractfile(member).read()) != checkpoint['sourceFiles'][member.name]:
                raise RuntimeError('Archive member identity mismatch: ' + member.name)
    write_verified(INPUTS / 'functional-certificate.json', checkpoint_bytes, EXPECTED_CHECKPOINT_SHA256)
    proof_copies = []
    for index, (original, data) in enumerate(proofs):
        target = INPUTS / f'proof-{index}-{original.name}'
        expected = digest(data)
        write_verified(target, data, expected)
        proof_copies.append({'original': str(original), 'copy': str(target), 'sha256': expected})
    # Detect intervening changes before publishing a ready manifest. Copied bytes stay tied
    # to the original certificate even if an input changes during the final comparison.
    snapshot(CHECKPOINT, EXPECTED_CHECKPOINT_SHA256)
    for name, expected in checkpoint['sourceFiles'].items():
        snapshot(ROOT / name, expected)
    for artifact, data in artifacts:
        snapshot(artifact['path'], digest(data))
    snapshot(checkpoint['agentArtifact']['path'], digest(agent_data))
    for original, data in proofs:
        snapshot(original, digest(data))
    snapshot(java_home / 'release', digest(jdk_data))
    for name, data in helper_data.items():
        snapshot(ROOT / '.work/A08' / name, digest(data))
    revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    command = [str(java), '-Xmx512m', '-XX:ActiveProcessorCount=4',
               '-Dthreadlens.sourceFingerprint=' + checkpoint['buildInputFingerprint'],
               '-Dthreadlens.sourceRevision=' + revision, '-cp',
               ':'.join(a['runtimeCopy'] for a in copied),
               'io.github.cheshmakzanoon.portfolio.threadlens.cli.ThreadLensCli',
               'benchmark', str(OUTPUT), str(agent)]
    manifest = {'schemaVersion': 2, 'preparedUtc': datetime.now(timezone.utc).isoformat(),
                'sourceRevision': revision, 'buildInputFingerprint': checkpoint['buildInputFingerprint'],
                'exclusiveHostReservation': 'PENDING explicit coordinator quiet-host reservation',
                'collectionConfiguration': checkpoint['collectionConfiguration'],
                'eventSettings': checkpoint['eventSettings'], 'sourceFiles': checkpoint['sourceFiles'],
                'command': command, 'runtimeArtifacts': copied,
                'agentSha256': checkpoint['agentArtifact']['sha256'],
                'functionalCertificateSha256': EXPECTED_CHECKPOINT_SHA256,
                'validationProofs': proof_copies,
                'sourceArchiveSha256': digest((INPUTS / 'source.tar.gz').read_bytes()),
                'jdkReleaseSha256': digest(jdk_data),
                'executionHelpers': {name: digest(data) for name, data in helper_data.items()},
                'protocol': {'workloads': ['cpu', 'allocation', 'contention', 'virtual'],
                             'randomSeed': 42908, 'pairedBlocksPerWorkload': 7,
                             'modes': ['baseline', 'jfr', 'instrumented'], 'warmupSeconds': 30,
                             'measurementSeconds': 60, 'freshJvmRuns': 84, 'fixedObservationSeconds': 7560}}
    with (INPUTS / 'manifest.json').open('x') as output:
        output.write(json.dumps(manifest, indent=2) + '\n')
    print('Prepared immutable inputs; no benchmark has started:', INPUTS)


if __name__ == '__main__':
    main()
