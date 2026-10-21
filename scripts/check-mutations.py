#!/usr/bin/env python3
"""Require fresh mutation evidence and the kill threshold for every selected module."""
from collections import Counter
from pathlib import Path
import json
import sys
import xml.etree.ElementTree as ET

from maven_evidence import EvidenceError, fresh_reports, reactor_modules, report_xml, run_started

KNOWN = {'KILLED', 'SURVIVED', 'NO_COVERAGE', 'TIMED_OUT', 'NON_VIABLE', 'RUN_ERROR', 'MEMORY_ERROR'}


def inspect(root, started):
    total = Counter()
    failures, modules = [], []
    for module in reactor_modules(root, ('mutation',)):
        reports = fresh_reports(module, 'pit', started)
        if not module.mutation_selected() and not reports:
            continue
        outcomes = Counter()
        for report in reports:
            try:
                document = report_xml(report)
                if document.tag != 'mutations':
                    raise ValueError('expected mutations')
                outcomes.update(mutation.get('status', 'UNKNOWN') for mutation in document.iter('mutation'))
            except (OSError, ET.ParseError, ValueError) as error:
                failures.append(str(report) + ': invalid mutation report: ' + str(error))
        valid = sum(outcomes.values()) - outcomes['NON_VIABLE']
        rate = 100 * outcomes['KILLED'] / valid if valid else 0
        threshold = module.mutation_threshold()
        if not valid or rate < threshold or set(outcomes) - KNOWN or outcomes['RUN_ERROR'] or outcomes['MEMORY_ERROR']:
            failures.append(str(module.directory) + ': missing, below ' + str(threshold) + '% killed, or incomplete mutation evidence')
        modules.append({'module': str(module.directory.relative_to(Path(root).resolve())),
                        'reports': len(reports), 'outcomes': dict(outcomes), 'killedPercent': rate,
                        'requiredKilledPercent': threshold})
        total.update(outcomes)
    if not modules:
        failures.append('Selected reactor produced no mutation evidence')
    return {'modules': modules, 'outcomes': dict(total), 'failures': failures}


def main():
    if len(sys.argv) != 2:
        raise EvidenceError('Expected the selected project directory')
    result = inspect(Path(sys.argv[1]).resolve(), run_started())
    print(json.dumps(result, indent=2))
    return bool(result['failures'])


if __name__ == '__main__':
    try:
        sys.exit(main())
    except EvidenceError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
