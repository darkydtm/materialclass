#!/usr/bin/env python3
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / '.github/workflows/android-ci.yml'


def device_script(ref):
	text = WORKFLOW.read_text()
	device = text.split('  device:\n', 1)[1].split('\n  test-apk:', 1)[0]
	script = device.split('          script: |\n', 1)[1]
	return textwrap.dedent(script).replace('${{ github.ref }}', ref)


class DeviceScriptTest(unittest.TestCase):
	def run_script(self, failure):
		with tempfile.TemporaryDirectory(dir=os.environ.get('RUNNER_TEMP') or os.environ.get('TMPDIR')) as directory:
			root = Path(directory)
			log = root / 'commands'
			fake = '''#!/bin/bash
printf '%s\\n' "$(basename "$0") $*" >> "$COMMAND_LOG"
if [ "$(basename "$0") $*" = "$FAIL_COMMAND" ]; then exit 23; fi
'''
			for name in ('gradlew', 'adb', 'bash'):
				path = root / name
				path.write_text(fake)
				path.chmod(0o755)
			(root / '.github').mkdir()
			(root / '.github/cleanup-restart.sh').write_text('exit 99\n')
			env = dict(os.environ, PATH=f'{root}:{os.environ["PATH"]}', COMMAND_LOG=str(log), FAIL_COMMAND=failure)
			result = subprocess.run(['/bin/bash', '-e', '-c', device_script('refs/heads/test/cleanup-restart')], cwd=root, env=env, capture_output=True, text=True)
			return result.returncode, log.read_text().splitlines()

	def test_failures_stop_before_restart(self):
		commands = [
			'gradlew --no-daemon assembleDebug assembleDebugAndroidTest',
			'adb install app/build/outputs/apk/debug/app-debug.apk',
			'adb install app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',
		]
		for index, command in enumerate(commands):
			with self.subTest(command=command):
				status, calls = self.run_script(command)
				self.assertNotEqual(0, status, calls)
				self.assertEqual(commands[:index + 1], calls[2:])

	def test_success_runs_restart(self):
		status, calls = self.run_script('never')
		self.assertEqual(0, status, calls)
		self.assertEqual('bash .github/cleanup-restart.sh', calls[-1])

	def test_restart_failure_propagates(self):
		status, calls = self.run_script('bash .github/cleanup-restart.sh')
		self.assertNotEqual(0, status, calls)


if __name__ == '__main__':
	unittest.main(verbosity=2)
