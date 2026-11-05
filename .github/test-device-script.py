#!/usr/bin/env python3
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / '.github/workflows/android-ci.yml'

WAKE = [
	'adb shell input keyevent KEYCODE_WAKEUP',
	'adb shell wm dismiss-keyguard',
]
RESTART = [
	'gradlew --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=org.materialclass.session.CleanupProcessRestartTest',
	'gradlew --no-daemon assembleDebug assembleDebugAndroidTest',
	'adb install -r app/build/outputs/apk/debug/app-debug.apk',
	'adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',
	'bash .github/cleanup-restart.sh',
]
DEFAULT = [
	'gradlew --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=org.materialclass.session.CleanupProcessRestartTest',
]
DEBUG_APK = [
	'gradlew --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.materialclass.NativeUiWiringTest',
	'gradlew --no-daemon -PintegrationTestNativeUi=true connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=org.materialclass.session.CleanupProcessRestartTest',
]
DEFAULT_BRANCHES = (
	'ci-android-toolchain',
	'fix/verified-session-restore',
	'feat/integrate-student-work',
	'fix/schedule-stale-flags',
	'feat/integrate-student-v2',
	'feat/integrate-a11y',
	'feat/integrate-schedule-races',
	'feat/integrate-student-v3',
	'feat/integrate-durable',
	'feat/integrate-restore',
	'feat/integrate-profile-grades',
	'feat/integrate-security',
	'feat/integrate-readiness',
)


def device_script(ref):
	text = WORKFLOW.read_text()
	device = text.split('  device:\n', 1)[1].split('\n  test-apk:', 1)[0]
	script = device.split('          script: |\n', 1)[1]
	return textwrap.dedent(script).replace('${{ github.ref }}', ref)


class DeviceScriptTest(unittest.TestCase):
	def run_script(self, branch, failure='never'):
		with tempfile.TemporaryDirectory(dir=os.environ.get('RUNNER_TEMP') or os.environ.get('TMPDIR')) as directory:
			root = Path(directory)
			log = root / 'commands'
			fake = '''#!/bin/bash
command="$(basename "$0") $*"
printf 'CALL %s\\n' "$command" >> "$COMMAND_LOG"
if [ "$(basename "$0")" = bash ]; then exec /bin/bash "$@"; fi
if [ "$command" = "$FAIL_COMMAND" ]; then
  printf 'INJECTED %s\\n' "$command" >> "$COMMAND_LOG"
  exit 23
fi
printf 'EXIT 0 %s\\n' "$command" >> "$COMMAND_LOG"
'''
			for name in ('gradlew', 'adb', 'bash'):
				path = root / name
				path.write_text(fake)
				path.chmod(0o755)
			(root / '.github').mkdir()
			(root / '.github/cleanup-restart.sh').write_text('''printf 'RESTART ENTERED\\n' >> "$COMMAND_LOG"
if [ "$FAIL_COMMAND" = 'bash .github/cleanup-restart.sh' ]; then
  printf 'INJECTED restart-script\\n' >> "$COMMAND_LOG"
  exit 23
fi
printf 'RESTART EXIT 0\\n' >> "$COMMAND_LOG"
''')
			env = dict(os.environ, PATH=f'{root}:{os.environ["PATH"]}', COMMAND_LOG=str(log), FAIL_COMMAND=failure)
			for line in device_script(f'refs/heads/{branch}').splitlines():
				result = subprocess.run(['/bin/sh', '-c', line], cwd=root, env=env, capture_output=True, text=True)
				if result.returncode:
					return result.returncode, log.read_text().splitlines() if log.exists() else []
			return 0, log.read_text().splitlines() if log.exists() else []

	def expected_log(self, commands, failure='never'):
		log = []
		for command in commands:
			log.append(f'CALL {command}')
			if command == 'bash .github/cleanup-restart.sh':
				log.append('RESTART ENTERED')
				if command == failure:
					log.append('INJECTED restart-script')
					break
				log.append('RESTART EXIT 0')
			elif command == failure:
				log.append(f'INJECTED {command}')
				break
			else:
				log.append(f'EXIT 0 {command}')
		return log

	def test_routes_and_stops_on_every_failure(self):
		routes = [('feat/integrate-backup-cleanup', RESTART), ('feat/debug-apk', DEBUG_APK)]
		routes.extend((branch, RESTART + DEBUG_APK[1:]) for branch in ('feat/integrate-restart-ui', 'feat/integrate-network-contract', 'feat/integrate-homework', 'feat/integrate-profile-security'))
		routes.extend((branch, DEFAULT) for branch in DEFAULT_BRANCHES)
		routes.append(('unlisted-branch', DEFAULT))
		for branch, commands in routes:
			commands = WAKE + commands
			for failure in ['never', *commands]:
				with self.subTest(branch=branch, failure=failure):
					status, log = self.run_script(branch, failure)
					self.assertEqual(0 if failure == 'never' else 23, status, log)
					self.assertEqual(self.expected_log(commands, failure), log)


if __name__ == '__main__':
	unittest.main(verbosity=2)
