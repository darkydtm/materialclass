#!/usr/bin/env python3
import argparse
import re
import sys
from pathlib import Path


def source_tests(root: Path) -> set[str]:
	result: set[str] = set()
	package_pattern = re.compile(r'^\s*package\s+([\w.]+)', re.MULTILINE)
	class_pattern = re.compile(r'\bclass\s+(\w+)\b')
	test_pattern = re.compile(r'@Test(?:\s*\([^\n]*\))?\s*(?:\n\s*)?(?:public\s+|internal\s+|private\s+)?fun\s+(\w+)\s*\(')
	for path in sorted(root.rglob('*.kt')):
		text = path.read_text()
		package = package_pattern.search(text)
		if package is None:
			continue
		class_matches = list(class_pattern.finditer(text))
		if not class_matches:
			continue
		test_class = class_matches[0].group(1)
		for match in test_pattern.finditer(text):
			result.add(f'{package.group(1)}.{test_class}#{match.group(1)}')
	return result


def main() -> int:
	parser = argparse.ArgumentParser()
	parser.add_argument('output', type=Path)
	parser.add_argument('--source-root', type=Path, required=True)
	parser.add_argument('--exclude-class', action='append', default=[])
	parser.add_argument('--include-class', action='append', default=[])
	args = parser.parse_args()

	expected = source_tests(args.source_root)
	excluded = set(args.exclude_class)
	if excluded:
		expected = {spec for spec in expected if spec.split('#', 1)[0] not in excluded}
	included = set(args.include_class)
	if included:
		expected = {spec for spec in expected if spec.split('#', 1)[0] in included}
	if not expected:
		raise SystemExit('No expected instrumentation tests remain after filtering')

	text = args.output.read_text(errors='replace').replace('\r', '')
	current: str | None = None
	seen: set[str] = set()
	executed = skipped = failures = errors = 0
	status_codes = re.compile(r'^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$')
	for line in text.splitlines():
		if line.startswith('INSTRUMENTATION_STATUS: class='):
			current = line.split('=', 1)[1]
		elif line.startswith('INSTRUMENTATION_STATUS: test=') and current:
			current = f'{current}#{line.split("=", 1)[1]}'
		else:
			match = status_codes.match(line)
			if match is None:
				continue
			code = int(match.group(1))
			if code not in (0, -1, -2, -3, -4):
				continue
			if current is None:
				raise SystemExit(f'Instrumentation result has no test identity: code={code}')
			if current in seen:
				raise SystemExit(f'Duplicate instrumentation result: {current}')
			seen.add(current)
			if current not in expected:
				raise SystemExit(f'Unexpected instrumentation result: {current}')
			if code == 0:
				executed += 1
			elif code in (-3, -4):
				skipped += 1
			elif code == -2:
				failures += 1
			else:
				errors += 1
			current = None

	missing = expected - seen
	print(
		f'STAGE instrumentation-results-reconciled expected={len(expected)} '
		f'executed={executed} skipped={skipped} failures={failures} errors={errors}'
	)
	if missing:
		print(f'MISSING_INSTRUMENTATION_TESTS={len(missing)}')
	if missing or (executed, skipped, failures, errors) != (len(expected), 0, 0, 0):
		return 1
	print(f'INSTRUMENTATION_EXECUTED_TESTS={executed}')
	return 0


if __name__ == '__main__':
	raise SystemExit(main())
