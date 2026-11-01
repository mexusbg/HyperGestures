import os
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from tools.hgtools import sdk


class SdkTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.root = self.tmp / "repo"
        self.root.mkdir()
        self.sdk = self.tmp / "sdk"
        env = {k: v for k, v in os.environ.items() if k not in ("AAPT2", "ANDROID_HOME", "ANDROID_SDK_ROOT")}
        patcher = mock.patch.dict(os.environ, env, clear=True)
        patcher.start()
        self.addCleanup(patcher.stop)

    def tool(self, version: str, name: str = "aapt2.exe") -> Path:
        p = self.sdk / "build-tools" / version / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(b"")
        return p

    def test_local_properties_sdk_dir_wins_and_newest_build_tools_is_used(self):
        self.tool("9.0.0")
        self.tool("36.0.0")
        newest = self.tool("37.0.0", "aapt2")
        (self.tmp / "other").mkdir()
        os.environ["ANDROID_HOME"] = str(self.tmp / "other")
        (self.root / "local.properties").write_text(f"sdk.dir={self.sdk.as_posix()}\n",
                                                    encoding="utf-8")
        self.assertEqual(sdk.find_aapt2(self.root), newest)

    def test_escaped_windows_sdk_dir(self):
        text = "sdk.dir=" + str(self.sdk).replace("\\", "\\\\").replace(":", "\\:") + "\n"
        (self.root / "local.properties").write_text(text, encoding="utf-8")
        self.assertEqual(sdk.sdk_dir(self.root), self.sdk)

    def test_android_home_without_local_properties(self):
        expected = self.tool("35.0.0")
        os.environ["ANDROID_HOME"] = str(self.sdk)
        self.assertEqual(sdk.find_aapt2(self.root), expected)

    def test_aapt2_environment_variable_overrides(self):
        self.tool("37.0.0")
        os.environ["AAPT2"] = str(self.tmp / "custom-aapt2")
        self.assertEqual(sdk.find_aapt2(self.root), self.tmp / "custom-aapt2")

    def test_missing_sdk_is_an_error(self):
        with self.assertRaises(FileNotFoundError):
            sdk.find_aapt2(self.root)


if __name__ == "__main__":
    unittest.main()
