"""Assembles the HyperGestures KernelSU module zip. Refuses to produce an unsafe module."""
import argparse
import os
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

from tools.hgtools import privapp, sdk

# Packaged with mode 0755. service.sh copies the guard into /data/adb/service.d, where KernelSU
# skips files that aren't executable. customize.sh is sourced by KernelSU's installer (every
# template file is packaged, LF-only for *.sh).
SCRIPTS = {"service.sh", "post-fs-data.sh", "uninstall.sh", "action.sh", "restore.sh", "setconf.sh",
           "customize.sh", "common/functions.sh", "common/hypergestures-guard.sh"}
# The static recents overlay (the overlay/ Gradle project's RRO) ships OUTSIDE system/, so no
# metamodule ever mounts it: KernelSU's kernel_umount hides metamodule mounts from WebView's
# zygote, which holds framework overlays open, and every WebView renderer then aborts.
# post-fs-data.sh self-mounts it on /product/overlay in every MOUNT mode (hg_self_mount in
# common/functions.sh).
OVERLAY_ENTRY = "overlay/HyperGesturesOverlay.apk"
APP_ENTRY = "system/priv-app/HyperGestures/HyperGestures.apk"
# The notification icon fix's hooks, their own small APK and the LSPosed module (modules/notif):
# LSPosed copies every classes*.dex of a module's APK into each process it hooks, so the hooks
# must not be part of the main app's dex. Next to the main app in the same system/priv-app tree,
# so every mount, unmount and restore path handles both alike (common/functions.sh).
NOTIF_PACKAGE = "com.hypergestures.notif"
NOTIF_ENTRY = "system/priv-app/HyperGesturesNotif/HyperGesturesNotif.apk"
# What makes an APK an LSPosed module (modern, then legacy): LSPosed loads the first of a package's
# APKs that has one (JingMatrix/Vector ConfigCache.getModuleApkPath).
XPOSED_ENTRIES = ("META-INF/xposed/java_init.list", "assets/xposed_init")
# The hooks' dex is about 46 KB. Far more means the main app (or a library) got into it again,
# and LSPosed would copy all of it into SystemUI and the AOD.
NOTIF_DEX_MAX = 1024 * 1024
ROOT = Path(__file__).resolve().parents[2]
# The licenses and the NOTICE, at the zip's root: PolyForm Noncommercial asks that whoever gets a
# copy also gets its terms and the "Required Notice" line (both in LICENSE); Apache-2.0 asks the
# same for the AOSP code the APKs are built from (LICENSE-APACHE-2.0, NOTICE).
LEGAL_FILES = ("LICENSE", "LICENSE-APACHE-2.0", "NOTICE")


class PackagingError(Exception):
    pass


def _has_code(apk: Path) -> bool:
    try:
        with zipfile.ZipFile(apk) as z:
            return any(n.endswith(".dex") for n in z.namelist())
    except zipfile.BadZipFile as e:
        raise PackagingError(f"not an APK (zip): {apk}") from e


def _names(apk: Path) -> list[str]:
    try:
        with zipfile.ZipFile(apk) as z:
            return z.namelist()
    except zipfile.BadZipFile as e:
        raise PackagingError(f"not an APK (zip): {apk}") from e


def _check_notif_split(apk: Path, notif_apk: Path) -> None:
    """The main app must not be an LSPosed module (LSPosed would load its whole dex into SystemUI
    and the AOD again); the notification hooks' APK must be one, with a small dex."""
    xposed = [n for n in _names(apk) if n in XPOSED_ENTRIES]
    if xposed:
        raise PackagingError(f"{apk} is an LSPosed module ({', '.join(xposed)}); the hooks belong "
                             f"in the {NOTIF_PACKAGE} APK alone")
    if XPOSED_ENTRIES[0] not in _names(notif_apk):
        raise PackagingError(f"{notif_apk} has no {XPOSED_ENTRIES[0]}: LSPosed would not see the "
                             "notification hooks")
    with zipfile.ZipFile(notif_apk) as z:
        dex = sum(i.file_size for i in z.infolist()
                  if i.filename.startswith("classes") and i.filename.endswith(".dex"))
    if dex == 0:
        raise PackagingError(f"{notif_apk} has no classes.dex")
    if dex > NOTIF_DEX_MAX:
        raise PackagingError(f"{notif_apk}'s dex is {dex} bytes (limit {NOTIF_DEX_MAX}): LSPosed "
                             "copies all of it into SystemUI and the AOD")


def _entry(z: zipfile.ZipFile, name: str, data: bytes, executable: bool) -> None:
    info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = ((0o755 if executable else 0o644) | 0o100000) << 16
    z.writestr(info, data)


def read_fingerprint(device_build: Path) -> str:
    """The `fingerprint=` of tools/framework-stub/device-build.txt: the HyperOS build the APK was
    compiled against (device framework stub, inlined device resources, AIDL tables)."""
    try:
        text = device_build.read_text(encoding="utf-8")
    except OSError as e:
        raise PackagingError(f"cannot read {device_build}: {e}") from e
    for line in text.splitlines():
        key, sep, value = line.partition("=")
        if sep and key.strip() == "fingerprint" and value.strip():
            return value.strip()
    raise PackagingError(f"{device_build} has no fingerprint= line (re-run make-framework-stub.sh)")


