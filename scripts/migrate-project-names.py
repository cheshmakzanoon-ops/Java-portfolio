#!/usr/bin/env python3
"""One-time source-wide rename of the ten independently maintained Java projects."""
from __future__ import annotations

import os
from pathlib import Path
import re
import subprocess

RENAMES = (
    ('Clear', 'Ledger', 'Daybook'),
    ('Durable', 'Flow', 'Handoff'),
    ('Quorum', 'KV', 'Majority'),
    ('Pulse', 'Stream', 'LateArrival'),
    ('Tenant', 'Shield', 'Gatehouse'),
    ('Match', 'Core', 'ThePit'),
    ('Index', 'Forge', 'Stacks'),
    ('Thread', 'Lens', 'Backtrace'),
    ('Byte', 'Guard', 'Faultline'),
    ('Route', 'Weaver', 'Switchback'),
)
PATTERNS = tuple(
    (re.compile(re.escape(first) + r'([_\- ]?)' + re.escape(second), re.IGNORECASE), target)
    for first, second, target in RENAMES
)
OLD = re.compile('|'.join(p.pattern for p, _ in PATTERNS), re.IGNORECASE)
SELF = Path('scripts/migrate-project-names.py')
WORKFLOW = Path('.github/workflows/one-time-project-rename.yml')


def rename(text: str) -> str:
    for pattern, target in PATTERNS:
        words = ['The', 'Pit'] if target == 'ThePit' else (
            ['Late', 'Arrival'] if target == 'LateArrival' else [target]
        )

        def replacement(m: re.Match[str]) -> str:
            matched = m.group(0)
            separator = m.group(1)
            result = separator.join(words)
            if matched.isupper():
                return result.upper()
            if matched.islower():
                return result.lower()
            if matched[0].islower():
                return result[0].lower() + result[1:]
            return result

        text = pattern.sub(replacement, text)
    return text


def readable_markdown(text: str) -> str:
    """Use human-facing spacing without changing code fences or inline API identifiers."""
    lines = []
    fenced = False
    for line in text.splitlines(keepends=True):
        stripped = line.lstrip()
        if stripped.startswith(chr(96) * 3) or stripped.startswith('~~~'):
            fenced = not fenced
            lines.append(line)
            continue
        if not fenced:
            parts = line.split(chr(96))
            for i in range(0, len(parts), 2):
                parts[i] = re.sub(r'\bThePit\b', 'The Pit', parts[i])
                parts[i] = re.sub(r'\bLateArrival\b', 'Late Arrival', parts[i])
            line = chr(96).join(parts)
        lines.append(line)
    return ''.join(lines)


def main() -> None:
    tracked = [Path(os.fsdecode(p)) for p in subprocess.check_output(['git', 'ls-files', '-z']).split(b'\0') if p]
    work = {}
    target_paths = {}
    for old in tracked:
        if old in (SELF, WORKFLOW):
            continue
        new = Path(rename(old.as_posix()))
        if new in target_paths and target_paths[new] != old:
            raise RuntimeError(f'Rename collision: {old} and {target_paths[new]} -> {new}')
        target_paths[new] = old
        blob = old.read_bytes() if not old.is_symlink() else os.readlink(old).encode('utf-8')
        try:
            original = blob.decode('utf-8')
        except UnicodeDecodeError:
            if OLD.search(blob.decode('latin1')):
                raise RuntimeError(f'Old branding in non-UTF-8 tracked file: {old}')
            updated = blob
        else:
            changed = rename(original)
            if old.suffix.lower() == '.md':
                changed = readable_markdown(changed)
            elif old.suffix.lower() in ('.yaml', '.yml'):
                changed = re.sub(r'(?m)^(\s*(?:-\s*)?name:\s*)(.+)$',
                                 lambda m: m.group(1) + m.group(2).replace('ThePit', 'The Pit').replace('LateArrival', 'Late Arrival'),
                                 changed)
            updated = changed.encode('utf-8')
        work[old] = (new, blob, updated)

    for old, (new, original, updated) in work.items():
        if new != old:
            new.parent.mkdir(parents=True, exist_ok=True)
            if old.is_symlink():
                new.symlink_to(updated.decode('utf-8'))
                old.unlink()
            else:
                new.write_bytes(updated)
                new.chmod(old.stat().st_mode)
                old.unlink()
        elif original != updated:
            if old.is_symlink():
                old.unlink()
                old.symlink_to(updated.decode('utf-8'))
            else:
                old.write_bytes(updated)

    SELF.unlink(missing_ok=True)
    WORKFLOW.unlink(missing_ok=True)
    leftovers = []
    for p in Path('.').rglob('*'):
        if '.git' in p.parts or not p.is_file():
            continue
        if OLD.search(p.as_posix()):
            leftovers.append(f'path: {p}')
        data = p.read_bytes()
        try:
            found = OLD.search(data.decode('utf-8'))
        except UnicodeDecodeError:
            found = OLD.search(data.decode('latin1'))
        if found:
            leftovers.append(f'content: {p}: {found.group(0)}')
    if leftovers:
        raise RuntimeError('Incomplete rename:\n' + '\n'.join(leftovers[:100]))
    renamed = sum(old != item[0] for old, item in work.items())
    edited = sum(blob != updated for _, blob, updated in work.values())
    print(f'Migrated {len(work)} files: {renamed} file paths and {edited} file bodies changed; no legacy names remain.')


if __name__ == '__main__':
    main()
