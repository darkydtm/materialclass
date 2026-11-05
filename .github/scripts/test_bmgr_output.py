import subprocess
import sys
import unittest
from pathlib import Path

PARSER = Path(__file__).with_name('check-bmgr-output.py')
PACKAGE = 'org.materialclass'
BACKUP = '''Running incremental backup for 1 requested packages.
Package @pm@ with result: Success
Package org.materialclass with progress: 512/1024
Package org.materialclass with result: Success
Backup finished with result: Success
'''
RESTORE = '''restoreStarting: 1 packages
onUpdate: 0 = org.materialclass
restoreFinished: 0
done
'''


class BmgrOutputTest(unittest.TestCase):
	def check_output(self, mode, output, expected, api=24):
		result = subprocess.run([sys.executable, str(PARSER), mode, PACKAGE, str(api)], input=output, text=True, capture_output=True)
		self.assertEqual(result.returncode == 0, expected, result.stderr)

	def test_backup_actual_shape(self):
		self.check_output('backup', BACKUP, True)

	def test_backup_rejects_impostor_success(self):
		self.check_output('backup', BACKUP.replace('result: Success\nBackup', 'result: SuccessButActuallyFailed\nBackup'), False)

	def test_backup_rejects_duplicate_and_unexpected_results(self):
		for event in ('Package org.materialclass with result: Success', 'Package other.app with result: Success', 'Package org.materialclass with result: Failure'):
			with self.subTest(event=event):
				self.check_output('backup', BACKUP + event + '\n', False)

	def test_restore_actual_shapes(self):
		for index in (0, 1):
			prefix = '' if index == 0 else 'Scheduling restore: Local disk image\n'
			self.check_output('restore', prefix + RESTORE.replace('onUpdate: 0', f'onUpdate: {index}'), True, 24 if index == 0 else 37)

	def test_backup_legacy_shape(self):
		self.check_output('backup', BACKUP.replace('Running incremental backup', 'Running backup'), True)

	def test_restore_rejects_missing_duplicate_mismatched_and_replayed_events(self):
		for output in (RESTORE.replace('onUpdate: 0 = org.materialclass\n', ''), RESTORE.replace('org.materialclass', 'other.app'), RESTORE + RESTORE, RESTORE.replace('restoreFinished: 0', 'restoreFinished: 1'), RESTORE.replace('done\n', 'onUpdate: 1 = other.app\ndone\n')):
			with self.subTest(output=output):
				self.check_output('restore', output, False)

	def test_backup_rejects_unexpected_events_before_finish(self):
		for event in ('Package other.app with result: Success', 'Package org.materialclass with result: Success', 'Package org.materialclass with result: Failure', 'Package org.materialclass with progress: 512/1024'):
			with self.subTest(event=event):
				self.check_output('backup', BACKUP.replace('Backup finished', event + '\nBackup finished'), False)

	def test_backup_rejects_unexpected_events_before_package_result(self):
		for event in ('Package other.app with result: Success', 'Package @pm@ with result: Success', 'Package org.materialclass with result: Failure'):
			with self.subTest(event=event):
				self.check_output('backup', BACKUP.replace('Package org.materialclass with result: Success', event + '\nPackage org.materialclass with result: Success'), False)

	def test_backup_rejects_malicious_success_and_trailing_whitespace(self):
		for original in ('Package org.materialclass with result: Success', 'Backup finished with result: Success'):
			for suffix in ('ButActuallyFailed', ' ', '\t'):
				with self.subTest(original=original, suffix=suffix):
					self.check_output('backup', BACKUP.replace(original, original + suffix), False)

	def test_restore_rejects_multiple_updates_inside_sequence(self):
		for event in ('onUpdate: 0 = org.materialclass', 'onUpdate: 1 = other.app', 'onUpdate: 0 = other.app'):
			with self.subTest(event=event):
				self.check_output('restore', RESTORE.replace('restoreFinished: 0', event + '\nrestoreFinished: 0'), False)

	def test_restore_rejects_failure_inside_sequence(self):
		for event in ('restoreFinished: 1', 'restoreFinished: -1 ', 'onUpdate: 0 = org.materialclass '):
			with self.subTest(event=event):
				self.check_output('restore', RESTORE.replace('restoreFinished: 0', event + '\nrestoreFinished: 0'), False)

	def test_restore_rejects_nonexact_start_and_finish(self):
		for original in ('restoreStarting: 1 packages', 'restoreFinished: 0'):
			for suffix in ('0', 'Success', ' ', '\t'):
				with self.subTest(original=original, suffix=suffix):
					self.check_output('restore', RESTORE.replace(original, original + suffix), False)


if __name__ == '__main__':
	unittest.main()
