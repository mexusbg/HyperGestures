"""After a HyperOS OTA: what changed that HyperGestures depends on.

Compares two firmware folders (the files tools/pull-firmware.sh pulls from the phone, as in
prebuilts/device/firmware-OS4.0.0.13/) and reports, for the binder interfaces the app talks to
(WANTED: HyperOS Shell's SoSc split, multitasking state, recents, split screen and the other Shell
AIDL the APK compiles in, plus SystemUI's ILauncherProxy / ISystemUiProxy):

- codes: TRANSACTION_<method> constants that changed or went away (added ones are HyperOS
  additions and harmless);
- layouts: the order of Parcel reads/writes in each interface's generated Proxy methods, the
  witness of the parcel layout both sides use (SystemUI's own interfaces have no names left, so
  this is the only check for them);
- resources: framework resource IDs that moved (the APK compiles some private ones in);
- hidden APIs (with --apk): framework members the built APK references that the old firmware
  defines and the new one no longer does (a NoSuchMethodError waiting to happen).

The app itself follows the firmware for its hand-written HyperOS calls (HgShellInterfaces); this
is the developer's check for everything compiled in. Exit status 1 when anything relevant changed.

    python -m tools.hgtools.firmware_diff OLD_DIR NEW_DIR [--apk out/.../HyperGestures.apk]
"""
import argparse
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

from tools.hgtools import sdk

ROOT = Path(__file__).resolve().parents[2]

# Interface classes (and their $Stub / $Stub$Proxy) the app depends on.
WANTED = re.compile(
    r"^Lcom/android/wm/shell/(sosc|multitasking/common|recents|splitscreen|back|shared"
    r"|desktopmode|startingsurface|common/pip|draganddrop|onehanded|bubbles)/I[A-Za-z]+(\$|;)"
    r"|^Lcom/android/systemui/shared/recents/I(LauncherProxy|SystemUiProxy)(\$|;)")

# Interfaces whose layout coverage is reported: the hand-written HyperOS calls (HgShellCalls) and
# SystemUI's own two, whose Proxies the device's R8 renames (then not compared, and said so).
COVERAGE = (
    "Lcom/android/wm/shell/sosc/ISoScSplitScreen;",
    "Lcom/android/wm/shell/multitasking/common/IMultiTaskingStateManager;",
    "Lcom/android/wm/shell/multitasking/common/IMultiTaskingStateChangeListener;",
    "Lcom/android/wm/shell/recents/IRecentsAnimationRunner;",
    "Lcom/android/systemui/shared/recents/ILauncherProxy;",
    "Lcom/android/systemui/shared/recents/ISystemUiProxy;",
)

# Code that holds the interfaces (Shell, SystemUI) and the framework the APK compiles against.
INTERFACE_FILES = ("Miui-WindowManager-Shell.jar", "MiuiSystemUI.apk")
FRAMEWORK_FILES = ("framework.jar", "services.jar", "miui-framework.jar", "miui-services.jar")

_CLASS = re.compile(r"\s*Class descriptor\s*:\s*'(.*)'")
_NAME = re.compile(r"\s*name\s*:\s*'(.*)'")
_TYPE = re.compile(r"\s*type\s*:\s*'(.*)'")
_VALUE = re.compile(r"\s*value\s*:\s*(-?\d+)")
_PARCEL_CALL = re.compile(r"invoke-\w+(?:/range)? \{[^}]*\}, Landroid/os/Parcel;\.(\w+):")
_CREATOR = re.compile(r"sget-object [^,]+, (L[\w/$]+;)\.CREATOR")
_REF = re.compile(r"(L[\w/$]+;)\.([\w$<>]+):(\S+) // (?:method|field)@")
_RES = re.compile(r"\s*resource (0x[0-9a-f]{8}) (\S+)")


def _short(cls: str) -> str:
    return cls[cls.rfind("/") + 1:].rstrip(";")


def transaction_codes(dump: str) -> dict[tuple[str, str], int]:
    """(class, method) -> code, from `dexdump`'s static TRANSACTION_<method> fields."""
    codes: dict[tuple[str, str], int] = {}
    cls = name = None
    for line in dump.splitlines():
        if m := _CLASS.match(line):
            cls, name = m.group(1), None
        elif m := _NAME.match(line):
            name = m.group(1) if m.group(1).startswith("TRANSACTION_") else None
        elif (m := _VALUE.match(line)) and cls and name:
            codes[(cls, name[len("TRANSACTION_"):])] = int(m.group(1))
            name = None
    return codes


def code_changes(old: dict, new: dict, wanted: re.Pattern) -> list[str]:
    """Changed or removed codes of wanted interfaces, sorted; additions are not reported."""
    out = []
    for (cls, method), code in sorted(old.items()):
        if not wanted.match(cls):
            continue
        now = new.get((cls, method))
        if now != code:
            out.append(f"{_short(cls).removesuffix('$Stub')}.{method}: {code} -> "
                       f"{'removed' if now is None else now}")
    return out


def parcel_orders(dump: str, wanted: re.Pattern) -> dict[tuple[str, str], list[str]]:
    """(Proxy class, method) -> its Parcel calls in order (`dexdump -d`), wanted Proxies only."""
    orders: dict[tuple[str, str], list[str]] = {}
    cls = method = None
    for line in dump.splitlines():
        if m := _CLASS.match(line):
            c = m.group(1)
            cls = c if c.endswith("$Stub$Proxy;") and wanted.match(c) else None
            method = None
        elif cls and (m := _NAME.match(line)):
            method = m.group(1)
            orders.setdefault((cls, method), [])
        elif cls and method:
            if m := _PARCEL_CALL.search(line):
                orders[(cls, method)].append(m.group(1))
            elif m := _CREATOR.search(line):
                orders[(cls, method)].append(m.group(1) + ".CREATOR")
    return orders


