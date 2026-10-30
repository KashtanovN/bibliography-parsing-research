import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[4]
spec = importlib.util.spec_from_file_location('summary', ROOT / 'scripts/summarize-run.py')
summary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(summary)


class ExperimentToolsTest(unittest.TestCase):
    def pipeline(self, dev_exit, tamper=False):
        with tempfile.TemporaryDirectory(prefix='experiment test ') as folder:
            base = Path(folder)
            root = base / 'project with spaces'
            (root / 'scripts').mkdir(parents=True)
            (root / 'backend/target').mkdir(parents=True)
            shutil.copyfile(ROOT / 'scripts/run-experiment.sh', root / 'scripts/run-experiment.sh')
            data = b'immutable workbook fixture'
            (root / 'Dataset.xlsx').write_bytes(data)
            (root / 'Dataset.sha256').write_text(hashlib.sha256(data).hexdigest() + '  Dataset.xlsx\n')
            (root / 'backend/target/bibliography-1.0.0.jar').write_text('not executed')
            for name, script in {
                'check-research-environment.sh': 'printf "environment\\n" >> "$TOOL_TEST_LOG"\nprintf "{}\\n"\n',
                'run-dev.sh': 'printf "dev\\n" >> "$TOOL_TEST_LOG"\nmkdir -p "$1"\nexit "$TOOL_TEST_DEV_EXIT"\n',
                'run-final-test.sh': 'printf "test\\n" >> "$TOOL_TEST_LOG"\nmkdir -p "$1"\n',
            }.items():
                (root / 'scripts' / name).write_text(script)
            bin_dir = base / 'bin'
            bin_dir.mkdir()
            for executable, event in [('java', 'freeze'), ('python3', 'summary')]:
                path = bin_dir / executable
                path.write_text('#!/bin/bash\nprintf "' + event + '\\n" >> "$TOOL_TEST_LOG"\n')
                path.chmod(0o755)
            log = base / 'calls.log'
            env = dict(os.environ, PATH=str(bin_dir) + os.pathsep + os.environ['PATH'],
                       TOOL_TEST_LOG=str(log), TOOL_TEST_DEV_EXIT=str(dev_exit))
            if tamper:
                (root / 'Dataset.xlsx').write_bytes(b'changed')
            command = ['bash', str(root / 'scripts/run-experiment.sh')]
            result = subprocess.run(command, env=env, capture_output=True, text=True, timeout=15)
            if tamper:
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(log.exists())
                return
            self.assertEqual(result.returncode, dev_exit, result.stdout + result.stderr)
            expected = ['environment', 'dev', 'freeze', 'test', 'summary'] if dev_exit == 0 else ['environment', 'dev']
            self.assertEqual(log.read_text().splitlines(), expected)
            state = (root / 'research-runs/experiment.pipeline-status').read_text().strip()
            self.assertEqual(state, 'AUTOMATIC_COMPLETED_REVIEW_REQUIRED' if dev_exit == 0 else 'FAILED')
            self.assertEqual((root / 'Dataset.xlsx').read_bytes(), data)
            self.assertFalse((root / 'research-runs/experiment.lock').exists())
            again = subprocess.run(command, env=env, capture_output=True, text=True, timeout=15)
            self.assertEqual(again.returncode, 2)
            self.assertEqual(log.read_text().splitlines(), expected)

    def test_pipeline_order_and_no_overwrite(self):
        self.pipeline(0)

    def test_pipeline_stops_before_test_on_failure(self):
        self.pipeline(2)

    def test_pipeline_rejects_changed_workbook_before_services(self):
        self.pipeline(0, tamper=True)

    def fixture(self, run):
        (run / 'evaluation').mkdir()
        methods = ['rules', 'grobid', 'ollama', 'anystyle']
        hashes = {}
        for method in methods:
            records = [dict(id='F1', method=method, status='SUCCESS', durationMillis=0),
                       dict(id='F2', method=method, status='ERROR', durationMillis=10)]
            path = run / (method + '.jsonl')
            path.write_text(''.join(json.dumps(r) + '\n' for r in records))
            hashes[path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
        (run / 'run.json').write_text(json.dumps(dict(executionState='COMPLETED', split='test',
            datasetSha256='fixture-sha', records=2, methods={m: {} for m in methods})))
        (run / 'evaluation/metrics.json').write_text(json.dumps(dict(datasetSha256='fixture-sha', records=2,
            results={m: {} for m in methods})))
        journal = run / 'evaluation/records.jsonl'
        journal.write_text('{}\n')
        (run / 'evaluation/comparison_manifest.json').write_text(json.dumps(dict(sourceHashes=hashes,
            recordsSha256=hashlib.sha256(journal.read_bytes()).hexdigest())))

    def test_summary_includes_errors_and_preserves_unknown_review_outcomes(self):
        with tempfile.TemporaryDirectory() as folder:
            run = Path(folder)
            self.fixture(run)
            data = summary.build(run)
            times = data['callDurations']['ollama']
            self.assertAlmostEqual(times['totalSeconds'], .01)
            self.assertAlmostEqual(times['meanSeconds'], .005)
            self.assertAlmostEqual(times['medianSeconds'], .005)
            self.assertEqual(times['statuses']['ERROR'], 1)
            self.assertEqual(times['zeroMillisCalls'], 1)
            self.assertIsNone(data['formattingCorrectRate'])
            self.assertIsNone(data['confirmedUnsupportedFacts'])
            self.assertTrue(data['subjectMatterReviewRequired'])
            values = dict(precision=.5, recall=.5, microF1=.5, macroF1=.5,
                          fullyCorrect=1, fullyCorrectEligible=2, fullyCorrectRate=.5,
                          extraFilledFields=0, conflictingFields=1)
            for result in data['extractionMetrics']['results'].values():
                result.update(summary=values, fields={'AUTHORS': {'f1': .5}, 'ISBN': {'f1': None}})
            text = summary.report(data)
            self.assertIn('1/2 (0.500000)', text)
            self.assertIn('| ISBN | — | — | — | — |', text)
            self.assertIn('0.010000', text)
            self.assertIn('требуют заполнения', text)

    def test_summary_rejects_changed_predictions(self):
        with tempfile.TemporaryDirectory() as folder:
            run = Path(folder)
            self.fixture(run)
            (run / 'ollama.jsonl').write_text('{}\n')
            with self.assertRaisesRegex(ValueError, 'Контрольная сумма'):
                summary.build(run)


if __name__ == '__main__':
    unittest.main()
