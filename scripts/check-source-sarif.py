#!/usr/bin/env python3
"""Fail closed on incomplete, malformed, or policy-violating CodeQL SARIF.

This is an evidence *gate*, not a finding suppressor. A suppressed result still
counts; absent scan coverage never counts as zero findings. Supported SARIF is
version 2.1.0 and the expected producer is GitHub CodeQL.
"""
import json
import math
from pathlib import Path
import sys


ALLOWED_LEVELS = frozenset(('none', 'note', 'warning', 'error'))


def _object(value):
    return value if isinstance(value, dict) else {}


def _array(value):
    return value if isinstance(value, list) else None


def _level(value):
    return value if isinstance(value, str) and value in ALLOWED_LEVELS else None


def _score(rule, name, rule_id, errors):
    properties = _object(rule.get('properties'))
    severity = properties.get('security-severity')
    if severity is None:
        return None
    try:
        if isinstance(severity, bool):
            raise ValueError('boolean security severity')
        parsed = float(severity)
        if not math.isfinite(parsed) or not 0 <= parsed <= 10:
            raise ValueError('outside 0..10')
        return parsed
    except (TypeError, ValueError, OverflowError):
        errors.append(name + ': invalid security severity for ' + rule_id)
        return None


def _rules(run, name, errors):
    tool = _object(run.get('tool'))
    driver = _object(tool.get('driver'))
    if driver.get('name') != 'CodeQL':
        errors.append(name + ': expected CodeQL as the SARIF producer')
    extensions = _array(tool.get('extensions', []))
    if extensions is None:
        errors.append(name + ': invalid tool extensions')
        extensions = []
    rules = {}
    for component in [driver, *extensions]:
        component = _object(component)
        declared = _array(component.get('rules', []))
        if declared is None:
            errors.append(name + ': malformed rule metadata')
            continue
        for rule in declared:
            if not isinstance(rule, dict) or not isinstance(rule.get('id'), str) or not rule['id']:
                errors.append(name + ': rule is missing a string identifier')
                continue
            identifier = rule['id']
            if identifier in rules:
                errors.append(name + ': duplicate rule metadata for ' + identifier)
            else:
                rules[identifier] = rule
    if not rules:
        errors.append(name + ': source scan contains no rule metadata')
    return rules


def inspect(documents):
    """Validate a sequence of (filename, parsed-document) pairs.

    Every malformed field is a failure, never a silent zero-finding scan. Counts
    describe what was inspected, not whether an ignored result is safe.
    """
    failures = []
    runs = findings = 0
    if not documents:
        failures.append('Source scan did not produce any SARIF documents')
    for name, document in documents:
        if not isinstance(document, dict) or document.get('version') != '2.1.0':
            failures.append(name + ': expected SARIF 2.1.0 document')
            continue
        declared = _array(document.get('runs'))
        if not declared:
            failures.append(name + ': no SARIF 2.1.0 runs')
            continue
        for run in declared:
            runs += 1
            if not isinstance(run, dict):
                failures.append(name + ': malformed SARIF run')
                continue
            rules = _rules(run, name, failures)
            invocations = _array(run.get('invocations', []))
            if invocations is None:
                failures.append(name + ': malformed scanner invocations')
            else:
                for invocation in invocations:
                    if not isinstance(invocation, dict) or invocation.get('executionSuccessful') is False:
                        failures.append(name + ': scanner invocation failed or malformed')
            results = _array(run.get('results', []))
            if results is None:
                failures.append(name + ': malformed findings list')
                continue
            for result in results:
                findings += 1
                if not isinstance(result, dict):
                    failures.append(name + ': malformed finding')
                    continue
                rule_id = result.get('ruleId')
                if not isinstance(rule_id, str) or rule_id not in rules:
                    failures.append(name + ': finding lacks resolvable rule metadata: ' + str(rule_id))
                    continue
                rule = rules[rule_id]
                properties = _object(rule.get('properties'))
                if 'level' in result and _level(result['level']) is None:
                    failures.append(name + ': invalid finding level for ' + rule_id)
                severity_present = 'security-severity' in properties
                score = _score(rule, name, rule_id, failures)
                if score is not None and score >= 7:
                    failures.append(name + ': high/critical source finding: ' + rule_id)
                if not severity_present:
                    default = _object(rule.get('defaultConfiguration')).get('level', 'warning')
                    level = result.get('level', default)
                    if _level(level) is None:
                        failures.append(name + ': invalid finding level for ' + rule_id)
                    elif level == 'error':
                        failures.append(name + ': source error without a security score: ' + rule_id)
    if not runs:
        failures.append('Source scan did not produce any SARIF run')
    return {
        'runs': runs,
        'findings': findings,
        'failures': failures,
        'policy': 'CodeQL SARIF2.1.0; security-severity>=7 or unscored error; no suppressions',
    }


def main():
    if len(sys.argv) != 2:
        raise SystemExit('Expected the actual CodeQL SARIF output directory')
    directory = Path(sys.argv[1])
    if not directory.is_dir():
        raise SystemExit('Missing CodeQL SARIF output directory')
    paths = sorted(directory.glob('*.sarif'))
    documents = []
    failures = []
    for path in paths:
        try:
            if path.is_symlink() or not path.is_file():
                raise ValueError('SARIF artifact must be a regular file')
            documents.append((str(path), json.loads(path.read_text(encoding='utf-8'))))
        except (OSError, UnicodeError, ValueError) as error:
            failures.append(str(path) + ': invalid SARIF artifact: ' + str(error))
    result = inspect(documents)
    result['failures'] = failures + result['failures']
    print(json.dumps(result, indent=2))
    return bool(result['failures'])


if __name__ == '__main__':
    sys.exit(main())
