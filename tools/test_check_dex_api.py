from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile


class CheckDexApiTest(unittest.TestCase):
    def test_integer_and_minor_api_versions(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            api_xml = root / "platforms/android-36/data/api-versions.xml"
            api_xml.parent.mkdir(parents=True)
            api_xml.write_text("""<api>
                <class name="java/example/Available" since="1">
                    <method name="old()V" since="26"/>
                    <method name="minor()V" since="26.1"/>
                </class>
                <class name="java/example/New" since="36.1">
                    <method name="inheritedVersion()V"/>
                </class>
            </api>""")
            dexdump = root / "build-tools/36.0.0/dexdump"
            dexdump.parent.mkdir(parents=True)
            dexdump.write_text("""#!/bin/sh
echo 'Ljava/example/Available;.old:()V'
echo 'Ljava/example/Available;.minor:()V'
echo 'Ljava/example/New;.inheritedVersion:()V'
""")
            dexdump.chmod(0o755)
            apk = root / "test.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("classes.dex", b"fixture")

            checker = Path(__file__).with_name("check-dex-api.py")
            for min_sdk, expected in ((26, ("26.1", "36.1")), (36, ("36.1",))):
                with self.subTest(min_sdk=min_sdk):
                    result = subprocess.run(
                        [sys.executable, str(checker), str(apk), str(min_sdk), str(root)],
                        capture_output=True, text=True,
                    )
                    self.assertEqual(result.returncode, 1, result.stderr)
                    for version in expected:
                        self.assertIn(f"API {version}", result.stdout)
                    self.assertNotIn("Available.old", result.stdout)
                    if min_sdk == 36:
                        self.assertNotIn("Available.minor", result.stdout)

            result = subprocess.run(
                [sys.executable, str(checker), str(apk), "37", str(root)],
                capture_output=True, text=True,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("OK", result.stdout)


if __name__ == "__main__":
    unittest.main()
