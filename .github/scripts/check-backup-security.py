import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(sys.argv[1])
android = '{http://schemas.android.com/apk/res/android}'
manifest = ET.parse(root / 'manifest.xml').getroot()
assert manifest.tag == 'manifest'
apps = [element for element in manifest.iter() if element.tag.split('}')[-1] == 'application']
assert len(apps) == 1 and apps[0] in list(manifest) and apps[0].tag == 'application'
app = apps[0]
assert app.get(android + 'allowBackup') == 'true'
tree = (root / 'manifest-tree.txt').read_text()
resources = (root / 'resources.txt').read_text()
stack = []
applications = []
for line in tree.splitlines():
	match = re.match(r'^( *)([EA]): (.*)$', line)
	if not match:
		continue
	indent, kind, content = match.groups()
	depth = len(indent)
	if kind == 'E':
		while stack and stack[-1][0] >= depth:
			stack.pop()
		name = content.split(' ', 1)[0]
		if not stack:
			assert name == 'manifest'
		if name.split(':')[-1] == 'application':
			assert name == 'application' and len(stack) == 1 and stack[0][1] == 'manifest'
			applications.append({})
		stack.append((depth, name))
	elif stack and len(stack) == 2 and stack[-1][1] == 'application' and depth > stack[-1][0]:
		match = re.fullmatch(r'http://schemas\.android\.com/apk/res/android:(fullBackupContent|dataExtractionRules)\((0x[0-9a-fA-F]+)\)=@(0x[0-9a-fA-F]{8})', content)
		if match:
			attribute, attribute_id, resource_id = match.groups()
			assert attribute_id.lower() == {'fullBackupContent': '0x010104eb', 'dataExtractionRules': '0x0101063e'}[attribute]
			assert attribute not in applications[-1]
			applications[-1][attribute] = resource_id.lower()
assert len(applications) == 1
for attribute, name in (('fullBackupContent', 'backup_rules'), ('dataExtractionRules', 'data_extraction_rules')):
	resource_id = applications[0][attribute]
	value = app.get(android + attribute)
	assert value is not None and value.startswith('@'), (attribute, value)

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
