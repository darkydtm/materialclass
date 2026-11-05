import importlib.util
import shutil
import subprocess
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

	def test_rejects_unreviewed_properties_and_invalid_values(self):
		original = SOURCE.read_text(encoding="utf-8")
		mutations = (
			original + "unexpected=value\n",
			original.replace("distributionBase=GRADLE_USER_HOME", "distributionBase=PROJECT"),
			original.replace("distributionPath=wrapper/dists", "distributionPath=../../outside"),
			original.replace("zipStoreBase=GRADLE_USER_HOME", "zipStoreBase=PROJECT"),
			original.replace("zipStorePath=wrapper/dists", "zipStorePath=../../outside"),
			original.replace("networkTimeout=10000", "networkTimeout=0"),
			original + "distributionBase=GRADLE_USER_HOME\n",
			original + "malformed\n",
		)
		with tempfile.TemporaryDirectory() as directory:
			path = Path(directory) / "gradle-wrapper.properties"
			for mutation in mutations:
				with self.subTest(mutation=mutation):
					path.write_text(mutation, encoding="utf-8")
					with self.assertRaises(ValueError):
						MODULE.check(path)

	def test_rejects_symlink_properties(self):
		with tempfile.TemporaryDirectory() as directory:
			path = Path(directory) / "gradle-wrapper.properties"
			path.symlink_to(SOURCE)
			with self.assertRaises(ValueError):
				MODULE.check(path)

	def test_workflow_checksum_command_accepts_original_and_rejects_tampering(self):
		root = HERE.parent.parent
		with tempfile.TemporaryDirectory() as directory:
			work = Path(directory)
			jar = work / "gradle/wrapper/gradle-wrapper.jar"
			jar.parent.mkdir(parents=True)
			shutil.copyfile(root / jar.relative_to(work), jar)
			for workflow in (root / ".github/workflows").glob("*.yml"):
				for line in workflow.read_text(encoding="utf-8").splitlines():
					line = line.strip()
					if line.startswith("printf ") and "gradle-wrapper.jar | sha256sum --check" in line:
						with self.subTest(workflow=workflow):
							self.assertEqual(subprocess.run(["bash", "-c", line], cwd=work, capture_output=True).returncode, 0)
							jar.write_bytes(jar.read_bytes() + b"tampered")
							self.assertNotEqual(subprocess.run(["bash", "-c", line], cwd=work, capture_output=True).returncode, 0)
							shutil.copyfile(root / jar.relative_to(work), jar)


if __name__ == "__main__":
	unittest.main()
