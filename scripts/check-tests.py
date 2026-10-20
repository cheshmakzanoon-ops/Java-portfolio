#!/usr/bin/env python3
"""Require current, complete test evidence from every selected reactor module."""
from pathlib import Path
import json
import sys

from maven_evidence import COUNT_KEYS, EvidenceError, fresh_reports, reactor_modules, run_started, test_counts


def inspect(root, started, integration=False):
    counts = dict.fromkeys(COUNT_KEYS, 0)
    failures, modules = [], []
    integration_tests = 0
    for module in reactor_modules(root, ('integration',) if integration else ()):
        observed = {}
        for kind in ('surefire', 'failsafe') if integration else ('surefire',):
            reports = fresh_reports(module, kind, started)
            subtotal = dict.fromkeys(COUNT_KEYS, 0)
            for report in reports:
                try:
                    suite = test_counts(report)
                except EvidenceError as error:
                    failures.append(str(error))
                    continue
                for key, value in suite.items():
                    subtotal[key] += value
                    counts[key] += value
            if module.has_tests(integration=kind == 'failsafe') and not subtotal['tests']:
                failures.append(str(module.directory) + ': no current ' + kind + ' tests executed')
            if subtotal['failures'] or subtotal['errors'] or subtotal['skipped']:
                failures.append(str(module.directory) + ': ' + kind + ' contains failed or skipped tests')
            if reports:
                observed[kind] = subtotal
            if kind == 'failsafe':
                integration_tests += subtotal['tests'] - subtotal['skipped']
        if observed:
            modules.append({'module': str(module.directory.relative_to(Path(root).resolve())), 'counts': observed})
    if not counts['tests']:
        failures.append('Selected reactor executed no tests')
    if integration and not integration_tests:
        failures.append('Integration profile executed no integration tests')
    return {'counts': counts, 'modules': modules, 'failures': failures}


def main():
    if len(sys.argv) not in (2, 3) or len(sys.argv) == 3 and sys.argv[2] not in ('verify', 'integration', 'mutation'):
        raise EvidenceError('Expected a project directory and verify, integration, or mutation')
    result = inspect(Path(sys.argv[1]).resolve(), run_started(), len(sys.argv) == 3 and sys.argv[2] == 'integration')
    print(json.dumps(result, indent=2))
    return bool(result['failures'])


if __name__ == '__main__':
    try:
        sys.exit(main())
    except EvidenceError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
