#!/usr/bin/env python3
"""Read-only Linux teardown assertion for a selected catalog project."""
from pathlib import Path
import json
import os
import socket
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
catalog = json.loads((root / 'catalog/projects.yaml').read_text())['projects']
slug = sys.argv[1] if len(sys.argv) == 2 else ''
project = next((p for p in catalog if p['slug'] == slug), None)
if not project:
    raise SystemExit('Expected a catalog project slug')
if not Path('/proc').is_dir():
    raise SystemExit('Independent process ownership check requires Linux /proc')
failures = []
prefixes = (str(root / project['path']) + '/', str(root / '.work' / project['id']) + '/')
for process in Path('/proc').iterdir():
    if not process.name.isdigit():
        continue
    try:
        if (process / 'comm').read_text().strip() != 'java':
            continue
        stat = (process / 'stat').read_text()
        if stat[stat.rindex(')') + 2:].split()[0] == 'Z':
            continue
        argv = (process / 'cmdline').read_bytes().decode(errors='replace').split('\0')
        if any(prefix in argument for argument in argv for prefix in prefixes):
            failures.append('owned Java process remains: PID ' + process.name)
    except (FileNotFoundError, ProcessLookupError, PermissionError):
        continue
namespace = os.environ.get('COMPOSE_PROJECT_NAME', 'java-portfolio-' + slug)
try:
    containers = subprocess.check_output(['docker', 'ps', '-q', '--filter',
                                         'label=com.docker.compose.project=' + namespace], text=True, timeout=10)
    if containers.strip():
        failures.append('running project containers remain in ' + namespace)
except (subprocess.TimeoutExpired, subprocess.CalledProcessError, OSError):
    failures.append('could not verify container teardown within10seconds for ' + namespace)
for port in project['ports']:
    for family, address in ((socket.AF_INET, ('127.0.0.1', port)),
                            (socket.AF_INET6, ('::1', port))):
        with socket.socket(family, socket.SOCK_STREAM) as probe:
            probe.settimeout(0.2)
            try:
                connected = probe.connect_ex(address) == 0
            except OSError:
                connected = False
            if connected:
                failures.append('catalog port still accepts connections: ' + str(port))
                break
for failure in failures:
    print(failure, file=sys.stderr)
print(json.dumps({'project': slug, 'composeNamespace': namespace,
                  'assertion': 'no project Java, running Compose containers or catalog listeners',
                  'passed': not failures}))
sys.exit(bool(failures))
