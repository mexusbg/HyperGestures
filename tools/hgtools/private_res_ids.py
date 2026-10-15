"""No-firmware-specific-framework-resource-ID build check (docs/research/private-resource-ids.md).

The app compiles against the device's own framework.jar (converted to a stub), so `javac`/`kotlinc`
inline every `com.android.internal.R.<type>.<name>` (and any stub-only `android.R` field) as that
firmware's numeric ID. Only the IDs the SDK's public*.xml fixes are stable across builds; a private
ID can move on the next HyperOS update and then name a different resource (wrong value) or none
(Resources.NotFoundException). Code must resolve private framework resources by name at runtime
(com.hypergestures.res.HgSysRes, hg/common).

`--check` reads every classes*.dex in the APK and collects the int constants that can be a
framework (package 0x01) resource ID:
  - `const` (31i) and `const/high16` (21h) operands — the only instructions wide enough;
  - `fill-array-data` payloads with 4-byte elements (int[] literals, e.g. styleable arrays);
  - `sparse-switch` keys and `packed-switch` key ranges (`switch (id) { case R.id.x: }`);
  - static field initial values (class_def static_values, VALUE_INT) — `static final int` fields
    R8 kept.
A value counts when it is a resource ID in the device framework-res the stub was made from
(`--device-res`, prebuilts/device/framework-res.apk): exactly the numbers javac could have inlined,
so a hash or bit mask that merely starts with 0x01 does not. Values that SDK 37's public*.xml
assigns are fine (public IDs are fixed). Every other one must be in the allowlist, keyed by ID
with the resource name and a reason, or the check fails. The check also fails when an allowlisted
name no longer has its listed ID in that framework-res, so refreshing prebuilts/device from a new
firmware re-verifies every allowlist entry.

`--compare <framework-res.apk>` is the owner-facing OTA check: it looks up every allowlisted name
in the given framework-res (e.g. `adb pull /system/framework/framework-res.apk`) and prints
"IDs still match" or the entries that moved or disappeared.

Allowlist format (`#` starts a comment):
    <0xID> | <type>/<name> | <where: dex or res> | <reason>
`dex` entries are IDs the dex may contain. `res` entries are private IDs linked into compiled
resources (tools/hgtools/private-refs-allowlist.txt), with the SDK android.jar ID that aapt2
linked: the dex part ignores them; `--check` verifies them against both the device framework-res
and `<sdk-platform>/android.jar`, `--compare` against the given framework-res.
"""
import argparse
import re
import struct
import sys
import xml.etree.ElementTree as ET
import zipfile
from dataclasses import dataclass
from pathlib import Path

from tools.hgtools.shadow_check import _decode_mutf8, _read_uleb128, _u32

_DEX_NAME = re.compile(r"classes\d*\.dex")

# Header offsets (little-endian uint32).
_STRING_IDS_OFF = 0x3C
_TYPE_IDS_OFF = 0x44
_FIELD_IDS_OFF = 0x54
_METHOD_IDS_OFF = 0x5C
_CLASS_DEFS_SIZE = 0x60
_CLASS_DEFS_OFF = 0x64
_CLASS_DEF_ITEM_SIZE = 32

# Code units (16-bit) per instruction, by opcode (Dalvik bytecode formats). 0x00 is special-cased
# for the switch / array payload pseudo-instructions.
_WIDTH = [1] * 256
for _op, _w in {
    0x02: 2, 0x03: 3, 0x05: 2, 0x06: 3, 0x08: 2, 0x09: 3,
    0x13: 2, 0x14: 3, 0x15: 2, 0x16: 2, 0x17: 3, 0x18: 5, 0x19: 2,
    0x1A: 2, 0x1B: 3, 0x1C: 2, 0x1F: 2, 0x20: 2, 0x22: 2, 0x23: 2, 0x24: 3, 0x25: 3, 0x26: 3,
    0x29: 2, 0x2A: 3, 0x2B: 3, 0x2C: 3, 0xFA: 4, 0xFB: 4, 0xFC: 3, 0xFD: 3, 0xFE: 2, 0xFF: 2,
}.items():
    _WIDTH[_op] = _w
for _lo, _hi, _w in ((0x2D, 0x3D, 2), (0x44, 0x72, 2), (0x74, 0x78, 3), (0x90, 0xAF, 2),
                     (0xD0, 0xE2, 2)):
    for _op in range(_lo, _hi + 1):
        _WIDTH[_op] = _w
