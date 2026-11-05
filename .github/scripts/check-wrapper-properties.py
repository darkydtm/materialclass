import hashlib
import re
import stat
import sys
from pathlib import Path

EXPECTED = {
	"distributionUrl": r"https\://services.gradle.org/distributions/gradle-9.5.0-bin.zip",
	"distributionSha256Sum": "553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746",
	"distributionBase": "GRADLE_USER_HOME",
	"distributionPath": "wrapper/dists",
	"zipStoreBase": "GRADLE_USER_HOME",
	"zipStorePath": "wrapper/dists",
	"networkTimeout": "10000",
	"validateDistributionUrl": "true",
}


def regular_file(path):
	path = Path(path)
	if not stat.S_ISREG(path.lstat().st_mode):
		raise ValueError(f"Not a regular file: {path}")
	return path


def check_launch_files(root):
	root = Path(root)
	for name, digest in {
		"gradlew": "45bda8deaef01b732f07bd317f962a736e349c6faa6662829bc0f41293b51212",
		"gradle/wrapper/gradle-wrapper.jar": "497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7",
	}.items():
		path = regular_file(root / name)
		if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
			raise ValueError(f"Unexpected checksum: {name}")


def check(path):
	properties = {}
	for line in regular_file(path).read_text(encoding="utf-8").splitlines():
		line = line.strip()
		if not line or line.startswith(("#", "!")):
			continue
		if "=" not in line:
			raise ValueError("Unsupported wrapper property format")
		key, value = line.split("=", 1)
		if not re.fullmatch(r"[A-Za-z][A-Za-z0-9]*", key):
			raise ValueError("Unsupported wrapper property key")
		if "\\" in value and line != "distributionUrl=" + EXPECTED["distributionUrl"]:
			raise ValueError("Unsupported wrapper property escape")
		if key not in EXPECTED:
			raise ValueError("Unexpected wrapper property")
		if key in properties:
			raise ValueError("Duplicate wrapper property")
		properties[key.strip()] = value.strip()
	for key, expected in EXPECTED.items():
		if properties.get(key) != expected:
			raise ValueError(f"Unexpected {key}")


if __name__ == "__main__":
	try:
		check(sys.argv[1])
		check_launch_files(Path(sys.argv[1]).parent.parent.parent)
	except (ValueError, OSError, IndexError) as error:
		raise SystemExit(str(error))
	print("Gradle wrapper properties match the reviewed distribution")
