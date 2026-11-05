import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

android = '{http://schemas.android.com/apk/res/android}'
manifest_paths = list(Path('app/build/intermediates/merged_manifests/debug').rglob('AndroidManifest.xml'))
assert len(manifest_paths) == 1, manifest_paths
manifest = ET.parse(manifest_paths[0]).getroot()
apps = manifest.findall('application')
assert len(apps) == 1
app = apps[0]
assert app.get(android + 'allowBackup') == 'true'
assert app.get(android + 'backupAgent') is None
assert app.get(android + 'fullBackupContent') == '@xml/probe_backup_rules'
assert app.get(android + 'dataExtractionRules') == '@xml/probe_data_extraction_rules'
analyzers = sorted(Path(os.environ['ANDROID_HOME']).glob('cmdline-tools/*/bin/apkanalyzer'))
assert analyzers
apk = str(Path(os.environ['RUNNER_TEMP']) / 'probe.apk')
def analyze(*args):
	return subprocess.check_output([str(analyzers[-1]), *args, apk], text=True).strip()
assert analyze('manifest', 'application-id') == 'org.materialclass.backupprobe'
packaged = ET.fromstring(analyze('manifest', 'print'))
packaged_app = packaged.find('application')
assert packaged_app is not None
assert packaged_app.get(android + 'backupAgent') is None
assert packaged_app.get(android + 'fullBackupContent') == '@xml/probe_backup_rules'
assert packaged_app.get(android + 'dataExtractionRules') == '@xml/probe_data_extraction_rules'
for name, root in [('probe_backup_rules', 'full-backup-content'), ('probe_data_extraction_rules', 'data-extraction-rules')]:
	rules = ET.fromstring(analyze('resources', 'xml', '--file', '/res/xml/' + name + '.xml'))
	assert rules.tag == root
	sections = [rules] if root == 'full-backup-content' else list(rules)
	assert len(sections) == (1 if root == 'full-backup-content' else 2)
	for section in sections:
		assert {(node.tag, node.get('domain'), node.get('path')) for node in section} == {
			('include', 'sharedpref', '.'), ('include', 'file', '.')}
print('STAGE: merged and packaged permissive probe policy verified')