for _op in range(0x6E, 0x73):  # invoke-kind (35c); 0x73 is unused
    _WIDTH[_op] = 3

_OP_CONST = 0x14
_OP_CONST_HIGH16 = 0x15
_PAYLOAD_PACKED_SWITCH = 0x0100
_PAYLOAD_SPARSE_SWITCH = 0x0200
_PAYLOAD_FILL_ARRAY = 0x0300

_VALUE_INT = 0x04
_VALUE_ARRAY = 0x1C
_VALUE_ANNOTATION = 0x1D
_VALUE_NULL = 0x1E
_VALUE_BOOLEAN = 0x1F


def is_framework_res_id(value: int) -> bool:
    """Package 0x01 with a non-zero type byte: shaped like an android framework resource ID."""
    value &= 0xFFFFFFFF
    return (value >> 24) == 0x01 and (value >> 16) & 0xFF != 0


@dataclass(frozen=True)
class DexConst:
    value: int   # unsigned 32-bit
    where: str   # "Lcom/x/Y;->method" or "Lcom/x/Y;.FIELD"
    how: str     # const | const/high16 | array-data | switch | static-value


class _Dex:
    def __init__(self, data: bytes):
        self.data = data
        self.string_ids_off = _u32(data, _STRING_IDS_OFF)
        self.type_ids_off = _u32(data, _TYPE_IDS_OFF)
        self.field_ids_off = _u32(data, _FIELD_IDS_OFF)
        self.method_ids_off = _u32(data, _METHOD_IDS_OFF)

    def string(self, idx: int) -> str:
        off = _u32(self.data, self.string_ids_off + idx * 4)
        _, start = _read_uleb128(self.data, off)
        return _decode_mutf8(self.data, start)

    def type_name(self, idx: int) -> str:
        return self.string(_u32(self.data, self.type_ids_off + idx * 4))

    def method(self, idx: int) -> str:
        cls, _proto, name = struct.unpack_from("<HHI", self.data, self.method_ids_off + idx * 8)
        return f"{self.type_name(cls)}->{self.string(name)}"

    def field_name(self, idx: int) -> str:
        _cls, _type, name = struct.unpack_from("<HHI", self.data, self.field_ids_off + idx * 8)
        return self.string(name)


def _code_consts(data: bytes, code_off: int, where: str, out: list) -> None:
    insns_size = _u32(data, code_off + 12)
    base = code_off + 16
    pc = 0
    while pc < insns_size:
        unit = struct.unpack_from("<H", data, base + pc * 2)[0]
        op = unit & 0xFF
        if op == 0x00 and unit in (_PAYLOAD_PACKED_SWITCH, _PAYLOAD_SPARSE_SWITCH,
                                   _PAYLOAD_FILL_ARRAY):
            p = base + pc * 2
            if unit == _PAYLOAD_PACKED_SWITCH:
                size = struct.unpack_from("<H", data, p + 2)[0]
                first = _u32(data, p + 4)
                out.extend(DexConst((first + i) & 0xFFFFFFFF, where, "switch")
                           for i in range(size))
                pc += 4 + size * 2
            elif unit == _PAYLOAD_SPARSE_SWITCH:
                size = struct.unpack_from("<H", data, p + 2)[0]
                out.extend(DexConst(_u32(data, p + 4 + i * 4), where, "switch")
                           for i in range(size))
                pc += 2 + size * 4
            else:
                width = struct.unpack_from("<H", data, p + 2)[0]
                size = _u32(data, p + 4)
                if width == 4:
                    out.extend(DexConst(_u32(data, p + 8 + i * 4), where, "array-data")
                               for i in range(size))
                pc += 4 + (size * width + 1) // 2
            continue
        if op == _OP_CONST:
            lo, hi = struct.unpack_from("<HH", data, base + pc * 2 + 2)
            out.append(DexConst(lo | (hi << 16), where, "const"))
        elif op == _OP_CONST_HIGH16:
            hi = struct.unpack_from("<H", data, base + pc * 2 + 2)[0]
            out.append(DexConst(hi << 16, where, "const/high16"))
        pc += _WIDTH[op]


