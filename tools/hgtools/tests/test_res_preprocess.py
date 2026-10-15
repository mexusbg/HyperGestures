import unittest
from pathlib import Path

from tools.hgtools import res_preprocess as r


class ResPreprocessTest(unittest.TestCase):
    XML = (
        '<resources xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <color name="a" android:featureFlag="com.x.on">#1</color>\n'
        '    <color name="b" android:featureFlag="com.x.off">#2</color>\n'
        '    <color name="c" android:featureFlag="!com.x.off">#3</color>\n'
        '    <color name="d">#4</color>\n'
        '</resources>\n'
    )

    def test_true_flag_keeps_element_drops_attribute(self):
        out = r.resolve_feature_flags(self.XML, {"com.x.on": True})
        self.assertIn('<color name="a">#1</color>', out)

    def test_false_flag_removes_element(self):
        out = r.resolve_feature_flags(self.XML, {"com.x.on": True})
        self.assertNotIn('name="b"', out)

    def test_negated_false_flag_keeps_element(self):
        out = r.resolve_feature_flags(self.XML, {"com.x.on": True})
        self.assertIn('<color name="c">#3</color>', out)

    def test_untouched_elements_and_formatting_survive(self):
        out = r.resolve_feature_flags(self.XML, {"com.x.on": True})
        self.assertIn('    <color name="d">#4</color>\n', out)
        self.assertTrue(out.startswith('<resources xmlns:android='))

    def test_multiline_element_removed_whole(self):
        xml = ('<manifest>\n    <service android:name=".S"\n        android:featureFlag="f.x">\n'
               '        <intent-filter/>\n    </service>\n    <keep/>\n</manifest>\n')
        out = r.resolve_feature_flags(xml, {})
        self.assertNotIn("service", out)
        self.assertIn("<keep/>", out)

    def test_strip_manifest_package(self):
        xml = '<manifest xmlns:android="x"\n    package="com.android.launcher3">\n</manifest>\n'
        self.assertEqual(r.strip_manifest_package(xml), '<manifest xmlns:android="x">\n</manifest>\n')


