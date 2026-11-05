import os
import re
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
aapts = sorted(Path(os.environ['ANDROID_HOME']).glob('build-tools/*/aapt2'))
assert aapts
tree = subprocess.check_output([str(aapts[-1]), 'dump', 'xmltree', '--file', 'AndroidManifest.xml', apk], text=True)
resources = subprocess.check_output([str(aapts[-1]), 'dump', 'resources', apk], text=True)
for attribute, name, attr_id in [('fullBackupContent', 'probe_backup_rules', '0x010104eb'), ('dataExtractionRules', 'probe_data_extraction_rules', '0x0101063e')]:
	match = re.search(r'http://schemas\.android\.com/apk/res/android:' + attribute + r'\(' + attr_id + r'\)=@(0x[0-9a-fA-F]{8})', tree)
	assert match
	resource_id = match.group(1).lower()
	value = packaged_app.get(android + attribute)
	assert value is not None
	if re.fullmatch(r'@(?:ref/)?0[xX][0-9a-fA-F]{1,8}', value):
		assert int(value.removeprefix('@ref/').removeprefix('@'), 16) == int(resource_id, 16)
	elif re.fullmatch(r'@[0-9]+', value):
		assert int(value[1:], 10) == int(resource_id, 16)
	else:
		assert value == '@xml/' + name
	entries = re.split(r'(?m)^\s*resource ', resources)
	matches = [entry for entry in entries if re.match(re.escape(resource_id) + r'\s', entry.lower())]
	assert len(matches) == 1
	lines = matches[0].splitlines()
	assert re.fullmatch(re.escape(resource_id) + r'\s+xml/' + name + r'(?:\s+PUBLIC)?\s*', lines[0], re.IGNORECASE)
	assert [line.strip() for line in lines[1:] if line.strip().startswith('(')] == ['() (file) res/xml/' + name + '.xml type=XML']
for name, root in [('probe_backup_rules', 'full-backup-content'), ('probe_data_extraction_rules', 'data-extraction-rules')]:
	rules = ET.fromstring(analyze('resources', 'xml', '--file', '/res/xml/' + name + '.xml'))
	assert rules.tag == root
	sections = [rules] if root == 'full-backup-content' else list(rules)
	assert len(sections) == (1 if root == 'full-backup-content' else 2)
	for section in sections:
		assert {(node.tag, node.get('domain'), node.get('path')) for node in section} == {
			('include', 'sharedpref', '.'), ('include', 'file', '.')}
print('STAGE: merged and packaged permissive probe policy verified')
