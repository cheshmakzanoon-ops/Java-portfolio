"""Reject historical or altered evidence as certification of a current release."""
import hashlib
import json
from pathlib import Path
import subprocess


def source_fingerprint(root):
    """Include source, tests, config and release contracts; exclude evidence itself."""
    output = subprocess.check_output(
        ['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=root)
    digest = hashlib.sha256()
    for name in sorted(set(output.decode().split('\0')) - {''}):
        path = Path(name)
        if ('evidence' in path.parts or name.startswith('docs/execution/')
                or name == 'docs/PRODUCTION_READINESS.md'):
            continue
        target = root / path
        if not target.is_file() or not target.resolve().is_relative_to(root.resolve()):
            raise ValueError('Release input is missing or escapes checkout: ' + name)
        digest.update(name.encode() + b'\0')
        digest.update(hashlib.sha256(target.read_bytes()).hexdigest().encode() + b'\n')
    return digest.hexdigest()


def inspect(root, required, receipt_path=None):
    receipt_path = receipt_path or root / 'docs/execution/release-current.json'
    try:
        receipt = json.loads(receipt_path.read_text())
        if receipt.get('schemaVersion') != 1:
            raise ValueError('unsupported schemaVersion')
        if receipt.get('sourceFingerprint') != source_fingerprint(root):
            raise ValueError('source fingerprint differs from current checkout')
        criteria = receipt['criteria']
        if not isinstance(criteria, dict) or set(criteria) != set(required):
            raise ValueError('receipt must enumerate exactly the mandatory criteria')
    except (OSError, ValueError, KeyError, TypeError, subprocess.CalledProcessError) as error:
        return ['Current release evidence unavailable or stale: ' + str(error)]
    errors = []
    for key in sorted(required):
        row = criteria[key]
        if not isinstance(row, dict) or row.get('status') != 'PASS':
            errors.append(key + ': current-source evidence is not PASS')
            continue
        evidence = row.get('evidence')
        if not isinstance(evidence, dict) or not evidence:
            errors.append(key + ': current PASS requires hashed evidence files')
            continue
        for name, expected in evidence.items():
            target = root / name
            if (Path(name).is_absolute() or not target.resolve().is_relative_to(root.resolve())
                    or target.resolve() == receipt_path.resolve()):
                errors.append(key + ': invalid evidence path: ' + name)
                continue
            try:
                if hashlib.sha256(target.read_bytes()).hexdigest() != expected:
                    errors.append(key + ': evidence hash mismatch: ' + name)
            except OSError as error:
                errors.append(key + ': missing evidence: ' + str(error))
    return errors