def _encoded_value(data: bytes, off: int, sink) -> int:
    """Walks one encoded_value; calls sink(int) for VALUE_INT. Returns the offset after it."""
    head = data[off]
    off += 1
    vtype, arg = head & 0x1F, head >> 5
    if vtype == _VALUE_ARRAY:
        return _encoded_array(data, off, sink)
    if vtype == _VALUE_ANNOTATION:
        _, off = _read_uleb128(data, off)  # type_idx
        size, off = _read_uleb128(data, off)
        for _ in range(size):
            _, off = _read_uleb128(data, off)  # name_idx
            off = _encoded_value(data, off, lambda _v: None)
        return off
    if vtype in (_VALUE_NULL, _VALUE_BOOLEAN):
        return off
    n = arg + 1
    if vtype == _VALUE_INT:
        raw = int.from_bytes(data[off:off + n], "little", signed=True)
        sink(raw & 0xFFFFFFFF)
    return off + n


def _encoded_array(data: bytes, off: int, sink) -> int:
    size, off = _read_uleb128(data, off)
    for _ in range(size):
        off = _encoded_value(data, off, sink)
    return off


def dex_int_constants(data: bytes) -> list:
    """Every int constant (see module docstring) in one dex file, with where it sits."""
    dex = _Dex(data)
    out: list = []
    class_defs_size = _u32(data, _CLASS_DEFS_SIZE)
    class_defs_off = _u32(data, _CLASS_DEFS_OFF)
    for i in range(class_defs_size):
        cd = class_defs_off + i * _CLASS_DEF_ITEM_SIZE
        cls = dex.type_name(_u32(data, cd))
        class_data_off = _u32(data, cd + 24)
        static_values_off = _u32(data, cd + 28)
        static_fields: list = []
        if class_data_off:
            off = class_data_off
            sizes = []
            for _ in range(4):
                v, off = _read_uleb128(data, off)
                sizes.append(v)
            n_static, n_instance, n_direct, n_virtual = sizes
            idx = 0
            for k in range(n_static + n_instance):
                diff, off = _read_uleb128(data, off)
                _, off = _read_uleb128(data, off)  # access_flags
                idx = diff if k in (0, n_static) else idx + diff
                if k < n_static:
                    static_fields.append(idx)
            for count in (n_direct, n_virtual):
                idx = 0
                for k in range(count):
                    diff, off = _read_uleb128(data, off)
                    _, off = _read_uleb128(data, off)  # access_flags
                    code_off, off = _read_uleb128(data, off)
                    idx = diff if k == 0 else idx + diff
                    if code_off:
                        _code_consts(data, code_off, dex.method(idx), out)
        if static_values_off:
            values: list = []
            _encoded_array_top(data, static_values_off, values)
            for pos, value in values:
                name = dex.field_name(static_fields[pos]) if pos < len(static_fields) else f"#{pos}"
                out.append(DexConst(value, f"{cls}.{name}", "static-value"))
    return out


def _encoded_array_top(data: bytes, off: int, values: list) -> None:
    """static_values: records (field position, int) for top-level VALUE_INT entries, and any ints
    nested in arrays/annotations under the owning field's position."""
    size, off = _read_uleb128(data, off)
    for pos in range(size):
        off = _encoded_value(data, off, lambda v, pos=pos: values.append((pos, v)))


def apk_int_constants(apk: Path) -> list:
    consts: list = []
    with zipfile.ZipFile(apk) as z:
        for name in sorted(z.namelist()):
            if _DEX_NAME.fullmatch(name):
                consts.extend(dex_int_constants(z.read(name)))
    return consts


# --- SDK public IDs ----------------------------------------------------------------------------

def parse_public_ids(xml_text: str) -> dict:
    """{id: (type, name)} a framework public*.xml assigns.

    `<public type= name= id=/>` carries its ID. In a `<staging-public-group[-final] type=…
    first-id=…>` block the n-th `<public name=…/>` child has first-id + n (`removed_*` placeholders
    keep their slot, which is how the file keeps indexes stable).
    """
    ids = {}

    def walk(parent):
        for el in parent:
            if el.tag == "public" and el.get("id"):
                ids[int(el.get("id"), 16)] = (el.get("type"), el.get("name"))
            elif el.tag.startswith("staging-public-group"):
                first, rtype = int(el.get("first-id"), 16), el.get("type")
                n = 0
                for child in el:
                    if child.tag == "public":
                        ids[first + n] = (rtype, child.get("name"))
                        n += 1
            else:
                walk(el)

    walk(ET.fromstring(xml_text))
    return ids


