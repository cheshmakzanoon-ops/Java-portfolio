#!/usr/bin/env python3
"""Collect fresh reactor evidence without replacing historical certificates."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--integration', action='store_true')
    parser.add_argument('--mutation', action='store_true')
    parser.add_argument('--output', type=Path, help='new certificate path; defaults to stdout')
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    root = project.parents[1]

    def gate(script, *arguments):
        result = subprocess.run(['python3', str(root / 'scripts' / script), str(project), *arguments],
                                capture_output=True, text=True, check=False)
        if result.returncode:
            parser.exit(1, result.stdout + result.stderr)
        return json.loads(result.stdout)

    # Repository gates require PORTFOLIO_TEST_RUN_STARTED and reject stale or incomplete reports.
    tests = gate('check-tests.py', 'integration' if args.integration else 'verify')
    mutations = gate('check-mutations.py') if args.mutation else {'status': 'NOT_RUN'}
    inputs = [path for path in project.rglob('*')
              if path.is_file() and 'target' not in path.parts and 'evidence' not in path.parts
              and path.suffix in {'.java', '.xml', '.json', '.yaml', '.sh', '.py'}]
    inputs += [root / name for name in ('pom.xml', 'mvnw', 'scripts/cloud-env.sh',
                                       'scripts/with-build-slot.sh')]
    inputs += [path for path in (root / '.mvn').rglob('*') if path.is_file()]
    digest, hashes = hashlib.sha256(), {}
    for path in sorted(set(inputs)):
        name = path.relative_to(root).as_posix()
        content = path.read_bytes()
        hashes[name] = hashlib.sha256(content).hexdigest()
        digest.update(name.encode() + b'\0' + content + b'\0')
    result = {'schemaVersion': 2, 'project': 'backtrace',
              'baseRevision': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root,
                                                       text=True).strip(),
              'sourceFingerprint': digest.hexdigest(), 'sourceAndBuildSha256': hashes,
              'tests': tests, 'mutations': mutations,
              'notRun': ['full overhead remeasurement', 'long observer stress',
                         'Windows/macOS validation', 'clean-checkout/hosted release validation']}
    text = json.dumps(result, indent=2) + '\n'
    if args.output:
        with args.output.open('x') as output:
            output.write(text)
    else:
        print(text, end='')


if __name__ == '__main__':
    main()
