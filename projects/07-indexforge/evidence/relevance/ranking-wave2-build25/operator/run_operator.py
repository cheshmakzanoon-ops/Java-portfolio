#!/usr/bin/env python3
"""Local coordinator operator; preparation/check never parse actual corpus or judgments."""
from pathlib import Path
import argparse, ast, hashlib, json, os, subprocess, sys

REPO = Path(__file__).resolve().parents[6]
OP = Path(__file__).resolve().parent
WORK = REPO / '.work/A07/final-operator-build25-C1'
PROJECT = 'projects/07-indexforge/'
EVIDENCE = PROJECT + 'evidence/relevance/ranking-wave2-build25/'
ARTIFACT = '.work/A07/artifacts/wave2-build25-cli.jar'
JDK = Path('/workspace/.tools/jdk-25/bin')
JAVA_CLASS = 'io.github.cheshmakzanoon.portfolio.indexforge.wave2operator.FinalWaveOperator'
SOURCE = 'c0ab80c6a5e14d5836b9e69813f18d16acda382a97686df2d020c45622a2e8d2'
JAR = 'e2d947c97a6b5b5a5fc6d6696d1ca56241eed11f9cfbb772b0d1880a6217dbc7'
C1 = 'f938b3fea0324fdbeac8bb781b42bec7f38f99af4f86cda5db8a30495bc98399'
BASELINE = '75a5f85ef95c35c0a1f77d1cc4a35b3cca8fe1a90eef9ad8664cb85656316c0f'


def need(condition, message='frozen operator invariant'):
    if not condition: raise ValueError(message)


def digest(path):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 536870912:
        raise ValueError('bounded regular input required: ' + str(path))
    h = hashlib.sha256()
    with path.open('rb') as stream:
        while block := stream.read(65536): h.update(block)
    return h.hexdigest()


def properties(path):
    out = {}
    if path.stat().st_size > 65536: raise ValueError('context quota')
    for line in path.read_text().splitlines():
        if not line: continue
        key, value = line.split('=', 1)
        if not key or key in out: raise ValueError('duplicate context key')
        out[key] = value
    return out


def write_properties(path, values):
    with path.open('x') as stream:
        for key, value in sorted(values.items()):
            value = str(value)
            if any(x in key for x in '=\n\r') or any(x in value for x in '\n\r'): raise ValueError('metadata syntax')
            stream.write(key + '=' + value + '\n')


def binary_hash(classes):
    files = []; seen = 0
    for base, dirs, names in os.walk(classes):
        for name in dirs + names:
            entry = Path(base) / name; seen += 1
            if seen > 512 or entry.is_symlink(): raise ValueError('operator origin quota/symlink')
        for name in names:
            file = Path(base) / name
            if name.endswith('.class'):
                if len(files) >= 128: raise ValueError('operator class quota')
                files.append(file)
    files.sort()
    if not files: raise ValueError('operator class quota')
    h = hashlib.sha256()
    for file in files:
        if file.is_symlink() or file.stat().st_size > 1048576: raise ValueError('operator class bound')
        h.update(file.relative_to(classes).as_posix().encode() + b'\0' + file.read_bytes())
    return h.hexdigest()


def binding_paths():
    paths = [PROJECT + 'evidence/certification-build25.json']
    paths += [EVIDENCE + 'attempt-1/' + name for name in [
        'validation/selection.properties', 'validation/per-query.tsv', 'validation/summary.txt',
        'validation/PRE-VALIDATION.properties', 'validation/family-identities.txt',
        'audit/identity.properties', 'audit/features.tsv', 'fit/identity.properties',
        'fit/report.txt', 'fit/selected-model.bin']]
    paths += [PROJECT + 'fixtures/ranking-wave2/' + name for name in [
        'PREREGISTRATION.md', 'FREEZE.sha256', 'protocol-freeze.json', 'folds.json', 'folds.tsv', 'comparator-reference.tsv']]
    paths += [PROJECT + 'fixtures/FREEZE.sha256', PROJECT + 'fixtures/REVIEW-APPROVED.md',
              EVIDENCE + 'verification.json', EVIDENCE + 'phase-identity-refusals.json']
    paths += [str((OP / name).relative_to(REPO)) for name in ['FinalWaveOperator.java', 'run_operator.py', 'PROTOCOL.md']]
    return paths


def static_check():
    need(digest(REPO / ARTIFACT) == JAR)
    certificate = json.loads((REPO / PROJECT / 'evidence/certification-build25.json').read_text())
    need(certificate['sourceFingerprint'] == SOURCE and certificate['cliJarSha256'] == JAR)
    need(certificate['testTotals'] == dict(tests=88, failures=0, errors=0, skipped=0))
    selected = properties(REPO / EVIDENCE / 'attempt-1/validation/selection.properties')
    need(all(selected[k] == v for k, v in dict(family='C1', strategy='SYNTAX_GATED', mode='SOURCE_AWARE', profile='CODE_HIGH', model='NONE').items()))
    families = properties(REPO / EVIDENCE / 'attempt-1/validation/family-identities.txt')
    need(families['C1'] == C1 and families['C0'] == BASELINE)
    return {name: digest(REPO / name) for name in binding_paths()}


def verify_context(path):
    p = properties(path)
    need(p['artifactSha256'] == digest(REPO / ARTIFACT) == JAR)
    need(p['repository'] == str(REPO) and p['sourceFingerprint'] == SOURCE)
    for i in range(int(p['bindingCount'])):
        relative = p[f'binding.{i}.relative']; location = REPO / relative
        need(not Path(relative).is_absolute() and location.resolve().is_relative_to(REPO))
        need(digest(location) == p[f'binding.{i}.sha256'])
    classes = REPO / p['operatorClassesRelative']
    need(binary_hash(classes) == p['operatorBinarySha256'])
    need(digest(OP / 'FinalWaveOperator.java') == p['operatorSourceSha256'])
    static_check()
    return p, classes


