import importlib.util
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("wrapper_properties", HERE / "check-wrapper-properties.py")
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
SOURCE = HERE.parent.parent / "gradle/wrapper/gradle-wrapper.properties"


class WrapperIntegrityTest(unittest.TestCase):
	def test_reviewed_properties_pass(self):
		MODULE.check(SOURCE)

	def test_tampered_properties_fail(self):
		original = SOURCE.read_text(encoding="utf-8")
		mutations = (
			original.replace("gradle-9.5.0-bin.zip", "gradle-9.5.1-bin.zip"),
			original.replace(MODULE.EXPECTED["distributionSha256Sum"], "0" * 64),
			original.replace("validateDistributionUrl=true", "validateDistributionUrl=false"),
			original + "\ndistributionSha256Sum=" + "0" * 64 + "\n",
			original + r"\u0064istributionUrl=https\://example.com/evil.zip" + "\n",
		)
		with tempfile.TemporaryDirectory() as directory:
			path = Path(directory) / "gradle-wrapper.properties"
			for mutation in mutations:
				with self.subTest(mutation=mutation):
					self.assertNotEqual(original, mutation)
					path.write_text(mutation, encoding="utf-8")
					with self.assertRaises(ValueError):
						MODULE.check(path)


if __name__ == "__main__":
	unittest.main()
