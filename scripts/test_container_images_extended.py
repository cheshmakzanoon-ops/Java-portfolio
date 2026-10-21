"""Image inventory must include immutable Dockerfile, CI and integration images."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

script = Path(__file__).with_name('container-images.py')
spec = importlib.util.spec_from_file_location('extended_images', script)
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)
PG = 'postgres@sha256:' + 'a' * 64
KAFKA = 'apache/kafka@sha256:' + 'b' * 64


class ImageCoverageTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.write('docs/execution/container-images.json',
                   json.dumps({'images': [{'repoDigests': [PG]}]}))
        self.write('projects/01-clearledger/infra/compose.yaml',
                   'services:\n  db:\n    image: postgres@sha256:' + 'a' * 64 + '\n')

    def write(self, name, content):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        return path

    def inspect(self):
        return {x['image'] for x in gate.inspect(self.root)['include']}

    def declare(self, *images):
        self.write('docs/execution/container-images.json',
                   json.dumps({'images': [{'repoDigests': [img]} for img in images]}))

    def test_existing_image_is_preserved(self):
        self.assertEqual({PG}, self.inspect())

    def test_dockerfile_multistage_alias_is_not_a_second_image(self):
        self.write('projects/01-clearledger/Dockerfile',
                   'FROM postgres@sha256:' + 'a' * 64 + ' AS builder\n'
                   'FROM builder AS final\n')
        self.assertEqual({PG}, self.inspect())

    def test_dockerfile_must_declare_external_stage(self):
        self.write('projects/01-clearledger/Dockerfile',
                   'FROM apache/kafka@sha256:' + 'b' * 64 + ' AS base\n')
        with self.assertRaisesRegex(ValueError, 'undeclared'):
            self.inspect()
        self.declare(PG, KAFKA)
        self.assertEqual({PG, KAFKA}, self.inspect())

    def test_scratch_is_not_an_external_image(self):
        self.write('projects/01-clearledger/Dockerfile',
                   'FROM scratch AS empty\n'
                   'FROM postgres@sha256:' + 'a' * 64 + ' AS data\n'
                   'FROM empty AS output\n')
        self.assertEqual({PG}, self.inspect())

    def test_malleable_and_dynamic_base_rejected(self):
        for image in ('ubuntu:latest', 'postgres', '$IMAGE', 'postgres@sha256:1234'):
            with self.subTest(image=image):
                self.write('projects/01-clearledger/Dockerfile', 'FROM ' + image + '\n')
                with self.assertRaises(ValueError):
                    self.inspect()

    def test_bad_multistage_syntax_and_alias_rejected(self):
        for command in ('FROM\n', 'FROM a AS one AS two\n',
                        'FROM --platform=linux/amd64\n'):
            with self.subTest(command=command):
                self.write('projects/01-clearledger/Dockerfile', command)
                with self.assertRaises(ValueError):
                    self.inspect()
        self.write('projects/01-clearledger/Dockerfile',
                   'FROM postgres@sha256:' + 'a' * 64 + ' AS s\n'
                   'FROM s AS s\n')
        with self.assertRaisesRegex(ValueError, 'duplicate stage'):
            self.inspect()

    def test_alternate_dockerfile_filenames_scanned(self):
        for filename in ('Dockerfile.prod', 'backend.dockerfile'):
            with self.subTest(filename=filename):
                source = self.write('projects/01-clearledger/' + filename,
                                    'FROM ubuntu:latest\n')
                with self.assertRaises(ValueError):
                    self.inspect()
                source.unlink()

    def test_ci_and_shared_integration_yamls_must_be_declared(self):
        ci = self.write('.github/workflows/build.yml',
                        'jobs:\n  image:\n    container:\n      image: ubuntu:latest\n')
        with self.assertRaises(ValueError):
            self.inspect()
        ci.unlink()
        shared = self.write('integration/coexistence/service.yaml',
                            'services:\n  broker:\n    image: apache/kafka@sha256:' + 'b' * 64 + '\n')
        with self.assertRaisesRegex(ValueError, 'undeclared'):
            self.inspect()
        self.declare(PG, KAFKA)
        self.assertEqual({PG, KAFKA}, self.inspect())
        shared.unlink()

    def test_job_container_shorthand_must_be_immutable(self):
        action = self.write('.github/workflows/containers.yml',
                            'jobs:\n  test:\n    container: ubuntu:latest\n')
        with self.assertRaises(ValueError):
            self.inspect()
        action.write_text(
            'jobs:\n  test:\n    container: "postgres@sha256:' + 'a' * 64 + '"\n')
        self.assertEqual({PG}, self.inspect())

    def test_docker_action_pulls_are_in_the_same_inventory(self):
        action = self.write('.github/workflows/actions.yml',
                            'jobs:\n  test:\n    steps:\n'
                            '      - uses: docker://ubuntu:latest\n')
        with self.assertRaises(ValueError):
            self.inspect()
        action.write_text(
            'jobs:\n  test:\n    steps:\n'
            '      - uses: docker://postgres@sha256:' + 'a' * 64 + '\n')
        self.assertEqual({PG}, self.inspect())

    def test_ci_dynamic_images_and_inline_maps_fail_closed(self):
        image_variable = '
        for path in ('projects/01-clearledger/target/Dockerfile',
                     'projects/01-clearledger/.runtime/service.dockerfile',
                     'projects/01-clearledger/node_modules/Dockerfile'):
            self.write(path, 'FROM ubuntu:latest\n')
        self.assertEqual({PG}, self.inspect())

    def test_unpinned_testcontainers_does_not_evade_gate(self):
        path = self.write('projects/01-clearledger/check/src/test/java/ImageIT.java',
                          'DockerImageName.parse("ubuntu:latest");')
        with self.assertRaises(ValueError):
            self.inspect()
        path.write_text('DockerImageName.parse("postgres@sha256:' + 'a' * 64 + '");')
        self.assertEqual({PG}, self.inspect())

    def test_unused_declaration_is_still_failure(self):
        self.declare(PG, KAFKA)
        with self.assertRaisesRegex(ValueError, 'unused'):
            self.inspect()


if __name__ == '__main__':
    unittest.main()
 + '{{ matrix.image }}'
        for setting in ('container: ' + image_variable,
                        'uses: docker://' + image_variable,
                        'container: {image: "ubuntu:latest"}'):
            with self.subTest(setting=setting):
                path = self.write('.github/workflows/invalid.yml',
                                  'jobs:\n  build:\n    ' + setting + '\n')
                with self.assertRaises(ValueError):
                    self.inspect()
                path.unlink()

    def test_generated_cache_directories_are_skipped(self):
        for path in ('projects/01-clearledger/target/Dockerfile',
                     'projects/01-clearledger/.runtime/service.dockerfile',
                     'projects/01-clearledger/node_modules/Dockerfile'):
            self.write(path, 'FROM ubuntu:latest\n')
        self.assertEqual({PG}, self.inspect())

    def test_unpinned_testcontainers_does_not_evade_gate(self):
        path = self.write('projects/01-clearledger/check/src/test/java/ImageIT.java',
                          'DockerImageName.parse("ubuntu:latest");')
        with self.assertRaises(ValueError):
            self.inspect()
        path.write_text('DockerImageName.parse("postgres@sha256:' + 'a' * 64 + '");')
        self.assertEqual({PG}, self.inspect())

    def test_unused_declaration_is_still_failure(self):
        self.declare(PG, KAFKA)
        with self.assertRaisesRegex(ValueError, 'unused'):
            self.inspect()


if __name__ == '__main__':
    unittest.main()
