import struct
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

from tools.hgtools import private_res_ids as p
from tools.hgtools.tests.test_shadow_check import _mutf8, _uleb128

# Device framework-res as `aapt2 dump resources` prints it (the parts the tool reads).
DEVICE_DUMP = """\
Package name=android id=01
  type attr id=01 entryCount=2
    resource 0x01010000 attr/theme PUBLIC
      () (attr) type=reference
  type bool id=11 entryCount=3
    resource 0x01110001 bool/config_private
      () true
    resource 0x01110002 bool/config_other
      () false
    resource 0x01110003 bool/config_allowed
      () true
  type color id=06 entryCount=1
    resource 0x01060220 color/customColorSurfaceEffect1
      () @color/system_surface_effect_1_light
      (night) @color/system_surface_effect_1_dark
"""

PUBLIC_XML = """<resources>
  <public type="attr" name="theme" id="0x01010000" />
  <staging-public-group-final type="bool" first-id="0x01fd0000">
    <public name="removed_old" />
    <public name="config_staged" />
  </staging-public-group-final>
</resources>
"""


def _const(value: int, reg: int = 0) -> list:
    """const vReg, #value (format 31i: 3 code units)."""
    return [(reg << 8) | 0x14, value & 0xFFFF, (value >> 16) & 0xFFFF]


def _const_high16(value: int, reg: int = 0) -> list:
    return [(reg << 8) | 0x15, (value >> 16) & 0xFFFF]


def _fill_array_payload(values: list) -> list:
    units = [0x0300, 4, len(values) & 0xFFFF, len(values) >> 16]
    for v in values:
        units += [v & 0xFFFF, (v >> 16) & 0xFFFF]
    return units


def _sparse_switch_payload(keys: list) -> list:
    units = [0x0200, len(keys)]
    for k in keys + [0] * len(keys):  # keys, then (ignored) targets
        units += [k & 0xFFFF, (k >> 16) & 0xFFFF]
    return units


RETURN_VOID = [0x000E]


def _build_dex(methods: dict, static_ints: dict = None) -> bytes:
    """One class `LFix;` with direct methods {name: [code units]} and static int fields
    {name: value} (initial values in static_values). Only the tables the parser reads are filled;
    magic/checksum/signature/proto_ids are not."""
    static_ints = static_ints or {}
    strings = ["LFix;"] + list(methods) + list(static_ints)
    method_names = {n: 1 + i for i, n in enumerate(methods)}
    field_names = {n: 1 + len(methods) + i for i, n in enumerate(static_ints)}

    header_size = 0x70
    string_ids_off = header_size
    type_ids_off = string_ids_off + 4 * len(strings)
    field_ids_off = type_ids_off + 4
    method_ids_off = field_ids_off + 8 * len(static_ints)
    class_defs_off = method_ids_off + 8 * len(methods)
    data_off = class_defs_off + 32

    data = bytearray()

    def place(blob: bytes, align: int = 1) -> int:
        while (data_off + len(data)) % align:
            data.append(0)
        off = data_off + len(data)
        data.extend(blob)
        return off

    string_offs = [place(_uleb128(len(s)) + _mutf8(s) + b"\x00") for s in strings]
    code_offs = []
    for units in methods.values():
        code = struct.pack("<HHHHII", 1, 0, 0, 0, 0, len(units))
        code += b"".join(struct.pack("<H", u) for u in units)
        code_offs.append(place(code, align=4))
    class_data = _uleb128(len(static_ints)) + _uleb128(0) + _uleb128(len(methods)) + _uleb128(0)
    for i in range(len(static_ints)):
        class_data += _uleb128(0 if i == 0 else 1) + _uleb128(0x19)  # field_idx diff, flags
    for i, off in enumerate(code_offs):
        class_data += _uleb128(0 if i == 0 else 1) + _uleb128(0x9) + _uleb128(off)
    class_data_off = place(class_data)
    static_values_off = 0
    if static_ints:
        arr = _uleb128(len(static_ints))
        for v in static_ints.values():
            arr += bytes([(3 << 5) | 0x04]) + struct.pack("<i", v - (1 << 32) if v >= 1 << 31
                                                         else v)
        static_values_off = place(arr)

    header = bytearray(header_size)
    struct.pack_into("<II", header, 0x38, len(strings), string_ids_off)
    struct.pack_into("<II", header, 0x40, 1, type_ids_off)
    struct.pack_into("<II", header, 0x50, len(static_ints), field_ids_off)
    struct.pack_into("<II", header, 0x58, len(methods), method_ids_off)
    struct.pack_into("<II", header, 0x60, 1, class_defs_off)
    tables = b"".join(struct.pack("<I", o) for o in string_offs)
    tables += struct.pack("<I", 0)  # type 0 -> "LFix;"
    tables += b"".join(struct.pack("<HHI", 0, 0, field_names[n]) for n in static_ints)
    tables += b"".join(struct.pack("<HHI", 0, 0, method_names[n]) for n in methods)
    tables += struct.pack("<IIIIIIII", 0, 1, 0xFFFFFFFF, 0, 0xFFFFFFFF, 0, class_data_off,
                          static_values_off)
    return bytes(header) + tables + bytes(data)