class PrivateRefsTest(unittest.TestCase):
    XML = (
        '<resources xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    xmlns:androidprv="http://schemas.android.com/apk/prv/res/android">\n'
        '    <color name="pub">@*android:color/system_accent1_100</color>\n'
        '    <color name="lit">@androidprv:color/system_brand_a_light</color>\n'
        '    <dimen name="d">\n'
        '        @*android:dimen/taskbar_frame_height\n'
        '    </dimen>\n'
        '    <color name="miss">@androidprv:color/not_on_device</color>\n'
        '    <color name="ok">@android:color/system_accent1_200</color>\n'
        '    <!-- <item name="x">@*android:color/in_a_comment</item> -->\n'
        '</resources>\n'
    )
    PUBLIC = {("color", "system_accent1_100"), ("color", "system_accent1_200"),
              ("attr", "colorAccent")}
    DEVICE = {("color", "system_brand_a_light"): "#ff485b88",
              ("dimen", "taskbar_frame_height"): "56dp"}

    def test_find_private_refs_skips_public_refs_namespace_decl_and_comments(self):
        refs = r.find_private_refs(self.XML)
        self.assertEqual(
            [(x.ref, x.type, x.name, x.line) for x in refs],
            [("@*android:color/system_accent1_100", "color", "system_accent1_100", 3),
             ("@androidprv:color/system_brand_a_light", "color", "system_brand_a_light", 4),
             ("@*android:dimen/taskbar_frame_height", "dimen", "taskbar_frame_height", 6),
             ("@androidprv:color/not_on_device", "color", "not_on_device", 8)])

    def test_find_private_refs_theme_attr_forms(self):
        xml = ('<a x="?androidprv:attr/textColorOnAccent" y="?*android:attr/colorAccent"'
               ' z="?android:attr/colorAccent"/>\n')
        refs = r.find_private_refs(xml)
        self.assertEqual([(x.ref, x.type, x.name) for x in refs],
                         [("?androidprv:attr/textColorOnAccent", "attr", "textColorOnAccent"),
                          ("?*android:attr/colorAccent", "attr", "colorAccent")])

    def test_find_private_refs_style_item_key(self):
        xml = '<style name="s">\n  <item name="*android:colorAccentPrimary">#1</item>\n</style>\n'
        refs = r.find_private_refs(xml)
        self.assertEqual([(x.ref, x.type, x.name, x.line) for x in refs],
                         [("*android:colorAccentPrimary", "attr", "colorAccentPrimary", 2)])

    def test_public_name_becomes_public_ref(self):
        out, _ = r.rewrite_private_refs(self.XML, self.PUBLIC, self.DEVICE)
        self.assertIn('<color name="pub">@android:color/system_accent1_100</color>', out)

    def test_non_public_name_becomes_device_literal(self):
        out, _ = r.rewrite_private_refs(self.XML, self.PUBLIC, self.DEVICE)
        self.assertIn('<color name="lit">#ff485b88</color>', out)
        self.assertIn('    <dimen name="d">\n        56dp\n    </dimen>\n', out)

    def test_unknown_ref_left_and_reported_unresolved(self):
        out, report = r.rewrite_private_refs(self.XML, self.PUBLIC, self.DEVICE)
        self.assertIn('@androidprv:color/not_on_device', out)
        self.assertEqual([(e.ref, e.action) for e in report],
                         [("@*android:color/system_accent1_100", "public"),
                          ("@androidprv:color/system_brand_a_light", "literal"),
                          ("@*android:dimen/taskbar_frame_height", "literal"),
                          ("@androidprv:color/not_on_device", "unresolved")])
        self.assertEqual(report[1].replacement, "#ff485b88")

    def test_unresolved_reason_is_reported(self):
        _, report = r.rewrite_private_refs(
            self.XML, self.PUBLIC, self.DEVICE,
            reasons={("color", "not_on_device"): "file-based color state list"})
        self.assertEqual(report[3].reason, "file-based color state list")

    def test_comments_and_other_text_untouched(self):
        out, _ = r.rewrite_private_refs(self.XML, self.PUBLIC, self.DEVICE)
        self.assertIn('@*android:color/in_a_comment', out)
        self.assertIn('<color name="ok">@android:color/system_accent1_200</color>', out)
        self.assertTrue(out.startswith('<resources xmlns:android='))
        self.assertEqual(out.count('\n'), self.XML.count('\n'))

    def test_crlf_preserved(self):
        xml = self.XML.replace('\n', '\r\n')
        out, _ = r.rewrite_private_refs(xml, self.PUBLIC, self.DEVICE)
        self.assertIn('<color name="lit">#ff485b88</color>\r\n', out)
        self.assertNotIn('\n', out.replace('\r\n', ''))

    def test_theme_attr_uses_public_attr_else_unresolved(self):
        xml = '<a x="?androidprv:attr/colorAccent" y="?androidprv:attr/textColorOnAccent"/>\n'
        out, report = r.rewrite_private_refs(
            xml, self.PUBLIC, {("attr", "textColorOnAccent"): "#fff"})
        self.assertEqual(out, '<a x="?android:attr/colorAccent" '
                              'y="?androidprv:attr/textColorOnAccent"/>\n')
        self.assertEqual([(e.action, e.kind) for e in report],
                         [("public", "theme-attr"), ("unresolved", "theme-attr")])

    def test_device_string_literal_is_aapt2_and_xml_escaped(self):
        # aapt2 drops unescaped double quotes from string values and treats a leading @/? as
        # a reference, so those are backslash-escaped; then XML-escaped for the text node.
        xml = '<string name="s">@*android:string/q</string>\n'
        out, _ = r.rewrite_private_refs(xml, set(), {("string", "q"): '@a<b&"c"'})
        self.assertEqual(out, '<string name="s">\\@a&lt;b&amp;\\"c\\"</string>\n')

    def test_literal_in_attribute_value_escapes_quotes(self):
        xml = '<a android:fontFamily="@*android:string/q"/>\n'
        out, _ = r.rewrite_private_refs(xml, set(), {("string", "q"): "x'y"})
        self.assertEqual(out, '<a android:fontFamily="x\\\'y"/>\n')

    def test_reference_to_public_resource_found_via_device_is_reported_as_such(self):
        xml = '<a android:color="@*android:color/accent_device_default_light"/>\n'
        out, report = r.rewrite_private_refs(
            xml, set(), {("color", "accent_device_default_light"):
                         "@android:color/system_accent1_600"})
        self.assertEqual(out, '<a android:color="@android:color/system_accent1_600"/>\n')
        self.assertEqual(report[0].action, "device-public")


