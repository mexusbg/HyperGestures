import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from tools.hgtools import privapp

FIX = Path(__file__).parent / "fixtures"


class PrivappTest(unittest.TestCase):
    def test_parse_permissions_sorted_unique_and_foreign_only(self):
        text = (FIX / "aapt2_permissions.txt").read_text(encoding="utf-8")
        perms = privapp.parse_permissions(text)
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
        self.assertEqual(privapp.missing(xml, "com.hypergestures", ["a.B", "c.D"]), [])

    def test_missing_reports_absent_permissions(self):
        xml = privapp.render_xml("com.hypergestures", ["a.B"])
        self.assertEqual(privapp.missing(xml, "com.hypergestures", ["a.B", "x.Y"]), ["x.Y"])

    def test_missing_reads_the_xml_as_the_framework_does(self):
        # Granted only inside the package's own <privapp-permissions> block: not in a comment,
        # not to another package, not under another root element.
        xml = ('<?xml version="1.0" encoding="utf-8"?><permissions>'
               '<!-- <permission name="a.B" /> -->'
               '<privapp-permissions package="com.other"><permission name="c.D" />'
               '</privapp-permissions>'
               '<privapp-permissions package="com.hypergestures"><permission name="e.F" />'
               '</privapp-permissions></permissions>')
        self.assertEqual(privapp.missing(xml, "com.hypergestures", ["a.B", "c.D", "e.F"]),
                         ["a.B", "c.D"])
        wrong_root = (privapp.render_xml("com.hypergestures", ["a.B"])
                      .replace("<permissions>", "<perms>").replace("</permissions>", "</perms>"))
        self.assertEqual(privapp.missing(wrong_root, "com.hypergestures", ["a.B"]), ["a.B"])
        with self.assertRaises(ET.ParseError):
            privapp.missing("<permissions>", "com.hypergestures", ["a.B"])


if __name__ == "__main__":
    unittest.main()
