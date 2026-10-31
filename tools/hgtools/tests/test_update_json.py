import json
import unittest

from tools.hgtools import update_json


class UpdateJsonTest(unittest.TestCase):
    def test_render_points_at_the_tagged_release(self):
        data = json.loads(update_json.render("0.4.0-beta", 68))
        self.assertEqual(data["version"], "0.4.0-beta")
        self.assertEqual(data["versionCode"], 68)
        self.assertEqual(
            data["zipUrl"],
            "https://github.com/mexusbg/HyperGestures/releases/download/v0.4.0-beta/"
            "HyperGestures-0.4.0-beta.zip")
        self.assertTrue(data["changelog"].endswith("/CHANGELOG.md"))

    def test_committed_update_json_matches_version_properties(self):
        # KernelSU offers an update only when versionCode grows: a release must update both.
        expected = update_json.render(
            *update_json.read_version(update_json.ROOT / "version.properties"))
        self.assertEqual(update_json.UPDATE_JSON.read_text(encoding="utf-8"), expected,
                         "run: python -m tools.hgtools.update_json")

    def test_module_prop_template_names_update_json(self):
        prop = (update_json.ROOT / "module/template/module.prop.in").read_text(encoding="utf-8")
        self.assertIn(f"updateJson={update_json.UPDATE_JSON_URL}\n", prop)

    def test_changelog_is_committed(self):
        self.assertTrue((update_json.ROOT / "CHANGELOG.md").is_file())


if __name__ == "__main__":
    unittest.main()
