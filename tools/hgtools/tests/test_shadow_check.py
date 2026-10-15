import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

from tools.hgtools import shadow_check as s


def _uleb128(n: int) -> bytes:
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            break
    return bytes(out)


def _mutf8(text: str) -> bytes:
    """Encodes text as MUTF-8 (NUL -> C0 80; BMP chars only, which is all we need here)."""
    out = bytearray()
    for ch in text:
        cp = ord(ch)
        if cp == 0:
            out += bytes([0xC0, 0x80])
        elif cp < 0x80:
            out.append(cp)
        elif cp < 0x800:
            out.append(0xC0 | (cp >> 6))
            out.append(0x80 | (cp & 0x3F))
        else:
            out.append(0xE0 | (cp >> 12))
            out.append(0x80 | ((cp >> 6) & 0x3F))
            out.append(0x80 | (cp & 0x3F))
    return bytes(out)


def _build_dex(descriptors: list[str]) -> bytes:
    """A minimal, hand-built dex: one string/type/class_def per descriptor (class_idx == its own
    type_idx == its own string_idx), enough to exercise header parsing, MUTF-8 decoding and the
    type-descriptor -> class-name mapping. Checksum/signature/magic are not filled in: the parser
    never reads them."""
    n = len(descriptors)
    header_size = 0x70
    string_ids_off = header_size
    type_ids_off = string_ids_off + n * 4
    class_defs_off = type_ids_off + n * 4
    data_off = class_defs_off + n * 32

    encoded = [_mutf8(d) for d in descriptors]
    string_data_offsets = []
    cursor = data_off
    string_data_blob = bytearray()
    for e in encoded:
        string_data_offsets.append(cursor)
        blob = _uleb128(len(e)) + e + b"\x00"
        string_data_blob += blob
        cursor += len(blob)

    header = bytearray(header_size)
    struct.pack_into("<I", header, 0x38, n)  # string_ids_size
    struct.pack_into("<I", header, 0x3C, string_ids_off)
    struct.pack_into("<I", header, 0x40, n)  # type_ids_size
    struct.pack_into("<I", header, 0x44, type_ids_off)
    struct.pack_into("<I", header, 0x60, n)  # class_defs_size
    struct.pack_into("<I", header, 0x64, class_defs_off)

    string_ids = b"".join(struct.pack("<I", off) for off in string_data_offsets)
    type_ids = b"".join(struct.pack("<I", i) for i in range(n))  # descriptor_idx == i
    class_defs = b"".join(
        struct.pack("<IIIIIIII", i, 0, 0, 0, 0xFFFFFFFF, 0, 0, 0) for i in range(n)
    )
    return bytes(header) + string_ids + type_ids + class_defs + bytes(string_data_blob)


def _write_apk(path: Path, dex_by_name: dict[str, bytes]) -> None:
    with zipfile.ZipFile(path, "w") as z:
        for name, data in dex_by_name.items():
            z.writestr(name, data)


class ShadowCheckTest(unittest.TestCase):
    def test_shadow_check_detects_duplicate(self):
        apk = {"com/android/launcher3/Launcher", "android/view/RemoteAnimationTarget"}
        boot = {"android/view/RemoteAnimationTarget", "android/app/Activity"}
        self.assertEqual(s.find_shadowed(apk, boot), ["android/view/RemoteAnimationTarget"])

    def test_clean(self):
        self.assertEqual(s.find_shadowed({"a/B"}, {"c/D"}), [])

    def test_relocated_flags_are_not_shadowing(self):
        boot = {"com/android/internal/hidden_from_bootclasspath/com/android/window/flags/Flags"}
        self.assertEqual(s.find_shadowed({"com/android/window/flags/Flags"}, boot), [])

    def test_appfunctions_stub_classes_are_never_allowed(self):
        # aosp/appfunctions-stubs: compile-only stubs for the device shared library
        # com.android.extensions.appfunctions. They must never end up in the dex, even if the
        # boot set (built from framework-classes.txt + android.jar) doesn't happen to list them —
        # framework.jar does not carry this shared library, so a plain boot-set intersection
        # would miss it.
        apk = {"com/android/extensions/appfunctions/AppFunctionService",
               "com/android/launcher3/Launcher"}
        boot = {"android/app/Activity"}  # deliberately does not contain the appfunctions class
        self.assertEqual(s.find_shadowed(apk, boot),
                         ["com/android/extensions/appfunctions/AppFunctionService"])


class ApkClassesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())

    def test_parses_class_defs_from_a_single_dex(self):
        dex = _build_dex(["Lcom/android/launcher3/Launcher;", "Landroid/view/CaféWidget;"])
        apk = self.tmp / "app.apk"
        _write_apk(apk, {"classes.dex": dex})
        self.assertEqual(s.apk_classes(apk),
                         {"com/android/launcher3/Launcher", "android/view/CaféWidget"})

    def test_multiple_dex_files_are_unioned(self):
        dex1 = _build_dex(["Lcom/a/One;"])
        dex2 = _build_dex(["Lcom/b/Two;"])
        apk = self.tmp / "app.apk"
        _write_apk(apk, {"classes.dex": dex1, "classes2.dex": dex2, "resources.arsc": b"x"})
        self.assertEqual(s.apk_classes(apk), {"com/a/One", "com/b/Two"})

    def test_only_defined_classes_count_not_merely_referenced_types(self):
        # type_ids/string_ids may carry referenced-only type descriptors; class_defs marks which
        # are actually implemented in this dex. Only those are "bundled".
        dex = _build_dex(["Lcom/a/Defined;"])
        apk = self.tmp / "app.apk"
        _write_apk(apk, {"classes.dex": dex})
        self.assertEqual(s.apk_classes(apk), {"com/a/Defined"})


class JarClassesTest(unittest.TestCase):
    def test_reads_class_entries_from_a_plain_jar(self):
        tmp = Path(tempfile.mkdtemp())
        jar = tmp / "android.jar"
        with zipfile.ZipFile(jar, "w") as z:
            z.writestr("android/app/Activity.class", b"x")
            z.writestr("META-INF/MANIFEST.MF", b"x")
        self.assertEqual(s.jar_classes(jar), {"android/app/Activity"})


if __name__ == "__main__":
    unittest.main()
