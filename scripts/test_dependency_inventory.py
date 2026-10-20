"""Security execution evidence must cover the complete resolved dependency inventory."""
from copy import deepcopy
from pathlib import Path
import importlib.util
import tempfile
import unittest

from test_build_tools import make_reactor

spec = importlib.util.spec_from_file_location('dependency_inventory', Path(__file__).with_name('dependency-inventory.py'))
inventory = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inventory)


class DependencyInventoryTest(unittest.TestCase):
    def setUp(self):
        self.root_id = 'pkg:maven/io.github.cheshmakzanoon.portfolio/portfolio@1.0.0?type=pom'
        self.external_id = 'pkg:maven/example/library@2.0?type=jar'
        self.external = {'purl': self.external_id, 'bom-ref': self.external_id,
                         'group': 'example', 'name': 'library', 'version': '2.0',
                         'licenses': [{'license': {'id': 'Apache-2.0'}}],
                         'hashes': [{'alg': 'SHA-256', 'content': 'a' * 64}]}
        self.bom = {'bomFormat': 'CycloneDX', 'specVersion': '1.6',
                    'metadata': {'component': {'purl': self.root_id, 'bom-ref': self.root_id}},
                    'components': [self.external],
                    'dependencies': [{'ref': self.root_id, 'dependsOn': [self.external_id]},
                                     {'ref': self.external_id, 'dependsOn': []}]}
        self.scan = {'ArtifactType': 'cyclonedx', 'Results': [{'Class': 'lang-pkgs', 'Type': 'jar',
                     'Packages': [{'Identifier': {'PURL': identity}} for identity in (self.root_id, self.external_id)]}]}

    def test_complete_inventory_and_scanner_coverage_pass(self):
        components, external = inventory.inspect(self.bom, self.scan)
        self.assertEqual([self.external], components)
        self.assertEqual(components, external)

    def test_empty_partial_and_extra_scanned_packages_fail(self):
        for identities in ([], [self.root_id], [self.root_id, self.external_id, 'unresolved']):
            with self.subTest(identities=identities):
                scan = deepcopy(self.scan)
                scan['Results'][0]['Packages'] = [{'Identifier': {'PURL': identity}} for identity in identities]
                with self.assertRaisesRegex(ValueError, 'coverage'):
                    inventory.inspect(self.bom, scan)
        with self.assertRaisesRegex(ValueError, 'coverage'):
            inventory.inspect(self.bom, {'ArtifactType': 'cyclonedx', 'Results': []})

    def test_wrong_scope_or_high_critical_findings_fail(self):
        for severity in ('HIGH', 'CRITICAL'):
            scan = deepcopy(self.scan)
            scan['Results'][0]['Vulnerabilities'] = [{'Severity': severity}]
            with self.assertRaisesRegex(ValueError, 'findings'):
                inventory.inspect(self.bom, scan)
        self.scan['Results'][0]['Type'] = 'os'
        with self.assertRaisesRegex(ValueError, 'scope'):
            inventory.inspect(self.bom, self.scan)

    def test_duplicate_missing_or_unresolved_graph_identities_fail(self):
        for mutate in (
                lambda bom: bom['components'].append(deepcopy(self.external)),
                lambda bom: bom['dependencies'].pop(),
                lambda bom: bom['dependencies'][0]['dependsOn'].append('missing'),
                lambda bom: bom['metadata']['component'].pop('purl')):
            bom = deepcopy(self.bom)
            mutate(bom)
            with self.assertRaises(ValueError):
                inventory.inspect(bom, self.scan)

    def test_published_hashes_and_licenses_are_required(self):
        for key in ('licenses', 'hashes'):
            bom = deepcopy(self.bom)
            bom['components'][0].pop(key)
            with self.assertRaisesRegex(ValueError, 'licenses or SHA-256'):
                inventory.inspect(bom, self.scan)
        self.external['licenses'] = [{'expression': 'EPL-2.0 OR LGPL-2.1-only'}]
        self.assertIn('EPL-2.0 OR LGPL-2.1-only', inventory.license_table([self.external]))

    def test_empty_license_records_cannot_claim_published_metadata(self):
        for entries in ([{}], [{'expression': '   '}], [{'license': {'id': '  '}}],
                        [{'license': {'name': ''}}], [{'license': 'malformed'}]):
            with self.subTest(entries=entries):
                bom = deepcopy(self.bom)
                bom['components'][0]['licenses'] = entries
                with self.assertRaises(ValueError):
                    inventory.inspect(bom, self.scan)

    def test_pom_change_during_generation_cannot_bind_a_stale_scan(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            make_reactor(root, ('core',))
            before = inventory.pom_manifest(root)
            inventory.require_inputs(root, before)
            with (root / 'core/pom.xml').open('a') as output:
                output.write('\n<!-- changed after inventory generation -->\n')
            with self.assertRaisesRegex(ValueError, 'changed'):
                inventory.require_inputs(root, before)


if __name__ == '__main__':
    unittest.main()
