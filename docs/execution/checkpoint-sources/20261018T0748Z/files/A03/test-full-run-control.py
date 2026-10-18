#!/usr/bin/env python3
"""Small control checks: frozen input validation and refusal of stale/smoke evidence."""
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

WORK = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('quorumkv_full', WORK / 'execute-prepared-full.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class FullRunControlTest(unittest.TestCase):
    def test_current_actual_artifacts_match_prepared_packet(self):
        packet = runner.validate_inputs()
        self.assertEqual(runner.SOURCE, packet['sourceFingerprint'])
        self.assertEqual(82, packet['artifactCount'])

    def test_old_source_stamp_is_refused_before_any_process_launch(self):
        with tempfile.TemporaryDirectory(dir=WORK) as directory:
            project = Path(directory) / 'project'
            target = project / 'raft-verification/target'
            target.mkdir(parents=True)
            for name in ('verified-artifacts.json', 'runtime-classpath.txt'):
                shutil.copyfile(runner.INPUTS / name, target / name)
            (target / 'verified-source.sha256').write_text('c2fa4c96f74db0a7ca20facb579713eb2c79cd12e4d8c52b264befa6f380b7ac\n')
            with patch.object(runner, 'PROJECT', project), patch.object(runner.subprocess, 'Popen') as launch:
                with self.assertRaisesRegex(RuntimeError, 'receipt/classpath differs'):
                    runner.validate_inputs()
                launch.assert_not_called()

    def test_changed_runner_receipt_is_refused(self):
        with tempfile.TemporaryDirectory(dir=WORK) as directory:
            inputs = Path(directory) / 'inputs'
            shutil.copytree(runner.INPUTS, inputs)
            packet = json.loads((inputs / 'prepared.json').read_text())
            packet['runnerSha256'] = '0' * 64
            (inputs / 'prepared.json').write_text(json.dumps(packet))
            with patch.object(runner, 'INPUTS', inputs):
                with self.assertRaisesRegex(RuntimeError, 'runner receipt changed'):
                    runner.validate_inputs()

    def test_small_smoke_cannot_pass_full_study_assertions(self):
        with tempfile.TemporaryDirectory(dir=WORK) as directory:
            raw = Path(directory)
            shutil.copyfile(WORK / 'benchmark-36LidKTB/workload.csv', raw / 'workload.csv')
            with self.assertRaisesRegex(RuntimeError, '18 distinct full scenarios'):
                runner.assert_results(raw, raw / 'absent-resources.jsonl')

    def test_cleanup_does_not_signal_unrelated_java(self):
        unrelated = {'argv': [runner.NODE_MAIN, '0', '/different/runtime/node-0', '/different/runtime/tls', '18300'],
                     'pid': 987654, 'pgrp': 123456, 'state': 'S'}
        with patch.object(runner, 'processes', return_value=[unrelated]), patch.object(runner.os, 'kill') as kill, patch.object(runner.os, 'killpg') as killpg:
            result = runner.cleanup(None, WORK / 'nonexistent-private-study/raw')
            self.assertEqual([], result['remainingOwnedProcesses'])
            kill.assert_not_called()
            killpg.assert_not_called()


if __name__ == '__main__':
    unittest.main()