def build_zip(template_dir: Path, apk: Path, overlay_apk: Path, permissions: list[str],
              package: str, version: str, version_code: int, out_zip: Path,
              fingerprint: str, notif_apk: Path, notif_permissions: list[str] = (),
              notif_package: str = NOTIF_PACKAGE) -> None:
    """`fingerprint` goes into the module's `expected-fingerprint`: service.sh sets up only on a
    phone whose ro.build.fingerprint equals it (IGNORE_FINGERPRINT=1 overrides).

    `overlay_apk` (resource-only) goes to OVERLAY_ENTRY, not into system/: system/ holds exactly
    the two priv-app APKs (the app at APP_ENTRY, the notification hooks `notif_apk` at
    NOTIF_ENTRY), a privapp allowlist for each, and the hidden-API sysconfig."""
    if not fingerprint.strip() or any(c in fingerprint.strip() for c in "\r\n"):
        raise PackagingError(f"bad expected fingerprint: {fingerprint!r}")
    for f in (apk, overlay_apk, notif_apk):
        if not f.is_file():
            raise PackagingError(f"missing input: {f}")
    if not template_dir.is_dir():
        raise PackagingError(f"missing template: {template_dir}")
    if _has_code(overlay_apk):
        raise PackagingError(f"{overlay_apk} contains dex code; the overlay must be resource-only "
                             "(stale overlay build output? run ./gradlew :overlay:clean)")
    _check_notif_split(apk, notif_apk)

    # Entry -> (package, permissions it must grant), checked on the written zip (_check_allowlists).
    allowlists: dict[str, tuple[str, list[str]]] = {}
    for pkg, perms in ((package, list(permissions)), (notif_package, list(notif_permissions))):
        allowlists[f"system/etc/permissions/privapp-permissions-{pkg}.xml"] = (pkg, perms)

    files: dict[str, tuple[bytes, bool]] = {}
    for path in sorted(template_dir.rglob("*")):
        if path.is_dir():
            continue
        rel = path.relative_to(template_dir).as_posix()
        data = path.read_bytes()
        if rel.endswith(".sh") or rel.endswith(".in") or rel.endswith(".xml") or rel == "config.default":
            if b"\r\n" in data:
                raise PackagingError(f"CRLF line endings in {rel}")
        if rel == "module.prop.in":
            rel = "module.prop"
            data = (data.decode("utf-8")
                    .replace("@VERSION@", version)
                    .replace("@VERSION_CODE@", str(version_code))).encode()
        files[rel] = (data, rel in SCRIPTS)

    files[APP_ENTRY] = (apk.read_bytes(), False)
    files[NOTIF_ENTRY] = (notif_apk.read_bytes(), False)
    files[OVERLAY_ENTRY] = (overlay_apk.read_bytes(), False)
    for name, (pkg, perms) in allowlists.items():
        files[name] = (privapp.render_xml(pkg, perms).encode("utf-8"), False)
    files["expected-fingerprint"] = ((fingerprint.strip() + "\n").encode(), False)
    for name in LEGAL_FILES:
        legal = ROOT / name
        if not legal.is_file():
            raise PackagingError(f"missing {legal}: the module must ship its licenses")
        files[name] = (legal.read_bytes(), False)

    tmp = out_zip.with_suffix(".tmp")
    out_zip.parent.mkdir(parents=True, exist_ok=True)
    try:
        with zipfile.ZipFile(tmp, "w") as z:
            for name, (data, exe) in sorted(files.items()):
                _entry(z, name, data, exe)
        _check_allowlists(tmp, allowlists)
        os.replace(tmp, out_zip)
    except Exception:
        tmp.unlink(missing_ok=True)
        raise


def _check_allowlists(module_zip: Path, allowlists: dict[str, tuple[str, list[str]]]) -> None:
    """Reads each privapp allowlist back from the written module zip and parses it as the
    framework does (privapp.allowlisted): every permission the APK requests must be granted to its
    package there, or system_server throws at boot (ro.control_privapp_permissions=enforce)."""
    with zipfile.ZipFile(module_zip) as z:
        for name, (pkg, perms) in allowlists.items():
            try:
                xml = z.read(name).decode("utf-8")
            except KeyError:
                raise PackagingError(f"{name} is not in the module — would bootloop the device")
            try:
                gaps = privapp.missing(xml, pkg, perms)
            except ET.ParseError as e:
                raise PackagingError(f"{name} is not valid XML ({e}) — would bootloop the device")
            if gaps:
                raise PackagingError(f"privapp allowlist of {pkg} misses {gaps} — would bootloop "
                                     "the device")


def _aapt2_permissions(apk: Path) -> list[str]:
    aapt2 = sdk.find_aapt2(ROOT)
    out = subprocess.run([str(aapt2), "dump", "permissions", str(apk)],
                         check=True, capture_output=True, text=True, encoding="utf-8").stdout
    return privapp.parse_permissions(out)


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--template", type=Path, required=True)
    ap.add_argument("--apk", type=Path, required=True)
    ap.add_argument("--overlay", type=Path, required=True,
                    help="the resource-only recents overlay APK (the overlay/ Gradle project)")
    ap.add_argument("--notif-apk", type=Path, required=True,
                    help="the notification icon fix's APK, the LSPosed module (modules/notif)")
    ap.add_argument("--package", default="com.hypergestures")
    ap.add_argument("--notif-package", default=NOTIF_PACKAGE)
    ap.add_argument("--version", required=True)
    ap.add_argument("--version-code", type=int, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--device-build", type=Path, default=ROOT / "tools/framework-stub/device-build.txt",
                    help="stamp whose fingerprint= becomes the module's expected-fingerprint")
    a = ap.parse_args(argv)
    fingerprint = read_fingerprint(a.device_build)
    perms = _aapt2_permissions(a.apk)
    notif_perms = _aapt2_permissions(a.notif_apk)
    build_zip(a.template, a.apk, a.overlay, perms, a.package, a.version, a.version_code, a.out,
              fingerprint, a.notif_apk, notif_perms, a.notif_package)
    print(f"{a.out} ({len(perms)} + {len(notif_perms)} permissions allowlisted; "
          f"expected firmware {fingerprint})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
