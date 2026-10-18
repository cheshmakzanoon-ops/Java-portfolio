#!/usr/bin/env python3
"""Verify the selected completed Maven run actually discovered meaningful tests."""
from pathlib import Path
import sys
import os
import xml.etree.ElementTree as ET

path = Path(sys.argv[1]).resolve()
reports = list(path.glob('**/target/surefire-reports/TEST-*.xml'))
if len(sys.argv) > 2 and sys.argv[2] == 'integration':
    reports += list(path.glob('**/target/failsafe-reports/TEST-*.xml'))
try:
    started = int(os.environ['PORTFOLIO_TEST_RUN_STARTED'])
    if started <= 0:
        raise ValueError('nonpositive run cutoff')
except (KeyError, ValueError):
    print('Set PORTFOLIO_TEST_RUN_STARTED to the actual run start before Maven.', file=sys.stderr)
    sys.exit(1)
reports = [p for p in reports if p.stat().st_mtime >= started]
counts = dict(tests=0, failures=0, errors=0, skipped=0)
for report in reports:
    suite = ET.parse(report).getroot()
    for key in counts:
        counts[key] += int(suite.get(key, '0'))
print(counts)
if counts['tests'] - counts['skipped'] <= 0 or counts['failures'] or counts['errors'] or counts['skipped']:
    print('Required project verification has no executed tests, failures, or unexplained skips.', file=sys.stderr)
    sys.exit(1)
if len(sys.argv) > 2 and sys.argv[2] == 'integration':
    integration = [p for p in path.glob('**/target/failsafe-reports/TEST-*.xml') if p.stat().st_mtime >= started]
    if not any(int(ET.parse(p).getroot().get('tests', '0')) > int(ET.parse(p).getroot().get('skipped', '0')) for p in integration):
        print('Integration profile executed no integration tests.', file=sys.stderr)
        sys.exit(1)
