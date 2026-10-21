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



DOCKERFILE = re.compile(r'(?:Dockerfile(?:\.[A-Za-z0-9_-]+)?|[A-Za-z0-9_.-]+\.dockerfile)\Z')
EXCLUDED_DIRECTORIES = {'target', '.runtime', '.work', '.git', 'node_modules', 'vendor'}


def dockerfile_images(source):
    """Inspect every FROM, including additional stages and aliases."""
    used = set()
    found = []
    content = source.read_text(encoding='utf-8')
    if '\\' + '\n' in content:
        raise ValueError(str(source) + ': multiline Dockerfiles are not supported')
    for line_number, raw in enumerate(content.splitlines(), 1):
        line = raw.partition('#')[0].strip()
        if not re.match(r'FROM(?:\s|$)', line, re.IGNORECASE):
            continue
        match = re.fullmatch(
            r'FROM\s+(?:--platform=\S+\s+)?(\S+)(?:\s+AS\s+([A-Za-z0-9_-]+))?',
            line, re.IGNORECASE)
        if match is None:
            raise ValueError(str(source) + ':' + str(line_number) + ': unresolved FROM')
        name, alias = match.groups()
        if name.lower() not in used and name.lower() != 'scratch':
            found.append(name)
        if alias is not None:
            if alias.lower() in used or alias.lower() == 'scratch':
                raise ValueError(str(source) + ':' + str(line_number) + ': duplicate stage')
            used.add(alias.lower())
    return found


def sources_to_scan(root):
    """Do not overlook images added outside projects/, especially in CI jobs."""
    for scope in ('projects', 'integration', '.github'):
        directory = root / scope
        if not directory.is_dir():
            continue
        for location, dirs, files in os.walk(directory):
            dirs[:] = sorted(d for d in dirs if d not in EXCLUDED_DIRECTORIES)
            for name in sorted(files):
                path = Path(location) / name
                if path.suffix in ('.yaml', '.yml', '.java') or DOCKERFILE.fullmatch(name):
                    yield path


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
    for source in sources_to_scan(root):
        references = []
        if source.suffix in ('.yaml', '.yml'):
            for line in source.read_text().splitlines():
                line = line.split('#', 1)[0]
                # GitHub supports both container: image and container: {image: image};
                # the latter is intentionally rejected here unless written in block form.
                if re.match(r'^\s*(?:container|["\x27]container["\x27])\s*:', line):
                    raw = line.split(':', 1)[1].strip()
                    if not raw:
                        continue
                    if raw.startswith(('{', '[', '
                    continue
                match = re.fullmatch(r'\s*(?:image|[\"\x27]image[\"\x27])\s*:\s*(?:"([^\"]+)"|\x27([^\x27]+)\x27|([^\s{}\[\],]+))\s*', line)
                if not match:
                    raise ValueError(str(source.relative_to(root)) + ': image syntax must be a literal block-style image key')
                references.append(next(value for value in match.groups() if value is not None))
        elif DOCKERFILE.fullmatch(source.name):
            references = dockerfile_images(source)
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
)) or any(c.isspace() for c in raw):
                        raise ValueError(str(source.relative_to(root)) + ': dynamic or inline CI container')
                    if raw.startswith(('"', "'")) and raw.endswith(raw[0]):
                        raw = raw[1:-1]
                    references.append(raw)
                    continue
                if re.match(r'^\s*uses\s*:\s*["\x27]?docker://', line):
                    docker = re.fullmatch(
                        r'\s*uses\s*:\s*(?:"docker://([^"]+)"|\x27docker://([^\x27]+)\x27|docker://(\S+))\s*',
                        line)
                    if not docker:
                        raise ValueError(str(source.relative_to(root)) + ': unresolvable Docker action')
                    references.append(next(value for value in docker.groups() if value is not None))
                    continue
                if not re.search(r'(?<!\w)(?:image|[\"\x27]image[\"\x27])\s*:', line):
                    continue
                match = re.fullmatch(r'\s*(?:image|[\"\x27]image[\"\x27])\s*:\s*(?:"([^\"]+)"|\x27([^\x27]+)\x27|([^\s{}\[\],]+))\s*', line)
                if not match:
                    raise ValueError(str(source.relative_to(root)) + ': image syntax must be a literal block-style image key')
                references.append(next(value for value in match.groups() if value is not None))
        elif DOCKERFILE.fullmatch(source.name):
            references = dockerfile_images(source)
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
