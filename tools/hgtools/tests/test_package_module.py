import contextlib
import io
import tempfile
import unittest
import zipfile
from pathlib import Path
from tools.hgtools import package_module
from tools.hgtools.package_module import PackagingError

ROOT = Path(__file__).resolve().parents[3]
TEMPLATE = ROOT / "module" / "template"
FP = "Xiaomi/nezha_eea/nezha:17/CP2A.260605.016/OS4.0.0.11.XPAEUXM:user/release-keys"
NOTIF_APK = "system/priv-app/HyperGesturesNotif/HyperGesturesNotif.apk"
NOTIF_XML = "system/etc/permissions/privapp-permissions-com.hypergestures.notif.xml"
NOTIF_ENTRIES = ["AndroidManifest.xml", "classes.dex", "META-INF/xposed/java_init.list",
                 "META-INF/xposed/module.prop", "META-INF/xposed/scope.list"]


class PackageModuleTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.apk = self.tmp / "app.apk"
        self.write_zip(self.apk, ["AndroidManifest.xml", "classes.dex", "resources.arsc"])
        self.overlay = self.tmp / "overlay.apk"
        self.write_overlay(["AndroidManifest.xml", "resources.arsc"])
        self.notif = self.tmp / "notif.apk"
        self.write_zip(self.notif, NOTIF_ENTRIES)
        self.out = self.tmp / "out.zip"

    @staticmethod
    def write_zip(path, entries, data=b"x"):
        with zipfile.ZipFile(path, "w") as z:
            for name in entries:
                z.writestr(name, data)

    def write_overlay(self, entries):
        self.write_zip(self.overlay, entries)

    def build(self, perms=("android.permission.MONITOR_INPUT",), notif_perms=()):
        package_module.build_zip(TEMPLATE, self.apk, self.overlay, list(perms),
                                 "com.hypergestures", "0.1.0-probe", 1, self.out, FP,
                                 self.notif, list(notif_perms))
        return zipfile.ZipFile(self.out)

    def test_layout(self):
        names = set(self.build().namelist())
        for expected in [
            "module.prop",
            "service.sh", "post-fs-data.sh", "uninstall.sh", "action.sh", "restore.sh", "setconf.sh",
            "customize.sh", "common/functions.sh", "common/hypergestures-guard.sh",
            "config.default",
            "system/priv-app/HyperGestures/HyperGestures.apk",
            "system/etc/permissions/privapp-permissions-com.hypergestures.xml",
            NOTIF_APK, NOTIF_XML,
            "system/etc/sysconfig/hypergestures-hiddenapi.xml",
            "overlay/HyperGesturesOverlay.apk",
            "expected-fingerprint",
        ]:
            self.assertIn(expected, names)

    def test_license_files_ship_at_the_root(self):
        # PolyForm Noncommercial: whoever gets a copy gets the terms and the Required Notice;
        # Apache-2.0 4(a)/(d): the AOSP license and NOTICE travel with the APKs built from it.
        z = self.build()
        for name in package_module.LEGAL_FILES:
            self.assertEqual(z.read(name), (package_module.ROOT / name).read_bytes(), name)
        self.assertIn(b"Required Notice:", z.read("LICENSE"))

    def test_system_tree_and_no_payload(self):
        # The standard KernelSU system/ tree, for the phone's metamodule to mount (mixed mode, what
        # MOUNT=auto picks with an active metamodule); self mode stacks the same tree from
        # post-fs-data. No second copy ships.
        names = self.build().namelist()
        self.assertEqual([n for n in names if n.startswith("payload/")], [])
        self.assertIn("system/priv-app/HyperGestures/HyperGestures.apk", names)

    def test_module_prop_has_version(self):
        prop = self.build().read("module.prop").decode()
        self.assertIn("id=hypergestures\n", prop)
        self.assertIn("version=0.1.0-probe\n", prop)
        self.assertIn("versionCode=1\n", prop)

    def test_scripts_are_lf_and_executable(self):
        z = self.build()
        for name in ["service.sh", "post-fs-data.sh", "uninstall.sh", "action.sh", "restore.sh",
                     "setconf.sh", "customize.sh", "common/functions.sh", "common/hypergestures-guard.sh"]:
            self.assertNotIn(b"\r\n", z.read(name), name)
            mode = (z.getinfo(name).external_attr >> 16) & 0o777
            self.assertEqual(mode & 0o755, 0o755, name)

    def test_customize_sh_is_packaged_at_the_module_root(self):
        # KernelSU's installer sources $MODPATH/customize.sh; it sets skip_mount for the mount mode.
        data = self.build().read("customize.sh")
        self.assertIn(b"skip_mount", data)
        self.assertNotIn(b"\r", data)
        self.assertNotRegex(data.decode(), r"(?m)^\s*exit")  # sourced: an exit would end the installer

    def test_package_refuses_a_crlf_script(self):
        tpl = self.tmp / "tpl"
        (tpl / "common").mkdir(parents=True)
        for f in TEMPLATE.rglob("*"):
            if f.is_file():
                dst = tpl / f.relative_to(TEMPLATE)
                dst.parent.mkdir(parents=True, exist_ok=True)
                dst.write_bytes(f.read_bytes())
        (tpl / "customize.sh").write_bytes(b"ui_print hi\r\n")
        with self.assertRaises(PackagingError):
            package_module.build_zip(tpl, self.apk, self.overlay, ["android.permission.MONITOR_INPUT"],
                                     "com.hypergestures", "0.1.0-probe", 1, self.out, FP,
                                     self.notif)
        self.assertFalse(self.out.exists())

    def test_every_template_script_is_packaged_executable(self):
        scripts = {p.relative_to(TEMPLATE).as_posix() for p in TEMPLATE.rglob("*.sh")}
        self.assertEqual(scripts - package_module.SCRIPTS, set())

    def test_no_post_fs_data_d_guard_ships(self):
        # 0.3.5's /data/adb/post-fs-data.d guard (for the fabricated overlay) is gone; post-fs-data
        # removes an installed copy (hg_cleanup_fab_overlay).
        names = self.build().namelist()
        self.assertNotIn("common/hypergestures-overlay-guard.sh", names)
        self.assertNotIn("common/hypergestures-overlay-guard.sh", package_module.SCRIPTS)

    def test_system_tree_is_exactly_apks_allowlists_and_hiddenapi_config(self):
        # The overlay APK is not in system/: a metamodule mounting it hides it from WebView's
        # zygote (KernelSU kernel_umount) and breaks every WebView. post-fs-data self-mounts it.
        # The notification hooks' APK sits next to the app in the same priv-app tree, so it is
        # mounted and unmounted with it.
        names = [n for n in self.build().namelist() if n.startswith("system/")]
        self.assertEqual(sorted(names), [
            "system/etc/permissions/privapp-permissions-com.hypergestures.notif.xml",
            "system/etc/permissions/privapp-permissions-com.hypergestures.xml",
            "system/etc/sysconfig/hypergestures-hiddenapi.xml",
            "system/priv-app/HyperGestures/HyperGestures.apk",
            NOTIF_APK,
        ])
        self.assertFalse(any("overlay" in n.lower() for n in names), names)

    def test_overlay_apk_ships_outside_system_as_is(self):
        z = self.build()
        overlays = [n for n in z.namelist() if n.lower().endswith(".apk") and "overlay" in n.lower()]
        self.assertEqual(overlays, ["overlay/HyperGesturesOverlay.apk"])
        self.assertEqual(package_module.OVERLAY_ENTRY, "overlay/HyperGesturesOverlay.apk")
        self.assertEqual(z.read("overlay/HyperGesturesOverlay.apk"), self.overlay.read_bytes())
        self.assertEqual((z.getinfo("overlay/HyperGesturesOverlay.apk").external_attr >> 16) & 0o777, 0o644)
        # Never where a metamodule looks: under system/ or a partition directory at the root.
        for prefix in ("system/", "product/", "vendor/", "system_ext/", "odm/"):
            self.assertFalse(any(n.startswith(prefix) and n.endswith("Overlay.apk") for n in z.namelist()), prefix)

    def test_package_refuses_overlay_with_code(self):
        self.write_overlay(["AndroidManifest.xml", "classes.dex", "resources.arsc"])
        with self.assertRaises(PackagingError):
            self.build()
        self.assertFalse(self.out.exists())

    def test_package_refuses_an_overlay_that_is_not_a_zip(self):
        self.overlay.write_bytes(b"not a zip")
        with self.assertRaises(PackagingError):
            self.build()
        self.assertFalse(self.out.exists())

    def test_package_refuses_missing_overlay(self):
        self.overlay.unlink()
        with self.assertRaises(PackagingError):
            self.build()
        self.assertFalse(self.out.exists())

    def test_command_line_requires_the_overlay(self):
        err = io.StringIO()
        with self.assertRaises(SystemExit), contextlib.redirect_stderr(err):
            package_module.main(["--template", str(TEMPLATE), "--apk", str(self.apk),
                                 "--version", "1", "--version-code", "1", "--out", str(self.out)])
        self.assertIn("--overlay", err.getvalue())
        self.assertFalse(self.out.exists())

    def test_privapp_xml_covers_permissions(self):
        z = self.build(["android.permission.MONITOR_INPUT", "android.permission.STATUS_BAR_SERVICE"])
        xml = z.read("system/etc/permissions/privapp-permissions-com.hypergestures.xml").decode()
        self.assertIn('name="android.permission.MONITOR_INPUT"', xml)
        self.assertIn('name="android.permission.STATUS_BAR_SERVICE"', xml)

    def test_package_refuses_when_permission_missing(self):
        # Simulate a generator bug: the rendered XML omits a requested permission.
        original = package_module.privapp.render_xml
        package_module.privapp.render_xml = lambda pkg, perms: original(pkg, perms[:-1])
        try:
            with self.assertRaises(PackagingError):
                self.build(["android.permission.MONITOR_INPUT", "android.permission.STATUS_BAR_SERVICE"])
        finally:
            package_module.privapp.render_xml = original
        self.assertFalse(self.out.exists())

    def test_expected_fingerprint_is_packaged(self):
        z = self.build()
        self.assertEqual(z.read("expected-fingerprint").decode(), FP + "\n")
        self.assertEqual((z.getinfo("expected-fingerprint").external_attr >> 16) & 0o777, 0o644)

    def test_package_refuses_empty_fingerprint(self):
        with self.assertRaises(PackagingError):
            package_module.build_zip(TEMPLATE, self.apk, self.overlay, ["android.permission.MONITOR_INPUT"],
                                     "com.hypergestures", "0.1.0-probe", 1, self.out, "  ",
                                     self.notif)
        self.assertFalse(self.out.exists())

    def test_device_build_stamp_is_read(self):
        stamp = self.tmp / "device-build.txt"
        stamp.write_text(f"# comment\nfingerprint={FP}\nframework.jar.sha256=ab\n")
        self.assertEqual(package_module.read_fingerprint(stamp), FP)
        stamp.write_text("# no fingerprint line\nframework.jar.sha256=ab\n")
        with self.assertRaises(PackagingError):
            package_module.read_fingerprint(stamp)

    def test_committed_device_build_stamp_names_the_source_device(self):
        self.assertEqual(package_module.read_fingerprint(ROOT / "tools/framework-stub/device-build.txt"), FP)

    def test_package_refuses_missing_apk(self):
        self.apk.unlink()
        with self.assertRaises(PackagingError):
            self.build()

    # --- the notification hooks' own APK (the LSPosed module) ---------------------------------
    def test_notif_apk_ships_as_is_with_its_own_allowlist(self):
        z = self.build(notif_perms=["android.permission.FOO"])
        self.assertEqual(z.read(NOTIF_APK), self.notif.read_bytes())
        self.assertEqual((z.getinfo(NOTIF_APK).external_attr >> 16) & 0o777, 0o644)
        self.assertEqual(package_module.NOTIF_ENTRY, NOTIF_APK)
        xml = z.read(NOTIF_XML).decode()
        self.assertIn('<privapp-permissions package="com.hypergestures.notif">', xml)
        self.assertIn('name="android.permission.FOO"', xml)
        app_xml = z.read("system/etc/permissions/privapp-permissions-com.hypergestures.xml").decode()
        self.assertNotIn("FOO", app_xml)

    def test_notif_allowlist_without_permissions_is_empty(self):
        xml = self.build().read(NOTIF_XML).decode()
        self.assertIn('<privapp-permissions package="com.hypergestures.notif">', xml)
        self.assertNotIn("<permission ", xml)

    def test_package_refuses_an_app_that_is_still_an_lsposed_module(self):
        # LSPosed would copy the app's whole dex into SystemUI and the AOD again.
        for marker in ("META-INF/xposed/java_init.list", "assets/xposed_init"):
            self.write_zip(self.apk, ["AndroidManifest.xml", "classes.dex", marker])
            with self.assertRaises(PackagingError, msg=marker):
                self.build()
            self.assertFalse(self.out.exists())

    def test_package_refuses_a_notif_apk_that_is_no_module(self):
        self.write_zip(self.notif, ["AndroidManifest.xml", "classes.dex"])
        with self.assertRaises(PackagingError):
            self.build()
        self.assertFalse(self.out.exists())

    def test_package_refuses_a_notif_apk_without_code(self):
        self.write_zip(self.notif, [n for n in NOTIF_ENTRIES if not n.endswith(".dex")])
        with self.assertRaises(PackagingError):
            self.build()
        self.assertFalse(self.out.exists())

    def test_package_refuses_a_big_notif_dex(self):
        with zipfile.ZipFile(self.notif, "w") as z:
            for name in NOTIF_ENTRIES:
                z.writestr(name, b"x")
            z.writestr("classes2.dex", bytes(package_module.NOTIF_DEX_MAX))
        with self.assertRaises(PackagingError):
            self.build()
        self.assertFalse(self.out.exists())

    def test_package_refuses_missing_or_broken_notif_apk(self):
        self.notif.write_bytes(b"not a zip")
        with self.assertRaises(PackagingError):
            self.build()
        self.notif.unlink()
        with self.assertRaises(PackagingError):
            self.build()
        self.assertFalse(self.out.exists())

    def test_command_line_requires_the_notif_apk(self):
        err = io.StringIO()
        with self.assertRaises(SystemExit), contextlib.redirect_stderr(err):
            package_module.main(["--template", str(TEMPLATE), "--apk", str(self.apk),
                                 "--overlay", str(self.overlay),
                                 "--version", "1", "--version-code", "1", "--out", str(self.out)])
        self.assertIn("--notif-apk", err.getvalue())
        self.assertFalse(self.out.exists())


if __name__ == "__main__":
    unittest.main()
