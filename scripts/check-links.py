#!/usr/bin/env python3
"""Check local Markdown destinations and GitHub-style heading anchors, offline."""
from pathlib import Path
import html
import re
import subprocess
import sys
from urllib.parse import unquote, urlsplit


def prose(text):
    result = []
    fence = None
    for line in text.splitlines():
        marker = re.match(r'^\s{0,3}(`{3,}|~{3,})', line)
        if marker:
            token = marker.group(1)
            if fence is None:
                fence = token
            elif token[0] == fence[0] and len(token) >= len(fence):
                fence = None
            result.append('')
        else:
            result.append(line if fence is None else '')
    return '\n'.join(result)


def anchors(text):
    values = set()
    duplicates = {}
    visible = prose(text)
    lines = visible.splitlines()
    for index, line in enumerate(lines):
        match = re.match(r'^\s{0,3}#{1,6}\s+(.+?)(?:\s+#+\s*)?$', line)
        title = match.group(1) if match else None
        if title is None and index + 1 < len(lines) and re.fullmatch(r'\s{0,3}(?:=+|-+)\s*', lines[index + 1]) and line.strip():
            title = line.strip()
        if title is None:
            continue
        title = re.sub(r'\[([^]]+)\]\([^)]*\)', r'\1', title)
        title = html.unescape(re.sub(r'<[^>]+>', '', title))
        slug = re.sub(r'[^\w\- ]', '', title.lower()).replace(' ', '-')
        suffix = duplicates.get(slug, 0)
        candidate = slug if suffix == 0 else slug + '-' + str(suffix)
        while candidate in values:
            suffix += 1
            candidate = slug + '-' + str(suffix)
        duplicates[slug] = suffix + 1
        values.add(candidate)
    values.update(re.findall(r'<a\b[^>]*\b(?:id|name)=[\"\x27]([^\"\x27]+)', without_inline_code(visible), re.I))
    return values


def without_inline_code(text):
    return re.sub(r'(?<!`)(`+)(?!`)([\s\S]*?)(?<!`)\1(?!`)', lambda match: ' ' * len(match.group(0)), text)


def destinations(text):
    text = without_inline_code(prose(text))
    # The repository uses standard inline destinations and reference definitions.
    pattern = r'!?\[[^\]\n]*\]\(\s*(?:<([^>]+)>|([^\s)]+))(?:\s+[\"\x27][^\n]*?[\"\x27])?\s*\)'
    for match in re.finditer(pattern, text):
        yield match.group(1) or match.group(2)
    for match in re.finditer(r'^\s{0,3}\[[^]]+\]:\s*(?:<([^>]+)>|([^\s]+))', text, re.M):
        yield match.group(1) or match.group(2)


def inspect(root, files):
    failures = []
    cache = {}
    for source in files:
        text = source.read_text()
        for target in destinations(text):
            parsed = urlsplit(target)
            if parsed.scheme or parsed.netloc:
                continue
            path = unquote(parsed.path)
            destination = ((root / path.lstrip('/')) if path.startswith('/')
                           else (source.parent / path)) if path else source
            destination = destination.resolve()
            if not destination.is_relative_to(root.resolve()) or not destination.exists():
                failures.append(str(source.relative_to(root)) + ': missing local target ' + target)
                continue
            fragment = unquote(parsed.fragment)
            if fragment and destination.suffix.lower() == '.md' and destination.is_file():
                if destination not in cache:
                    cache[destination] = anchors(destination.read_text())
                if fragment not in cache[destination]:
                    failures.append(str(source.relative_to(root)) + ': missing heading anchor ' + target)
    return failures


def markdown_files(root):
    names = subprocess.check_output(
        ['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z', '--', '*.md'],
        cwd=root).decode().split('\0')
    return sorted({root / name for name in names if name and (root / name).is_file()})


def main():
    root = Path(__file__).resolve().parents[1]
    files = markdown_files(root)
    failures = inspect(root, files)
    for failure in failures:
        print(failure, file=sys.stderr)
    print(f'Markdown links: {len(files)} working-tree documents; {len(failures)} unresolved local targets/anchors')
    return bool(failures)


if __name__ == '__main__':
    sys.exit(main())
