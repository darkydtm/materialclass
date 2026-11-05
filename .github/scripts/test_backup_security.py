import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name('check-backup-security.py')


class BackupSecurityTest(unittest.TestCase):
	def setUp(self):
		self.temp = tempfile.TemporaryDirectory()
		self.addCleanup(self.temp.cleanup)
		self.root = Path(self.temp.name)
		merged = self.root / 'app/build/intermediates/merged_manifests/debug/AndroidManifest.xml'
		merged.parent.mkdir(parents=True)
		merged.write_text(self.manifest('@xml/backup_rules', '@xml/data_extraction_rules'))
		(self.root / 'manifest.xml').write_text(self.manifest('@0x7f150000', '@0x7f150001'))
		(self.root / 'manifest-tree.txt').write_text(self.tree('0x7f150000', '0x7f150001'))
		(self.root / 'resources.txt').write_text(
			'resource 0x7f150000 xml/backup_rules\n  () (file) res/xml/backup_rules.xml type=XML\n'
			'resource 0x7f150001 xml/data_extraction_rules\n  () (file) res/xml/data_extraction_rules.xml type=XML\n'
			'resource 0x7f150002 xml/evil\n  () (file) res/xml/evil.xml type=XML\n'
		)
		(self.root / 'backup_rules.xml').write_text('<full-backup-content><include domain="sharedpref" path="appearance.xml"/></full-backup-content>')
		(self.root / 'data_extraction_rules.xml').write_text('<data-extraction-rules><cloud-backup><include domain="sharedpref" path="appearance.xml"/></cloud-backup><device-transfer><include domain="sharedpref" path="appearance.xml"/></device-transfer></data-extraction-rules>')

	def manifest(self, legacy, modern):
		return f'<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:allowBackup="true" android:fullBackupContent="{legacy}" android:dataExtractionRules="{modern}"/></manifest>'

	def tree(self, legacy, modern):
		return f'E: manifest (line=1)\n  E: application (line=2)\n    A: http://schemas.android.com/apk/res/android:fullBackupContent(0x010104eb)=@{legacy}\n    A: http://schemas.android.com/apk/res/android:dataExtractionRules(0x0101064e)=@{modern}\n'

	def run_check(self):
		return subprocess.run([sys.executable, str(SCRIPT), str(self.root)], cwd=self.root, capture_output=True, text=True)

	def test_expected_packaged_references_pass(self):
		result = self.run_check()
		self.assertEqual(result.returncode, 0, result.stderr)

	def test_evil_packaged_reference_is_rejected(self):
		(self.root / 'manifest.xml').write_text(self.manifest('@0x7f150002', '@0x7f150001'))
		(self.root / 'manifest-tree.txt').write_text(self.tree('0x7f150002', '0x7f150001'))
		self.assertNotEqual(self.run_check().returncode, 0)

	def test_missing_resource_mapping_is_rejected(self):
		(self.root / 'resources.txt').write_text('')
		self.assertNotEqual(self.run_check().returncode, 0)

	def test_resource_alias_is_rejected(self):
		path = self.root / 'resources.txt'
		path.write_text(path.read_text().replace('(file) res/xml/backup_rules.xml type=XML', '@0x7f150002'))
		self.assertNotEqual(self.run_check().returncode, 0)

	def test_alternate_resource_configuration_is_rejected(self):
		path = self.root / 'resources.txt'
		path.write_text(path.read_text().replace('  () (file) res/xml/backup_rules.xml type=XML', '  () (file) res/xml/backup_rules.xml type=XML\n  (v31) (file) res/xml/evil.xml type=XML'))
		self.assertNotEqual(self.run_check().returncode, 0)


if __name__ == '__main__':
	unittest.main()
