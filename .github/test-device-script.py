#!/usr/bin/env python3
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / '.github/workflows/android-ci.yml'
DEVICE_SCRIPT = ROOT / '.github/device-acceptance.sh'
EXPECTED_SMOKE_CLASSES = {
	'org.materialclass.LauncherTest',
	'org.materialclass.OfflineUiTest',
	'org.materialclass.NativeControllerIncompleteIdentityTest',
	'org.materialclass.NativeControllerPresenceTest',
	'org.materialclass.api.NativeGatewayTest',
	'org.materialclass.data.NativeIdentityVerifierTest',
	'org.materialclass.data.RepositorySessionTest',
	'org.materialclass.security.StorageSecurityTest',
}


class DeviceScriptTest(unittest.TestCase):
	def test_device_job_uses_smoke_script_and_keeps_timeout(self):
		workflow = WORKFLOW.read_text()
		device = workflow.split('  device:\n', 1)[1].split('\n  test-apk:', 1)[0]
		self.assertIn('timeout-minutes: 60', device)
		self.assertIn('.github/device-acceptance.sh', device)
		self.assertNotIn('connectedDebugAndroidTest', device)
		self.assertIn('DEVICE_SMOKE_CLASSES', device)

	def test_allowlist_is_explicit_and_stable(self):
		workflow = WORKFLOW.read_text()
		line = next(line for line in workflow.splitlines() if 'org.materialclass.LauncherTest,org.materialclass.OfflineUiTest' in line)
		classes = set(line.strip().split(','))
		self.assertEqual(EXPECTED_SMOKE_CLASSES, classes)
		script = DEVICE_SCRIPT.read_text()
		self.assertIn('--include-class org.materialclass.security.StorageSecurityTest', script)
		self.assertIn('reconcile-instrumentation.py', script)

	def test_workflow_does_not_hide_coverage_changes(self):
		workflow = WORKFLOW.read_text()
		self.assertNotIn('continue-on-error', workflow)
		self.assertNotIn('xfail', workflow)
		self.assertNotRegex(workflow, re.compile(r'\bskip\b', re.IGNORECASE))


if __name__ == '__main__':
	unittest.main(verbosity=2)
