import re
import sys
from pathlib import Path

EXPECTED = {
	"distributionUrl": r"https\://services.gradle.org/distributions/gradle-9.5.0-bin.zip",
	"distributionSha256Sum": "553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746",
	"validateDistributionUrl": "true",
}


def check(path):
	properties = {}
	for line in Path(path).read_text(encoding="utf-8").splitlines():
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
		if key.strip() in properties:
			raise ValueError("Duplicate wrapper property")
		properties[key.strip()] = value.strip()
	for key, expected in EXPECTED.items():
		if properties.get(key) != expected:
			raise ValueError(f"Unexpected {key}")


if __name__ == "__main__":
	try:
		check(sys.argv[1])
	except (ValueError, OSError, IndexError) as error:
		raise SystemExit(str(error))
	print("Gradle wrapper properties match the reviewed distribution")
