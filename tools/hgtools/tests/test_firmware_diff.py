import unittest
from pathlib import Path

from tools.hgtools import firmware_diff as fd

FIXTURES = Path(__file__).parent / "fixtures" / "firmware_diff"

# `dexdump` (no -d) excerpt: static TRANSACTION_ fields and declared members.
CODES_OLD = """
Class #0            -
  Class descriptor  : 'Lcom/android/wm/shell/sosc/ISoScSplitScreen$Stub;'
  Static fields     -
    #0              : (in Lcom/android/wm/shell/sosc/ISoScSplitScreen$Stub;)
      name          : 'TRANSACTION_startTask'
      type          : 'I'
      access        : 0x0018 (STATIC FINAL)
      value         : 8
    #1              : (in Lcom/android/wm/shell/sosc/ISoScSplitScreen$Stub;)
      name          : 'TRANSACTION_startTasks'
      type          : 'I'
      access        : 0x0018 (STATIC FINAL)
      value         : 11
  Instance fields   -
Class #1            -
  Class descriptor  : 'Landroid/os/IVold$Stub;'
  Static fields     -
    #0              : (in Landroid/os/IVold$Stub;)
      name          : 'TRANSACTION_fstrim'
      type          : 'I'
      access        : 0x0018 (STATIC FINAL)
      value         : 28
  Instance fields   -
"""
CODES_NEW = (CODES_OLD.replace("value         : 8", "value         : 9")
             .replace("value         : 28", "value         : 32"))

# `dexdump -d` excerpt: a Proxy method's Parcel writes.
PROXY_OLD = (FIXTURES / "proxy_old.txt").read_text(encoding="utf-8")
PROXY_NEW = PROXY_OLD.replace(
    "|0009: invoke-virtual {v0, v3}, Landroid/os/Parcel;.writeInt:(I)V // method@0001\n", "", 1)

RES_OLD = """Package name=android id=01
  type dimen id=05 entryCount=2
    resource 0x010501ec dimen/importance_ring_stroke_width
    resource 0x010501ed dimen/other
"""
RES_NEW = RES_OLD.replace("0x010501ec dimen/importance_ring_stroke_width",
                          "0x010501f0 dimen/importance_ring_stroke_width")

DEFS_OLD = """
  Class descriptor  : 'Landroid/app/ActivityTaskManager;'
  Direct methods    -
    #0              : (in Landroid/app/ActivityTaskManager;)
      name          : 'getService'
      type          : '()Landroid/app/IActivityTaskManager;'
  Virtual methods   -
    #0              : (in Landroid/app/ActivityTaskManager;)
      name          : 'removeTask'
      type          : '(I)Z'
  Instance fields   -
    #0              : (in Landroid/app/ActivityTaskManager;)
      name          : 'mHidden'
      type          : 'I'
"""
DEFS_NEW = DEFS_OLD.replace("      name          : 'removeTask'\n      type          : '(I)Z'\n",
                            "")
APK_DUMP = (FIXTURES / "apk_dump.txt").read_text(encoding="utf-8")


