#!/usr/bin/env python3
"""Require source SARIF and fail on security severity >=7 or unscored errors.

No implicit suppression is applied. Any future justified exception must be
reviewed explicitly and tied to the exact rule, location and fixture scope.
"""
from pathlib import Path
import json
import math
import sys


def inspect(documents):
    failures = []
    runs = findings = 0
    for name, document in documents:
        if document.get('version') != '2.1.0' or not document.get('runs'):
            failures.append(name + ': no SARIF2.1.0 runs')
            continue
        for run in document['runs']:
            runs += 1
            tool = run.get('tool', {})
            rules = {rule['id']: rule for component in [tool.get('driver', {}), *tool.get('extensions', [])]
                     for rule in component.get('rules', [])}
            if not rules:
                failures.append(name + ': source scan contains no rule metadata')
            if any(invocation.get('executionSuccessful') is False for invocation in run.get('invocations', [])):
                failures.append(name + ': scanner invocation was unsuccessful')
            for result in run.get('results', []):
                findings += 1
                rule_id = result.get('ruleId', '')
                rule = rules.get(rule_id)
                if rule is None:
                    failures.append(name + ': finding lacks resolvable rule metadata: ' + rule_id)
                    continue
                severity = rule.get('properties', {}).get('security-severity')
                if severity is not None:
                    try:
                        score = float(severity)
                        if not math.isfinite(score) or not 0 <= score <= 10:
                            raise ValueError('invalid score')
                    except (TypeError, ValueError):
                        failures.append(name + ': invalid security severity for ' + rule_id)
                        continue
                    if score >= 7:
                        failures.append(name + ': high/critical source finding: ' + rule_id)
                elif result.get('level', rule.get('defaultConfiguration', {}).get('level')) == 'error':
                    failures.append(name + ': source error without a security score: ' + rule_id)
    if not runs:
        failures.append('Source scan did not produce any SARIF run')
    return {'runs': runs, 'findings': findings, 'failures': failures,
            'policy': 'security-severity>=7 or unscored error; no implicit suppressions'}


def main():
    if len(sys.argv) != 2:
        raise SystemExit('Expected the actual CodeQL SARIF output directory')
    paths = sorted(Path(sys.argv[1]).glob('*.sarif'))
    result = inspect([(str(path), json.loads(path.read_text())) for path in paths])
    print(json.dumps(result, indent=2))
    return bool(result['failures'])


if __name__ == '__main__':
    sys.exit(main())
