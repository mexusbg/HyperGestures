"""No-boot-classpath-shadowing build check (spec §5.1).

The APK must never bundle a class that is also present on the device's boot classpath
(prebuilts/device/framework-classes.txt) or in the SDK 37 android.jar the build compiles against:
a definition inside our own dex would shadow (or be shadowed by) the platform's copy at class-load
time. Classes are read straight out of the dex `class_defs` table (which class_idx points into
`type_ids`, which string_idx points into `string_ids`) rather than via dexdump, which is not
assumed to be on PATH.

Separately, `com.android.extensions.appfunctions` is a device shared library our own
:appfunctions-stubs module provides compile-only stand-ins for (hg/appfunctions-stubs); the device
supplies the real classes through an optional <uses-library>, never framework.jar, so they would
never show up in framework-classes.txt/android.jar and a plain boot-set intersection would miss a
regression that accidentally packages them. Bundling any class under that package is always wrong,
independent of the boot set.
"""
import argparse
import re
import struct
import sys
import zipfile
from pathlib import Path

# A device shared library, never on the boot classpath (see module docstring): forbidden
# regardless of what's in boot_classes.
_FORBIDDEN_PREFIXES = ("com/android/extensions/appfunctions/",)

_DEX_NAME = re.compile(r"classes\d*\.dex")

# Header field offsets (all little-endian uint32).
_STRING_IDS_SIZE = 0x38
_STRING_IDS_OFF = 0x3C
_TYPE_IDS_OFF = 0x44
_CLASS_DEFS_SIZE = 0x60
_CLASS_DEFS_OFF = 0x64
_CLASS_DEF_ITEM_SIZE = 32


def _u32(data: bytes, off: int) -> int:
    return struct.unpack_from("<I", data, off)[0]


def _read_uleb128(data: bytes, offset: int) -> tuple[int, int]:
    result = 0
    shift = 0
    i = offset
    while True:
        b = data[i]
        i += 1
        result |= (b & 0x7F) << shift
        if b & 0x80 == 0:
            return result, i
        shift += 7


def _decode_mutf8(data: bytes, offset: int) -> str:
    """Decodes a NUL-terminated MUTF-8 string (dex string_data_item) starting at offset."""
    chars = []
    i = offset
    while True:
        b0 = data[i]
        if b0 == 0:
            return "".join(chars)
        if b0 & 0x80 == 0:
            chars.append(chr(b0))
            i += 1
        elif b0 & 0xE0 == 0xC0:
            b1 = data[i + 1]
            chars.append(chr(((b0 & 0x1F) << 6) | (b1 & 0x3F)))
            i += 2
        elif b0 & 0xF0 == 0xE0:
            b1, b2 = data[i + 1], data[i + 2]
            chars.append(chr(((b0 & 0x0F) << 12) | ((b1 & 0x3F) << 6) | (b2 & 0x3F)))
            i += 3
        else:
            # Not produced by dex2jar/d8 output; skip defensively rather than raising.
            chars.append(chr(0xFFFD))
            i += 1


def _dex_class_names(data: bytes) -> set[str]:
    string_ids_off = _u32(data, _STRING_IDS_OFF)
    type_ids_off = _u32(data, _TYPE_IDS_OFF)
    class_defs_size = _u32(data, _CLASS_DEFS_SIZE)
    class_defs_off = _u32(data, _CLASS_DEFS_OFF)

    def string_at(idx: int) -> str:
        string_data_off = _u32(data, string_ids_off + idx * 4)
        _, start = _read_uleb128(data, string_data_off)
        return _decode_mutf8(data, start)

    def type_descriptor(type_idx: int) -> str:
        string_idx = _u32(data, type_ids_off + type_idx * 4)
        return string_at(string_idx)

    names: set[str] = set()
    for i in range(class_defs_size):
        class_idx = _u32(data, class_defs_off + i * _CLASS_DEF_ITEM_SIZE)
        descriptor = type_descriptor(class_idx)
        if descriptor.startswith("L") and descriptor.endswith(";"):
            names.add(descriptor[1:-1])
    return names


def apk_classes(apk: Path) -> set[str]:
    """Every class defined (not merely referenced) across every classes*.dex in the APK."""
    classes: set[str] = set()
    with zipfile.ZipFile(apk) as z:
        for name in z.namelist():
            if _DEX_NAME.fullmatch(name):
                classes |= _dex_class_names(z.read(name))
    return classes


def jar_classes(jar: Path) -> set[str]:
    """Class names (no `.class` suffix) of every class file in a plain jar, e.g. android.jar."""
    with zipfile.ZipFile(jar) as z:
        return {n[: -len(".class")] for n in z.namelist() if n.endswith(".class")}


def boot_classes(framework_classes_txt: Path, android_jar: Path) -> set[str]:
    lines = {
        line.strip()
        for line in framework_classes_txt.read_text().splitlines()
        if line.strip()
    }
    return lines | jar_classes(android_jar)


def find_shadowed(apk_classes: set[str], boot_classes: set[str]) -> list[str]:
    shadowed = apk_classes & boot_classes
    forbidden = {c for c in apk_classes if c.startswith(_FORBIDDEN_PREFIXES)}
    return sorted(shadowed | forbidden)


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", type=Path, required=True)
    ap.add_argument("--framework-classes", type=Path, required=True)
    ap.add_argument("--android-jar", type=Path, required=True)
    a = ap.parse_args(argv)

    offenders = find_shadowed(apk_classes(a.apk), boot_classes(a.framework_classes, a.android_jar))
    if offenders:
        for name in offenders:
            print(name)
        print(f"{len(offenders)} shadowed classes", file=sys.stderr)
        return 1
    print("0 shadowed classes")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