def _values(dex: bytes) -> list:
    return [(c.value, c.where, c.how) for c in p.dex_int_constants(dex)]


class DexConstantsTest(unittest.TestCase):
    def test_const_and_const_high16(self):
        dex = _build_dex({"a": _const(0x01110001) + _const_high16(0x01120000) + RETURN_VOID})
        self.assertEqual(_values(dex), [
            (0x01110001, "LFix;->a", "const"),
            (0x01120000, "LFix;->a", "const/high16"),
        ])

    def test_instruction_widths_do_not_misread_operands(self):
        # const/16 v0, #0x0014 has an operand unit that looks like a `const` opcode; const-wide's
        # 64-bit operand holds a resource-shaped half. Neither is an int constant.
        units = [0x0013, 0x0014] + [0x0018, 0x0001, 0x0111, 0, 0] + _const(0x01110002)
        dex = _build_dex({"m": units + RETURN_VOID})
        self.assertEqual(_values(dex), [(0x01110002, "LFix;->m", "const")])

    def test_payloads(self):
        units = ([0x0026, 4, 0] + RETURN_VOID + _fill_array_payload([0x01010000, 0x01110001])
                 + _sparse_switch_payload([0x01110003, 7]))
        dex = _build_dex({"p": units})
        self.assertEqual(_values(dex), [
            (0x01010000, "LFix;->p", "array-data"),
            (0x01110001, "LFix;->p", "array-data"),
            (0x01110003, "LFix;->p", "switch"),
            (7, "LFix;->p", "switch"),
        ])

    def test_static_values_are_named_by_field(self):
        dex = _build_dex({"x": RETURN_VOID}, {"FOO": 0x01110001, "BAR": 5})
        self.assertIn((0x01110001, "LFix;.FOO", "static-value"), _values(dex))
        self.assertIn((5, "LFix;.BAR", "static-value"), _values(dex))

    def test_several_methods(self):
        dex = _build_dex({"a": _const(1) + RETURN_VOID, "b": _const(0x01110002) + RETURN_VOID})
        self.assertEqual(_values(dex), [(1, "LFix;->a", "const"),
                                        (0x01110002, "LFix;->b", "const")])


class PublicIdsTest(unittest.TestCase):
    def test_explicit_and_staged_ids(self):
        ids = p.parse_public_ids(PUBLIC_XML)
        self.assertEqual(ids[0x01010000], ("attr", "theme"))
        # removed_ placeholders keep their slot
        self.assertEqual(ids[0x01fd0001], ("bool", "config_staged"))

    def test_shape(self):
        self.assertTrue(p.is_framework_res_id(0x01110001))
        self.assertFalse(p.is_framework_res_id(0x01000000))  # type 0: e.g. 1 << 24
        self.assertFalse(p.is_framework_res_id(0x7F110001))  # the app's own