def java(classes, *args):
    # Artifact FIRST prevents external helper output from replacing original inference classes.
    return [str(JDK / 'java'), '--enable-native-access=ALL-UNNAMED', '-Xms128m', '-Xmx2g', '-cp',
            str(REPO / ARTIFACT) + os.pathsep + str(classes), JAVA_CLASS, *map(str, args)]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['static', 'prepare', 'check', 'execute'])
    parser.add_argument('--attempt', type=int, default=1)
    parser.add_argument('--context', type=Path)
    parser.add_argument('--approval', type=Path)
    parser.add_argument('--grant')
    args = parser.parse_args()
    if args.command == 'static':
        hashes = static_check()
        print(json.dumps({'result': 'STATIC_ONLY', 'javaExecuted': False, 'judgmentOrCorpusContentOpened': False,
                          'operatorSourceSha256': digest(OP / 'FinalWaveOperator.java'),
                          'launcherSha256': digest(Path(__file__)), 'protocolSha256': digest(OP / 'PROTOCOL.md'),
                          'bindingFiles': len(hashes)}, indent=2)); return
    if not args.grant: raise ValueError('explicit coordinator runtime window required; quiet holds do not expire automatically')
    if args.command == 'prepare':
        if not 1 <= args.attempt <= 99: raise ValueError('preparation attempt bound')
        hashes = static_check(); work = WORK / f'prepare-{args.attempt}'; work.mkdir(parents=True, exist_ok=False)
        classes = work / 'classes'; classes.mkdir()
        compile_command = [str(JDK / 'javac'), '--release', '25', '-encoding', 'UTF-8', '-Xlint:all',
                           '-cp', str(REPO / ARTIFACT), '-d', str(classes), str(OP / 'FinalWaveOperator.java')]
        with (work / 'compile.log').open('x') as log:
            subprocess.run(compile_command, cwd=REPO, stdout=log, stderr=subprocess.STDOUT, timeout=120, check=True)
        context = dict(wave='ranking-wave2-build25-C1-final', repository=str(REPO), sourceFingerprint=SOURCE,
                       artifactRelative=ARTIFACT, artifactSha256=JAR, candidateFingerprint=C1, baselineFingerprint=BASELINE,
                       candidateFamily='C1', strategy='SYNTAX_GATED', mode='SOURCE_AWARE', profile='CODE_HIGH', model='NONE',
                       outputRelative=EVIDENCE + 'final-attempt-1',
                       globalSealRelative=PROJECT + 'fixtures/ranking-wave2/FINAL-ONCE-build25-C1.properties',
                       dataRelative=PROJECT + '.runtime/ranking-wave2-build25-final-attempt1',
                       operatorSourceSha256=digest(OP / 'FinalWaveOperator.java'), operatorBinarySha256=binary_hash(classes),
                       operatorClassesRelative=str(classes.relative_to(REPO)),
                       selectionRelative=EVIDENCE + 'attempt-1/validation/selection.properties',
                       selectionSha256=hashes[EVIDENCE + 'attempt-1/validation/selection.properties'],
                       fixtureManifestSha256=hashes[PROJECT + 'fixtures/FREEZE.sha256'],
                       protocolSha256=digest(OP / 'PROTOCOL.md'), bindingCount=len(hashes))
        for i, (name, sha) in enumerate(sorted(hashes.items())):
            context[f'binding.{i}.relative'] = name; context[f'binding.{i}.sha256'] = sha
        path = work / 'context.properties'; write_properties(path, context)
        with (work / 'preparation-checks.log').open('x') as log:
            subprocess.run(java(classes, 'self-test', work / 'synthetic-self-test'), cwd=REPO, stdout=log, stderr=subprocess.STDOUT, timeout=60, check=True)
            subprocess.run(java(classes, 'check', path), cwd=REPO, stdout=log, stderr=subprocess.STDOUT, timeout=60, check=True)
        verify_context(path)
        print(json.dumps({'result': 'PREPARED_NOT_EXECUTED', 'context': str(path), 'contextSha256': digest(path),
                          'operatorBinarySha256': context['operatorBinarySha256'], 'realJudgmentsOpened': False}, indent=2)); return
    if args.context is None: raise ValueError('context required')
    p, classes = verify_context(args.context)
    if args.command == 'check':
        subprocess.run(java(classes, 'check', args.context), cwd=REPO, timeout=60, check=True); return
    if args.approval is None: raise ValueError('separate root approval file required')
    approval = properties(args.approval)
    need(approval['action'] == 'execute-once' and approval['wave'] == p['wave'] and approval['approvedBy'] == 'root')
    need(approval['contextSha256'] == digest(args.context))
    need(approval['operatorSourceSha256'] == p['operatorSourceSha256'] and approval['operatorBinarySha256'] == p['operatorBinarySha256'])
    output = REPO / p['outputRelative']
    if output.exists() or (REPO / p['globalSealRelative']).exists() or (REPO / p['dataRelative']).exists():
        raise ValueError('once-only context consumed or stale data/output present')
    log = args.context.parent / 'final-execution.log'
    with log.open('x') as stream:
        subprocess.run(java(classes, 'execute', args.context, args.approval), cwd=REPO, stdout=stream, stderr=subprocess.STDOUT, timeout=600, check=True)
    print('Final finished; preserve all outputs and inspect COMPLETED.properties; no rerun or diagnostic reselection.')


if __name__ == '__main__': main()