class FrameworkResourceParsingTest(unittest.TestCase):
    PUBLIC_FINAL = (
        '<resources>\n'
        '  <public type="attr" name="theme" id="0x01010000" />\n'
        '  <public type="color" name="system_accent1_100" id="0x0106003a" />\n'
        '  <staging-public-group-final type="color" first-id="0x01b80000">\n'
        '    <public name="system_error_0" />\n'
        '    <public name="removed_old_color" />\n'
        '  </staging-public-group-final>\n'
        '  <!-- <public type="dimen" name="commented_out" id="0x01050000" /> -->\n'
        '</resources>\n'
    )
    PUBLIC_STAGING = (
        '<resources>\n'
        '  <staging-public-group type="color" first-id="0x01960000">\n'
        '    <!-- @FlaggedApi(x) -->\n'
        '    <public name="system_error_dim_light"/>\n'
        '  </staging-public-group>\n'
        '</resources>\n'
    )
    DUMP = (
        'Binary APK\n'
        'Package name=android id=01\n'
        '  type attr id=01 entryCount=1\n'
        '    resource 0x01010000 attr/theme PUBLIC\n'
        '      () (attr) type=reference\n'
        '  type string id=04 entryCount=1\n'
        '    resource 0x010402a8 string/config_bodyFontFamilyMedium\n'
        '      () "sans-serif-medium"\n'
        '  type dimen id=05 entryCount=5\n'
        '    resource 0x010503b2 dimen/taskbar_frame_height\n'
        '      () 56.000000dp\n'
        '    resource 0x01050163 dimen/config_wallpaperMaxScale\n'
        '      () 1.1\n'
        '    resource 0x010502a4 dimen/navigation_bar_frame_height\n'
        '      () @dimen/navigation_bar_height\n'
        '    resource 0x010502a0 dimen/navigation_bar_height\n'
        '      () 48.000000dp\n'
        '      (land) 42.500000dp\n'
        '    resource 0x0105036f dimen/rounded_corner_content_padding\n'
        '      () 0.000000dp\n'
        '  type color id=06 entryCount=5\n'
        '    resource 0x0106003a color/system_accent1_100 PUBLIC\n'
        '      () #ffd8e2ff\n'
        '    resource 0x010600ed color/accent_device_default_light\n'
        '      () @color/system_accent1_100\n'
        '    resource 0x01060421 color/system_brand_a_light\n'
        '      () #ff485b88\n'
        '    resource 0x010603b0 color/shade_panel_fg\n'
        '      () @color/shade_panel_fg_color\n'
        '    resource 0x010603b1 color/shade_panel_fg_color\n'
        '      () (file) res/color/shade_panel_fg_color.xml type=XML\n'
        '      (night) (file) res/color-night-v8/shade_panel_fg_color.xml type=XML\n'
        '    resource 0x01060220 color/customColorSurfaceEffect1\n'
        '      () @color/system_brand_a_light\n'
        '      (night) @color/system_brand_b_dark\n'
        '  type ^attr-private id=12 entryCount=1\n'
        '    resource 0x01120106 ^attr-private/textColorOnAccent\n'
        '      () (attr) type=color\n'
        'Package name=android id=01\n'
        '  type color id=96 entryCount=1\n'
        '    resource 0x01960000 color/system_error_dim_light PUBLIC STAGED\n'
        '      () #ff67040d\n'
    )

    def test_parse_public_xml(self):
        names = r.parse_public_xml(self.PUBLIC_FINAL) | r.parse_public_xml(self.PUBLIC_STAGING)
        self.assertEqual(names, {("attr", "theme"), ("color", "system_accent1_100"),
                                 ("color", "system_error_0"),
                                 ("color", "system_error_dim_light")})

    def test_parse_aapt2_dump(self):
        entries = r.parse_aapt2_dump(self.DUMP)
        e = entries[("dimen", "navigation_bar_height")]
        self.assertEqual((e.id, e.public, e.configs), (0x010502a0, False,
                         {"": "48.000000dp", "land": "42.500000dp"}))
        self.assertTrue(entries[("color", "system_error_dim_light")].public)
        self.assertEqual(entries[("attr", "textColorOnAccent")].id, 0x01120106)

    def test_device_values_literals_are_normalized(self):
        values, _ = r.device_values(r.parse_aapt2_dump(self.DUMP), set())
        self.assertEqual(values[("string", "config_bodyFontFamilyMedium")], "sans-serif-medium")
        self.assertEqual(values[("dimen", "taskbar_frame_height")], "56dp")
        self.assertEqual(values[("dimen", "rounded_corner_content_padding")], "0dp")
        self.assertEqual(values[("dimen", "config_wallpaperMaxScale")], "1.1")
        self.assertEqual(values[("color", "system_brand_a_light")], "#ff485b88")

    def test_device_values_follow_reference_to_first_public_resource(self):
        public = {("color", "system_accent1_100")}
        values, _ = r.device_values(r.parse_aapt2_dump(self.DUMP), public)
        self.assertEqual(values[("color", "accent_device_default_light")],
                         "@android:color/system_accent1_100")

    def test_device_values_config_dependent_and_file_values_are_unresolvable(self):
        values, reasons = r.device_values(r.parse_aapt2_dump(self.DUMP), set())
        for key in [("dimen", "navigation_bar_frame_height"), ("color", "shade_panel_fg"),
                    ("color", "customColorSurfaceEffect1"), ("attr", "textColorOnAccent")]:
            self.assertNotIn(key, values)
            self.assertIn(key, reasons)
        self.assertIn("land", reasons[("dimen", "navigation_bar_frame_height")])
        self.assertIn("night", reasons[("color", "customColorSurfaceEffect1")])
        self.assertIn("file", reasons[("color", "shade_panel_fg")])