class AllowlistTest(unittest.TestCase):
    def _load(self, text):
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "a.txt"
            f.write_text(text, encoding="utf-8")
            return p.load_allowlist(f)

    def test_format(self):
        entries = self._load("# comment\n\n0x01110003 | bool/config_allowed | dex | why\n"
                             "0x01060220 | color/customColorSurfaceEffect1 | res | xml ref\n")
        self.assertEqual(entries, [
            p.AllowEntry(0x01110003, "bool/config_allowed", "dex", "why"),
            p.AllowEntry(0x01060220, "color/customColorSurfaceEffect1", "res", "xml ref"),
        ])

    def test_rejects_bad_lines(self):
        for bad in ("0x01110003 | bool/x | dex\n",            # no reason
                    "0x01110003 | bool/x | code | why\n",     # bad where
                    "0x01110003 | config_x | dex | why\n",    # no type
                    "zz | bool/x | dex | why\n"):             # bad id
            with self.assertRaises(ValueError, msg=bad):
                self._load(bad)


class CheckTest(unittest.TestCase):
    def setUp(self):
        self.by_name = p.framework_ids_by_name(DEVICE_DUMP)
        self.public = p.parse_public_ids(PUBLIC_XML)

    def _consts(self, *values):
        return [p.DexConst(v, "LFix;->m", "const") for v in values]

    def test_private_id_fails_with_name_and_location(self):
        status, lines = p.check(self._consts(0x01110001), self.public, [], self.by_name,
                                "app.apk", "allow.txt")
        self.assertEqual(status, 1)
        self.assertIn("  0x01110001 bool/config_private", lines)
        self.assertIn("      LFix;->m (const)", lines)

    def test_public_non_resource_and_allowlisted_pass(self):
        allow = [p.AllowEntry(0x01110003, "bool/config_allowed", "dex", "why")]
        # public attr; 0x010ea442 is resource-shaped but no device resource (a hash, say);
        # 0x01000000 is a flag; 0x01110003 allowlisted.
        consts = self._consts(0x01010000, 0x010EA442, 0x01000000, 0x01110003)
        status, lines = p.check(consts, self.public, allow, self.by_name, "app.apk", "a.txt")
        self.assertEqual(status, 0, lines)
        self.assertIn("2 framework resource ID constants", lines[-1])

    def test_res_entries_do_not_allow_dex_constants(self):
        allow = [p.AllowEntry(0x01060220, "color/customColorSurfaceEffect1", "res", "xml")]
        status, _ = p.check(self._consts(0x01060220), self.public, allow, self.by_name,
                            "app.apk", "a.txt")
        self.assertEqual(status, 1)

    def test_moved_allowlist_entry_fails(self):
        allow = [p.AllowEntry(0x01110009, "bool/config_allowed", "dex", "why")]
        status, lines = p.check([], self.public, allow, self.by_name, "app.apk", "a.txt")
        self.assertEqual(status, 1)
        self.assertIn("  bool/config_allowed: listed 0x01110009, now 0x01110003", lines)


    def test_res_entry_must_also_match_the_sdk(self):
        # aapt2 links the XML ref to android.jar's ID; the device must have the same one.
        allow = [p.AllowEntry(0x01060220, "color/customColorSurfaceEffect1", "res", "xml")]
        sdk_same = {"color/customColorSurfaceEffect1": 0x01060220}
        sdk_other = {"color/customColorSurfaceEffect1": 0x0106021F}
        status, _ = p.check([], self.public, allow, self.by_name, "app.apk", "a.txt", sdk_same)
        self.assertEqual(status, 0)
        status, lines = p.check([], self.public, allow, self.by_name, "app.apk", "a.txt",
                                sdk_other)
        self.assertEqual(status, 1)
        self.assertIn("  color/customColorSurfaceEffect1: listed 0x01060220, now 0x0106021f",
                      lines)


class CompareTest(unittest.TestCase):
    def test_match_moved_missing(self):
        by_name = p.framework_ids_by_name(DEVICE_DUMP)
        entries = [p.AllowEntry(0x01060220, "color/customColorSurfaceEffect1", "res", "r"),
                   p.AllowEntry(0x01110002, "bool/config_allowed", "dex", "r"),
                   p.AllowEntry(0x01110005, "bool/gone", "dex", "r")]
        self.assertEqual(p.compare(entries, by_name), [
            "bool/config_allowed: listed 0x01110002, now 0x01110003",
            "bool/gone: listed 0x01110005, not in this framework-res",
        ])
        self.assertEqual(p.compare(entries[:1], by_name), [])


