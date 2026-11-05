import re
import sys


def backup(lines, package):
	state = 0
	pm_seen = False
	for line in lines:
		if state == 0 and line == 'Running incremental backup for 1 requested packages.':
			state = 1
		elif state == 1 and line == 'Package @pm@ with result: Success' and not pm_seen:
			pm_seen = True
		elif state == 1 and re.fullmatch(r'Package ' + re.escape(package) + r' with progress: [0-9]+/[0-9]+', line):
			continue
		elif state == 1 and line == f'Package {package} with result: Success':
			state = 2
		elif state == 2 and line == 'Backup finished with result: Success':
			state = 3
		else:
			return False
	return state == 3


def restore(lines, package, api):
	index = 0 if api == 24 else 1
	return lines == ['restoreStarting: 1 packages', f'onUpdate: {index} = {package}', 'restoreFinished: 0', 'done']


def main():
	mode, package, api = sys.argv[1:]
	lines = sys.stdin.read().splitlines()
	if mode == 'backup':
		valid = backup(lines, package)
	elif mode == 'restore':
		valid = restore(lines, package, int(api))
	else:
		valid = False
	if not valid:
		raise SystemExit('Invalid bmgr output')


if __name__ == '__main__':
	main()