def load_public_ids(sdk_platform: Path) -> dict:
    files = sorted((sdk_platform / "data" / "res" / "values").glob("public*.xml"))
    if not files:
        raise FileNotFoundError(f"no public*.xml under {sdk_platform}/data/res/values")
    ids = {}
    for f in files:
        ids.update(parse_public_ids(f.read_text(encoding="utf-8")))
    return ids


# --- Allowlist ---------------------------------------------------------------------------------

@dataclass(frozen=True)
class AllowEntry:
    id: int
    name: str    # "type/name"
    where: str   # "dex" | "res"
    reason: str


def load_allowlist(path: Path) -> list:
    entries = []
    for n, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = [p.strip() for p in line.split("|")]
        if (len(parts) != 4 or not all(parts) or parts[2] not in ("dex", "res")
                or "/" not in parts[1]):
            raise ValueError(f"{path}:{n}: expected '0xID | type/name | dex|res | reason', "
                             f"got {raw!r}")
        try:
            rid = int(parts[0], 16)
        except ValueError:
            raise ValueError(f"{path}:{n}: bad ID {parts[0]!r}") from None
        entries.append(AllowEntry(rid, parts[1], parts[2], parts[3]))
    return entries


# --- Checks ------------------------------------------------------------------------------------

def find_private(consts, public_ids, allowed_ids, resource_ids=None) -> dict:
    """{id: sorted locations} for constants that are framework resource IDs (in `resource_ids`, the
    device framework-res table, when given; else merely shaped like one) but neither public nor
    allowlisted."""
    bad: dict = {}
    for c in consts:
        v = c.value
        if not is_framework_res_id(v) or v in public_ids or v in allowed_ids:
            continue
        if resource_ids is not None and v not in resource_ids:
            continue  # e.g. a hash or bit mask that happens to start with 0x01
        bad.setdefault(v, set()).add(f"{c.where} ({c.how})")
    return {k: sorted(v) for k, v in sorted(bad.items())}


def framework_ids_by_name(dump_text: str) -> dict:
    """{"type/name": id} from `aapt2 dump resources <framework-res.apk>` (first ID wins;
    `^attr-private` is named attr)."""
    from tools.hgtools.res_preprocess import parse_aapt2_dump
    return {f"{t}/{n}": e.id for (t, n), e in parse_aapt2_dump(dump_text).items()}


def compare(entries, by_name: dict) -> list:
    """Allowlisted entries whose name is missing from, or has another ID in, the framework-res."""
    moved = []
    for e in entries:
        now = by_name.get(e.name)
        if now is None:
            moved.append(f"{e.name}: listed 0x{e.id:08x}, not in this framework-res")
        elif now != e.id:
            moved.append(f"{e.name}: listed 0x{e.id:08x}, now 0x{now:08x}")
    return moved


def check(consts, public_ids, entries, by_name: dict, apk_label: str, allowlist_label: str,
          sdk_by_name: dict = None):
    """The build check. Returns (exit status, report lines). `by_name` is the device
    framework-res; `sdk_by_name`, when given, is android.jar's, which the `res` entries must match
    too (aapt2 links those refs to the SDK's ID)."""
    names = {v: k for k, v in by_name.items()}
    allowed = {e.id for e in entries if e.where == "dex"}
    bad = find_private(consts, public_ids, allowed, set(names))
    lines = []
    if bad:
        lines.append(f"{len(bad)} private framework resource ID(s) are compiled into "
                     f"{apk_label}. They are this firmware's numbering and can move on an OTA: "
                     "resolve them by name at runtime (com.hypergestures.res.HgSysRes) or "
                     f"allowlist them with a reason in {allowlist_label}:")
        for rid, where in bad.items():
            lines.append(f"  0x{rid:08x} {names.get(rid, '')}".rstrip())
            lines.extend(f"      {w}" for w in where)
    moved = compare(entries, by_name)
    if moved:
        lines.append(f"{len(moved)} allowlist entr{'y' if len(moved) == 1 else 'ies'} no longer "
                     "match the device framework-res:")
        lines.extend(f"  {m}" for m in moved)
    if sdk_by_name is not None:
        sdk_moved = compare([e for e in entries if e.where == "res"], sdk_by_name)
        if sdk_moved:
            lines.append(f"{len(sdk_moved)} `res` allowlist entr"
                         f"{'y' if len(sdk_moved) == 1 else 'ies'} no longer match the SDK "
                         "android.jar (aapt2 links them to its IDs):")
            lines.extend(f"  {m}" for m in sdk_moved)
            moved = moved + sdk_moved
    if bad or moved:
        return 1, lines
    n = sum(1 for c in consts if is_framework_res_id(c.value) and c.value in names)
    lines.append(f"private-res-ID check: {n} framework resource ID constants in the dex, all "
                 f"public or allowlisted ({len(entries)} allowlist entries match the device"
                 f"{' and the SDK' if sdk_by_name is not None else ''})")
    return 0, lines