class FirmwareDiffTest(unittest.TestCase):
    def test_transaction_codes_parsed(self):
        codes = fd.transaction_codes(CODES_OLD)
        self.assertEqual(codes[("Lcom/android/wm/shell/sosc/ISoScSplitScreen$Stub;",
                                "startTask")], 8)
        self.assertEqual(codes[("Landroid/os/IVold$Stub;", "fstrim")], 28)

    def test_code_changes_only_in_wanted_interfaces(self):
        changes = fd.code_changes(fd.transaction_codes(CODES_OLD),
                                  fd.transaction_codes(CODES_NEW), fd.WANTED)
        self.assertEqual(changes, ["ISoScSplitScreen.startTask: 8 -> 9"])

    def test_code_removed_is_reported_added_is_not(self):
        old = fd.transaction_codes(CODES_OLD)
        new = dict(old)
        del new[("Lcom/android/wm/shell/sosc/ISoScSplitScreen$Stub;", "startTasks")]
        new[("Lcom/android/wm/shell/sosc/ISoScSplitScreen$Stub;", "xiaomiNew")] = 30
        self.assertEqual(fd.code_changes(old, new, fd.WANTED),
                         ["ISoScSplitScreen.startTasks: 11 -> removed"])

    def test_parcel_orders_wanted_proxies_only(self):
        orders = fd.parcel_orders(PROXY_OLD, fd.WANTED)
        self.assertEqual(list(orders), [("Lcom/android/wm/shell/sosc/ISoScSplitScreen$Stub$Proxy;",
                                         "startTask")])
        self.assertEqual(orders[next(iter(orders))],
                         ["writeInterfaceToken", "writeInt", "writeInt", "writeTypedObject"])

    def test_layout_change_detected(self):
        self.assertEqual(fd.layout_changes(fd.parcel_orders(PROXY_OLD, fd.WANTED),
                                           fd.parcel_orders(PROXY_NEW, fd.WANTED)),
                         ["ISoScSplitScreen$Stub$Proxy.startTask: writeInterfaceToken, writeInt,"
                          " writeInt, writeTypedObject -> writeInterfaceToken, writeInt,"
                          " writeTypedObject"])

    def test_layout_unchanged_is_quiet(self):
        o = fd.parcel_orders(PROXY_OLD, fd.WANTED)
        self.assertEqual(fd.layout_changes(o, o), [])

    def test_resource_moves(self):
        self.assertEqual(fd.resource_moves(fd.resource_ids(RES_OLD), fd.resource_ids(RES_NEW)),
                         ["dimen/importance_ring_stroke_width: 0x010501ec -> 0x010501f0"])

    def test_hidden_ref_missing_in_new_firmware(self):
        refs = fd.referenced_members(APK_DUMP)
        self.assertIn("Landroid/app/ActivityTaskManager;.removeTask:(I)Z", refs)
        missing = fd.missing_hidden_refs(refs, fd.defined_members(DEFS_OLD),
                                         fd.defined_members(DEFS_NEW))
        self.assertEqual(missing, ["Landroid/app/ActivityTaskManager;.removeTask:(I)Z"])

    def test_hidden_refs_present_or_unknown_are_quiet(self):
        refs = fd.referenced_members(APK_DUMP)
        defs = fd.defined_members(DEFS_OLD)
        self.assertEqual(fd.missing_hidden_refs(refs, defs, defs), [])
        self.assertNotIn("Ljava/lang/Object;.hashCode:()I",
                         fd.missing_hidden_refs(refs, set(), set()))

    def test_wanted_covers_the_app_interfaces(self):
        for cls in ("Lcom/android/wm/shell/sosc/ISoScSplitScreen$Stub;",
                    "Lcom/android/wm/shell/multitasking/common/IMultiTaskingStateManager$Stub;",
                    "Lcom/android/wm/shell/recents/IRecentTasks$Stub$Proxy;",
                    "Lcom/android/systemui/shared/recents/ILauncherProxy$Stub$Proxy;"):
            self.assertTrue(fd.WANTED.match(cls), cls)
        self.assertFalse(fd.WANTED.match("Landroid/os/IVold$Stub;"))


    def test_coverage_names_interfaces_without_proxy_methods(self):
        orders = fd.parcel_orders(PROXY_OLD, fd.WANTED)
        gaps = fd.coverage_gaps(orders, ("Lcom/android/wm/shell/sosc/ISoScSplitScreen;",
                                         "Lcom/android/systemui/shared/recents/ILauncherProxy;"))
        self.assertEqual(gaps, ["ILauncherProxy: no Proxy methods found (names removed by R8?),"
                                " layout not compared"])

    def test_coverage_lists_the_systemui_interfaces(self):
        self.assertIn("Lcom/android/systemui/shared/recents/ILauncherProxy;", fd.COVERAGE)
        self.assertIn("Lcom/android/wm/shell/sosc/ISoScSplitScreen;", fd.COVERAGE)


if __name__ == "__main__":
    unittest.main()
