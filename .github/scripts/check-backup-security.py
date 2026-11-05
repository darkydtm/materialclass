import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(sys.argv[1])
android = '{http://schemas.android.com/apk/res/android}'
manifest = ET.parse(root / 'manifest.xml').getroot()
app = manifest.find('application')
assert app is not None
assert app.get(android + 'allowBackup') == 'true'
tree = (root / 'manifest-tree.txt').read_text()
resources = (root / 'resources.txt').read_text()
for attribute, name in (('fullBackupContent', 'backup_rules'), ('dataExtractionRules', 'data_extraction_rules')):
	matches = re.findall(r'^\s*A: http://schemas\.android\.com/apk/res/android:' + attribute + r'\(0x[0-9a-fA-F]+\)=@(0x[0-9a-fA-F]{8})\s*$', tree, re.MULTILINE)
	assert len(matches) == 1, (attribute, matches)
	resource_id = matches[0].lower()
	entries = re.split(r'(?m)^\s*resource ', resources)
	matches = [entry for entry in entries if re.match(re.escape(resource_id) + r'\s', entry.lower())]
	assert len(matches) == 1, (attribute, resource_id)
	lines = matches[0].splitlines()
	assert re.fullmatch(re.escape(resource_id) + r'\s+xml/' + name + r'(?:\s+PUBLIC)?\s*', lines[0], re.IGNORECASE), lines[0]
	values = [line.strip() for line in lines[1:] if line.strip().startswith('(')]
	assert values == ['() (file) res/xml/' + name + '.xml type=XML'], values


def check_rules(element):
	assert [(child.tag, child.attrib) for child in element] == [
		('include', {'domain': 'sharedpref', 'path': 'appearance.xml'})
	]


legacy = ET.parse(root / 'backup_rules.xml').getroot()
assert legacy.tag == 'full-backup-content'
check_rules(legacy)
modern = ET.parse(root / 'data_extraction_rules.xml').getroot()
assert modern.tag == 'data-extraction-rules'
assert [child.tag for child in modern] == ['cloud-backup', 'device-transfer']
for child in modern:
	check_rules(child)
print('Packaged legacy, cloud and device-transfer rules allow only appearance.xml')
print('Static packaged rule verification only; no backup or restore was performed')