class ToolError(Exception):
    """A failure the user should read as a message, not a traceback."""


def _dump(apk: Path, aapt2: str) -> str:
    import subprocess
    try:
        return subprocess.run([aapt2, "dump", "resources", str(apk)], check=True,
                              capture_output=True, text=True, encoding="utf-8").stdout
    except subprocess.CalledProcessError as e:
        detail = (e.stderr or "").strip() or f"exit status {e.returncode}"
        raise ToolError(f"aapt2 could not read {apk}: {detail}") from None
    except OSError as e:
        raise ToolError(f"cannot run aapt2 ({aapt2}): {e}") from None


_REPO_ROOT = Path(__file__).resolve().parents[2]


def main(argv) -> int:
    try:
        return _main(argv)
    except ToolError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1


def _main(argv) -> int:
    ap = argparse.ArgumentParser()
    mode = ap.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", type=Path, metavar="APK",
                      help="exit 1 on private framework resource IDs in the APK's dex")
    mode.add_argument("--compare", type=Path, metavar="FRAMEWORK_RES_APK",
                      help="check the allowlisted IDs against this framework-res.apk (OTA)")
    mode.add_argument("--list", type=Path, metavar="APK",
                      help="print every framework resource ID constant in the APK's dex (audit)")
    ap.add_argument("--sdk-platform", type=Path, help="e.g. $ANDROID_SDK/platforms/android-37.0")
    ap.add_argument("--device-res", type=Path,
                    help="the framework-res.apk the framework stub was made from "
                         "(prebuilts/device/framework-res.apk)")
    ap.add_argument("--allowlist", type=Path,
                    default=_REPO_ROOT / "tools/hgtools/private-res-ids-allowlist.txt")
    ap.add_argument("--aapt2", help="default: the SDK's newest build-tools aapt2")
    a = ap.parse_args(argv)
    if a.compare and not a.compare.is_file():
        raise ToolError(f"{a.compare} not found - pull it with: "
                        "adb pull /system/framework/framework-res.apk")
    if a.aapt2 is None:
        from tools.hgtools.sdk import find_aapt2
        a.aapt2 = str(find_aapt2(_REPO_ROOT))

    if a.compare:
        entries = load_allowlist(a.allowlist)
        moved = compare(entries, framework_ids_by_name(_dump(a.compare, a.aapt2)))
        if moved:
            print(f"{len(moved)} allowlisted private framework resource ID(s) moved in "
                  f"{a.compare}. Rebuild against this firmware's framework (prebuilts/device) "
                  "and re-verify each entry (docs/research/private-resource-ids.md):")
            for m in moved:
                print(f"  {m}")
            return 1
        print(f"IDs still match ({len(entries)} allowlisted entries, {a.compare})")
        return 0

    if not (a.sdk_platform and a.device_res):
        ap.error("--check/--list need --sdk-platform and --device-res")
    public_ids = load_public_ids(a.sdk_platform)
    by_name = framework_ids_by_name(_dump(a.device_res, a.aapt2))
    apk = a.check or a.list
    consts = apk_int_constants(apk)

    if a.list:
        names = {v: k for k, v in by_name.items()}
        for c in sorted(consts, key=lambda c: (c.value, c.where)):
            if is_framework_res_id(c.value) and c.value in names:
                kind = "public" if c.value in public_ids else "PRIVATE"
                print(f"0x{c.value:08x}\t{kind}\t{names[c.value]}\t{c.where} ({c.how})")
        return 0

    entries = load_allowlist(a.allowlist)
    sdk_by_name = None
    if any(e.where == "res" for e in entries):
        sdk_by_name = framework_ids_by_name(_dump(a.sdk_platform / "android.jar", a.aapt2))
    status, lines = check(consts, public_ids, entries, by_name, str(apk), a.allowlist.name,
                          sdk_by_name)
    print("\n".join(lines))
    return status


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
