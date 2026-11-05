import sys
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(sys.argv[1])
android = '{http://schemas.android.com/apk/res/android}'
manifest = ET.parse(root / 'manifest.xml').getroot()
app = manifest.find('application')
assert app is not None
assert app.get(android + 'allowBackup') == 'true'
for attribute in ('fullBackupContent', 'dataExtractionRules'):
	value = app.get(android + attribute)
	assert value and value.startswith('@'), (attribute, value)
merged = list(Path('app/build/intermediates/merged_manifests/debug').rglob('AndroidManifest.xml'))
assert len(merged) == 1, merged
merged_app = ET.parse(merged[0]).getroot().find('application')
assert merged_app is not None
assert merged_app.get(android + 'fullBackupContent') == '@xml/backup_rules'
assert merged_app.get(android + 'dataExtractionRules') == '@xml/data_extraction_rules'


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
