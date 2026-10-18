#!/usr/bin/env python3
"""Require fresh, nonempty targeted mutation evidence and report every outcome."""
from pathlib import Path
from collections import Counter
import os
import sys
import xml.etree.ElementTree as ET

root = Path(sys.argv[1])
started = int(os.environ.get('PORTFOLIO_TEST_RUN_STARTED', '0'))
reports = [p for p in root.glob('**/target/pit-reports/**/mutations.xml') if p.stat().st_mtime >= started]
counts = Counter()
for report in reports:
    for mutation in ET.parse(report).getroot().iter('mutation'):
        counts[mutation.get('status', 'UNKNOWN')] += 1
valid = sum(counts.values()) - counts['NON_VIABLE']
rate = 100 * counts['KILLED'] / valid if valid else 0
print({'reports': len(reports), 'outcomes': dict(counts), 'killedPercent': rate})
if not valid or rate < 70 or any(counts[x] for x in ['RUN_ERROR', 'MEMORY_ERROR', 'UNKNOWN']):
    print('Mutation evidence missing, below70% killed, or contains unexplained runner errors.', file=sys.stderr)
    sys.exit(1)
