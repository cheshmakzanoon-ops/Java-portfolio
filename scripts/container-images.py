#!/usr/bin/env python3
"""Validate the committed image inventory and emit exact scan targets, without pulls."""
from pathlib import Path
import argparse
import json
import os
import re
import sys

PIN = re.compile(r'([A-Za-z0-9._:/-]+)@sha256:([0-9a-f]{64})\Z')


def canonical(reference):
    match = PIN.fullmatch(reference)
    if not match:
        raise ValueError('Container image must include its exact sha256 digest: ' + reference)
    repository, digest = match.groups()
    if ':' in repository.rsplit('/', 1)[-1]:
        repository = repository.rsplit(':', 1)[0]
    return repository + '@sha256:' + digest


def inspect(root):
    inventory = json.loads((root / 'docs/execution/container-images.json').read_text())
    declared = {canonical(reference) for image in inventory['images']
                for reference in image['repoDigests']}
    observed = set()
    wrapper = (root / 'mvnw').read_text() if (root / 'mvnw').is_file() else ''
    helpers = re.findall(r'TESTCONTAINERS_RYUK_CONTAINER_IMAGE:=([^}]+)', wrapper)
    observed.update(canonical(image) for image in helpers)
    windows = root / 'mvnw.cmd'
    if windows.is_file():
        windows_helpers = re.findall(r'SET "TESTCONTAINERS_RYUK_CONTAINER_IMAGE=([^"\r\n]+)"', windows.read_text())
        if {canonical(image) for image in windows_helpers} != {canonical(image) for image in helpers}:
            raise ValueError('Maven wrapper cleanup-helper image defaults differ across platforms')
    sources = []
    for directory, dirs, names in os.walk(root / 'projects'):
        dirs[:] = sorted(name for name in dirs if name not in {'target', '.runtime', '.work', '.git'})
        sources.extend(Path(directory) / name for name in names if Path(name).suffix in {'.yaml', '.yml', '.java'})
    for source in sorted(sources):
        references = []
        if source.suffix in ('.yaml', '.yml'):
            for line in source.read_text().splitlines():
                line = line.split('#', 1)[0]
                if not re.search(r'(?<!\w)(?:image|[\"\x27]image[\"\x27])\s*:', line):
                    continue
                match = re.fullmatch(r'\s*(?:image|[\"\x27]image[\"\x27])\s*:\s*(?:"([^\"]+)"|\x27([^\x27]+)\x27|([^\s{}\[\],]+))\s*', line)
                if not match:
                    raise ValueError(str(source.relative_to(root)) + ': image syntax must be a literal block-style image key')
                references.append(next(value for value in match.groups() if value is not None))
        elif source.suffix == '.java' and '/src/test/' in source.as_posix():
            text = source.read_text()
            calls = re.findall(r'DockerImageName\.parse\s*\(', text)
            references = re.findall(r'DockerImageName\.parse\s*\(\s*"([^"]+)"\s*\)', text)
            if len(calls) != len(references):
                raise ValueError(str(source.relative_to(root)) + ': unresolved DockerImageName.parse image expression')
            constructor = r'new\s+(?:[\w.]+\.)?(?:PostgreSQLContainer|KafkaContainer|GenericContainer)(?:<[^>]*>)?\s*\('
            for match in re.finditer(constructor, text):
                argument = text[match.end():].lstrip()
                literal = re.match(r'"([^\"]+)"\s*\)', argument)
                if literal:
                    references.append(literal.group(1))
                elif not re.match(r'(?:org\.testcontainers\.utility\.)?DockerImageName\.parse\s*\(', argument):
                    raise ValueError(str(source.relative_to(root)) + ': unresolved Testcontainers image constructor')
        for reference in references:
            try:
                observed.add(canonical(reference))
            except ValueError as error:
                raise ValueError(str(source.relative_to(root)) + ': ' + str(error)) from error
    if not observed or observed != declared:
        raise ValueError('Image inventory drift: undeclared=' + str(sorted(observed - declared))
                         + ', unused=' + str(sorted(declared - observed)))
    return {'include': [{'id': 'image-%02d' % index, 'image': image}
                        for index, image in enumerate(sorted(observed), 1)]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--github-output', type=Path)
    args = parser.parse_args()
    try:
        matrix = inspect(Path(__file__).resolve().parents[1])
    except (ValueError, KeyError) as error:
        print(str(error), file=sys.stderr)
        return 1
    encoded = json.dumps(matrix, separators=(',', ':'))
    if args.github_output:
        with args.github_output.open('a') as output:
            output.write('matrix=' + encoded + '\n')
    print(encoded)
    return 0


if __name__ == '__main__':
    sys.exit(main())