class CheckPrivateCliTest(unittest.TestCase):
    def setUp(self):
        import tempfile
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        (self.root / "res").mkdir()
        self.clean = self.root / "res" / "clean.xml"
        self.clean.write_text('<a x="@android:color/white"/>\n', encoding="utf-8")
        self.dirty = self.root / "res" / "dirty.xml"
        self.dirty.write_text('<a x="?androidprv:attr/textColorOnAccent"/>\n', encoding="utf-8")

    def tearDown(self):
        self.tmp.cleanup()

    def run_cli(self, *args):
        import contextlib
        import io
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            code = r.main(list(args))
        return code, buf.getvalue()

    def test_clean_files_pass(self):
        code, _ = self.run_cli("--check-private", str(self.clean), "--root", str(self.root))
        self.assertEqual(code, 0)

    def test_remaining_private_ref_fails_and_is_listed(self):
        code, out = self.run_cli("--check-private", str(self.clean), str(self.dirty),
                                 "--root", str(self.root))
        self.assertEqual(code, 1)
        self.assertIn("res/dirty.xml:1: ?androidprv:attr/textColorOnAccent", out)

    def test_directories_are_scanned_for_xml(self):
        code, out = self.run_cli("--check-private", str(self.root / "res"),
                                 "--root", str(self.root))
        self.assertEqual(code, 1)
        self.assertIn("res/dirty.xml", out)

    def test_allowlisted_ref_passes(self):
        allow = self.root / "allow.txt"
        allow.write_text("# comment\n"
                         "res/dirty.xml | ?androidprv:attr/textColorOnAccent | no public attr\n",
                         encoding="utf-8")
        code, _ = self.run_cli("--check-private", str(self.dirty), "--root", str(self.root),
                               "--allowlist", str(allow))
        self.assertEqual(code, 0)

    def test_allowlist_entry_without_reason_is_rejected(self):
        allow = self.root / "allow.txt"
        allow.write_text("res/dirty.xml | ?androidprv:attr/textColorOnAccent |\n",
                         encoding="utf-8")
        with self.assertRaises(ValueError):
            r.load_allowlist(allow)


if __name__ == "__main__":
    unittest.main()