class MainTest(unittest.TestCase):
    """The CLI end to end over a fixture APK, SDK platform and allowlist (aapt2 mocked)."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        d = Path(self.tmp.name)
        values = d / "platform" / "data" / "res" / "values"
        values.mkdir(parents=True)
        (values / "public-final.xml").write_text(PUBLIC_XML, encoding="utf-8")
        self.platform = d / "platform"
        self.allow = d / "allow.txt"
        self.allow.write_text("0x01060220 | color/customColorSurfaceEffect1 | res | xml\n",
                              encoding="utf-8")
        self.d = d

    def tearDown(self):
        self.tmp.cleanup()

    def _apk(self, units) -> Path:
        apk = self.d / "app.apk"
        with zipfile.ZipFile(apk, "w") as z:
            z.writestr("classes.dex", _build_dex({"m": units + RETURN_VOID}))
            z.writestr("classes2.dex", _build_dex({"n": _const(0x01010000) + RETURN_VOID}))
        return apk

    def _main(self, *args, dump=DEVICE_DUMP, sdk_dump=DEVICE_DUMP):
        def fake_dump(apk, _aapt2):
            return sdk_dump if Path(apk).name == "android.jar" else dump
        with mock.patch.object(p, "_dump", side_effect=fake_dump), \
                mock.patch("builtins.print") as out:
            status = p.main(list(args) + ["--aapt2", "aapt2", "--allowlist", str(self.allow)])
        return status, "\n".join(str(c.args[0]) for c in out.call_args_list)

    def test_check_clean(self):
        status, out = self._main("--check", str(self._apk(_const(1))),
                                 "--sdk-platform", str(self.platform), "--device-res", "fw.apk")
        self.assertEqual(status, 0, out)

    def test_check_private(self):
        status, out = self._main("--check", str(self._apk(_const(0x01110002))),
                                 "--sdk-platform", str(self.platform), "--device-res", "fw.apk")
        self.assertEqual(status, 1)
        self.assertIn("0x01110002 bool/config_other", out)

    def test_check_res_entry_against_sdk(self):
        moved = DEVICE_DUMP.replace("0x01060220 color/", "0x01060230 color/")
        status, out = self._main("--check", str(self._apk(_const(1))),
                                 "--sdk-platform", str(self.platform), "--device-res", "fw.apk",
                                 sdk_dump=moved)
        self.assertEqual(status, 1, out)
        self.assertIn("SDK android.jar", out)

    def _framework_res(self) -> str:
        fw = self.d / "framework-res-new.apk"
        fw.write_bytes(b"not read: aapt2 is mocked")
        return str(fw)

    def test_compare(self):
        fw = self._framework_res()
        status, out = self._main("--compare", fw)
        self.assertEqual((status, out.startswith("IDs still match")), (0, True), out)
        moved = DEVICE_DUMP.replace("0x01060220 color/", "0x01060230 color/")
        status, out = self._main("--compare", fw, dump=moved)
        self.assertEqual(status, 1)
        self.assertIn("color/customColorSurfaceEffect1: listed 0x01060220, now 0x01060230", out)

    def test_compare_missing_file_says_how_to_pull_it(self):
        missing = str(self.d / "framework-res-new.apk")
        with mock.patch.object(p, "_dump") as dump:
            status, out = self._main("--compare", missing)
        self.assertEqual(status, 1)
        self.assertIn(f"{missing} not found", out)
        self.assertIn("adb pull /system/framework/framework-res.apk", out)
        dump.assert_not_called()

    def test_compare_aapt2_failure_reports_its_stderr(self):
        fw = self._framework_res()
        err = subprocess.CalledProcessError(1, ["aapt2"], stderr="error: failed to load apk")
        with mock.patch("subprocess.run", side_effect=err), \
                mock.patch("builtins.print") as out:
            status = p.main(["--compare", fw, "--aapt2", "aapt2", "--allowlist", str(self.allow)])
        self.assertEqual(status, 1)
        message = out.call_args.args[0]
        self.assertIn("aapt2 could not read", message)
        self.assertIn("failed to load apk", message)


if __name__ == "__main__":
    unittest.main()
