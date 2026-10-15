import unittest
from pathlib import Path
from tools.hgtools import privapp

FIX = Path(__file__).parent / "fixtures"


class PrivappTest(unittest.TestCase):
    def test_parse_permissions_sorted_unique_and_foreign_only(self):
        perms = privapp.parse_permissions((FIX / "aapt2_permissions.txt").read_text())
        self.assertEqual(perms, [
            "android.permission.ACCESS_CONTEXTUAL_SEARCH",
            "android.permission.MONITOR_INPUT",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.STATUS_BAR_SERVICE",
        ])  # the app's own permission is not a platform permission and is skipped;
        # uses-permission-sdk-23 lines count like uses-permission lines

    def test_render_xml_lists_every_permission(self):
        xml = privapp.render_xml("com.hypergestures", ["a.B", "c.D"])
        self.assertIn('<privapp-permissions package="com.hypergestures">', xml)
        self.assertIn('<permission name="a.B" />', xml)
        self.assertIn('<permission name="c.D" />', xml)
        self.assertEqual(privapp.missing(xml, ["a.B", "c.D"]), [])

    def test_missing_reports_absent_permissions(self):
        xml = privapp.render_xml("com.hypergestures", ["a.B"])
        self.assertEqual(privapp.missing(xml, ["a.B", "x.Y"]), ["x.Y"])


if __name__ == "__main__":
    unittest.main()