def layout_changes(old: dict, new: dict) -> list[str]:
    """Proxy methods present in both whose Parcel calls differ, sorted."""
    out = []
    for key in sorted(old):
        if key in new and old[key] != new[key]:
            cls, method = key
            out.append(f"{_short(cls)}.{method}: {', '.join(old[key])} -> "
                       f"{', '.join(new[key])}")
    return out


def coverage_gaps(orders: dict, interfaces: tuple[str, ...]) -> list[str]:
    """Interfaces with no Proxy method in orders: their layout was not compared at all."""
    seen = {cls for cls, _ in orders}
    return [f"{_short(i)}: no Proxy methods found (names removed by R8?), layout not compared"
            for i in interfaces if i[:-1] + "$Stub$Proxy;" not in seen]


def resource_ids(dump: str) -> dict[str, str]:
    """type/name -> 0x id, from `aapt2 dump resources`."""
    return {m.group(2): m.group(1) for line in dump.splitlines() if (m := _RES.match(line))}


def resource_moves(old: dict, new: dict) -> list[str]:
    """Resources in both whose ID changed, sorted (removals show as -> missing)."""
    return [f"{name}: {rid} -> {new.get(name, 'missing')}"
            for name, rid in sorted(old.items()) if new.get(name) != rid]


def defined_members(dump: str) -> set[str]:
    """'Lcls;.name:type' for every method and field `dexdump` lists."""
    out = set()
    cls = name = None
    for line in dump.splitlines():
        if m := _CLASS.match(line):
            cls, name = m.group(1), None
        elif m := _NAME.match(line):
            name = m.group(1)
        elif (m := _TYPE.match(line)) and cls and name:
            out.add(f"{cls}.{name}:{m.group(1)}")
            name = None
    return out


def referenced_members(dump: str) -> set[str]:
    """'Lcls;.name:type' of every method and field a `dexdump -d` listing references."""
    return {f"{m.group(1)}.{m.group(2)}:{m.group(3)}" for m in _REF.finditer(dump)}


def missing_hidden_refs(refs: set[str], old_defs: set[str], new_defs: set[str]) -> list[str]:
    """References the old firmware satisfied and the new one does not, sorted."""
    return sorted(r for r in refs if r in old_defs and r not in new_defs)


def _dexdump(tool: Path, archive: Path, disassemble: bool) -> str:
    """dexdump of every classes*.dex in a jar or APK."""
    out = []
    with zipfile.ZipFile(archive) as z, tempfile.TemporaryDirectory() as tmp:
        for name in sorted(n for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n)):
            path = Path(tmp) / name
            path.write_bytes(z.read(name))
            args = [str(tool)] + (["-d"] if disassemble else []) + [str(path)]
            out.append(subprocess.run(args, capture_output=True, encoding="utf-8",
                                      errors="replace", check=True).stdout)
    return "\n".join(out)


def _dumps(tool: Path, folder: Path, files: tuple[str, ...], disassemble: bool) -> str:
    return "\n".join(_dexdump(tool, folder / f, disassemble) for f in files
                     if (folder / f).is_file())


def _section(title: str, lines: list[str]) -> str:
    return f"{title}: " + ("no relevant changes" if not lines
                           else f"{len(lines)}\n  " + "\n  ".join(lines))


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("old", type=Path, help="firmware folder before the OTA")
    ap.add_argument("new", type=Path, help="firmware folder after the OTA")
    ap.add_argument("--apk", type=Path, help="the built HyperGestures.apk (hidden-API check)")
    a = ap.parse_args(argv)
    dexdump = sdk.find_build_tool(ROOT, "DEXDUMP", ("dexdump.exe", "dexdump"))
    aapt2 = sdk.find_aapt2(ROOT)

    found = []
    old_code = transaction_codes(_dumps(dexdump, a.old, INTERFACE_FILES, False))
    new_code = transaction_codes(_dumps(dexdump, a.new, INTERFACE_FILES, False))
    found.append(("codes", code_changes(old_code, new_code, WANTED)))
    old_dis = _dumps(dexdump, a.old, INTERFACE_FILES, True)
    new_dis = _dumps(dexdump, a.new, INTERFACE_FILES, True)
    old_orders, new_orders = parcel_orders(old_dis, WANTED), parcel_orders(new_dis, WANTED)
    found.append(("layouts", layout_changes(old_orders, new_orders)))
    # Not a change, but no all-clear either: said, without failing the run.
    gaps = sorted(set(coverage_gaps(old_orders, COVERAGE) + coverage_gaps(new_orders, COVERAGE)))
    res = [subprocess.run([str(aapt2), "dump", "resources", str(d / "framework-res.apk")],
                          capture_output=True, encoding="utf-8", errors="replace",
                          check=True).stdout for d in (a.old, a.new)]
    found.append(("resources", resource_moves(resource_ids(res[0]), resource_ids(res[1]))))
    if a.apk:
        refs = referenced_members(_dexdump(dexdump, a.apk, True))
        old_defs = defined_members(_dumps(dexdump, a.old, FRAMEWORK_FILES, False))
        new_defs = defined_members(_dumps(dexdump, a.new, FRAMEWORK_FILES, False))
        found.append(("hidden APIs", missing_hidden_refs(refs, old_defs, new_defs)))
    for title, lines in found:
        print(_section(title, lines))
    if gaps:
        print("not compared (check these by hand):\n  " + "\n  ".join(gaps))
    return 1 if any(lines for _, lines in found) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
