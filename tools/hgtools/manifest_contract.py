"""Manifest contract check for the HyperGestures recents-only launcher (spec §6.1).

No compiler enforces this: the manifest must expose the recents surface the system needs to route
gestures to us, and must not expose anything that would let the system offer this package as a
home screen, a secondary-display home, an application-preferences target, an indexable-settings
source that dereferences settings internals, or a full-backup agent that could tear down (and thus
kill) the gesture service across a restore. Nor may it declare the rest of a home launcher's
surface (pin-shortcut/widget confirmation, widget picker, notification-dots listener, app-function
services, ambient-cue renderer, install/widget-restore receivers, the wallpaper picker's grid
customization provider): the system only uses those for the default home app, and each is an
exported entry point this app does not need.

With --file-provider-paths, also checks the recents FileProvider's paths file (screenshot sharing):
it must not share the filesystem root (root-path) or a whole cache/files/external directory.

Checked on the merged manifest (a Gradle intermediate) and, with --apk, on the manifest actually
packaged in the APK (`aapt2 dump xmltree`), so nothing added after merging slips through.
"""
import argparse
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

from tools.hgtools import sdk

_ANDROID_NS = "http://schemas.android.com/apk/res/android"

# Actions only a home launcher serves -> why this recents-only app must not handle them.
_HOME_ONLY_ACTIONS = {
    "android.content.pm.action.CONFIRM_PIN_SHORTCUT": "pin-shortcut confirmation (home app only)",
    "android.content.pm.action.CONFIRM_PIN_APPWIDGET": "pin-widget confirmation (home app only)",
    "android.intent.action.PICK": "widget picker",
    "android.service.notification.NotificationListenerService": "notification-dots listener",
    "android.app.appfunctions.AppFunctionService": "app-function service",
    "android.service.personalcontext.renderer.InsightRendererService": "ambient-cue renderer",
    "android.content.pm.action.SESSION_COMMITTED": "adds installed apps to the workspace",
    "android.appwidget.action.APPWIDGET_HOST_RESTORED": "restores home-screen widgets",
}

# The AOSP components behind those actions, rejected by name even without an intent filter.
_HOME_ONLY_COMPONENTS = {
    "com.android.launcher3.QuickstepAddItemActivity",
    "com.android.launcher3.QuickstepWidgetPickerActivity",
    "com.android.launcher3.taskbar.CueBarInsightRendererService",
    "com.android.launcher3.notification.NotificationListener",
    "androidx.appfunctions.service.PlatformAppFunctionService",
    "androidx.appfunctions.service.ExtensionAppFunctionService",
    "com.android.launcher3.SessionCommitReceiver",
    "com.android.launcher3.AppWidgetsRestoredReceiver",
    "com.android.launcher3.graphics.LauncherCustomizationProvider",
}

ROOT = Path(__file__).resolve().parents[2]


def _android(attr: str) -> str:
    return f"{{{_ANDROID_NS}}}{attr}"


def _name(el: ET.Element, package: str) -> str:
    name = el.get(_android("name"), "")
    return package + name if name.startswith(".") else name


def check(merged_manifest_xml: str) -> list[str]:
    """Problems in a manifest given as XML text (the merged manifest)."""
    return check_root(ET.fromstring(merged_manifest_xml))


def check_root(root: ET.Element) -> list[str]:
    """Problems in a parsed manifest (from XML text or from parse_xmltree)."""
    problems: list[str] = []
    package = root.get("package", "")
    application = root.find("application")
    if application is None:
        return ["no <application> element"]

    components = [el for el in application.iter() if el is not application]

    # The recents component itself.
    if not any(
        el.tag == "activity" and _name(el, package) == "com.android.quickstep.RecentsActivity"
        for el in components
    ):
        problems.append("no activity resolves to com.android.quickstep.RecentsActivity")

    # TouchInteractionService: QUICKSTEP_SERVICE action + STATUS_BAR_SERVICE permission together.
    has_quickstep_service = False
    for el in components:
        if el.tag != "service":
            continue
        actions = {
            a.get(_android("name"))
            for intent in el.findall("intent-filter")
            for a in intent.findall("action")
        }
        if "android.intent.action.QUICKSTEP_SERVICE" not in actions:
            continue
        if el.get(_android("permission")) == "android.permission.STATUS_BAR_SERVICE":
            has_quickstep_service = True
        else:
            problems.append(
                f"service {_name(el, package)} has action QUICKSTEP_SERVICE but not "
                "android:permission=\"android.permission.STATUS_BAR_SERVICE\""
            )
    if not has_quickstep_service:
        problems.append(
            "no service with action QUICKSTEP_SERVICE and permission "
            "android.permission.STATUS_BAR_SERVICE"
        )

    # No entry point that could make the system offer us as a home screen (of any kind), no
    # exposed settings-preferences entry point, and none of a home launcher's other surface.
    for el in components:
        for intent in el.findall("intent-filter"):
            categories = {c.get(_android("name")) for c in intent.findall("category")}
            actions = {a.get(_android("name")) for a in intent.findall("action")}
            if "android.intent.category.HOME" in categories:
                problems.append(f"{el.tag} {_name(el, package)} has a category.HOME intent filter")
            if "android.intent.category.SECONDARY_HOME" in categories:
                problems.append(
                    f"{el.tag} {_name(el, package)} has a category.SECONDARY_HOME intent filter"
                )
            if "android.intent.action.APPLICATION_PREFERENCES" in actions:
                problems.append(
                    f"{el.tag} {_name(el, package)} has an APPLICATION_PREFERENCES intent filter"
                )
            for action in sorted(actions & _HOME_ONLY_ACTIONS.keys()):
                problems.append(
                    f"{el.tag} {_name(el, package)} handles {action} ({_HOME_ONLY_ACTIONS[action]})"
                )
        if _name(el, package) in _HOME_ONLY_COMPONENTS:
            problems.append(f"{el.tag} {_name(el, package)} is home-launcher-only surface")

    # LauncherSearchIndexablesProvider dereferences settingsActivity!! (a settings-search internal
    # we don't have); it must not be present at all, not merely unexported.
    for el in components:
        if el.tag == "provider" and _name(el, package) == "com.android.quickstep.LauncherSearchIndexablesProvider":
            problems.append("LauncherSearchIndexablesProvider is present")

    # A full-backup agent (or any backup) would tear down, and restore over, the gesture service.
    if _android("backupAgent") in application.attrib:
        problems.append("application has a backupAgent attribute")
    if application.get(_android("allowBackup"), "").lower() != "false":
        problems.append('application does not set android:allowBackup="false"')
    if _android("fullBackupContent") in application.attrib:
        problems.append("application has a fullBackupContent attribute")

    return problems


def check_file_provider_paths(paths_xml: str) -> list[str]:
    """Problems in a FileProvider paths file (android.support.FILE_PROVIDER_PATHS) given as XML
    text: a root-path entry (the whole filesystem, as the app's uid sees it) or any entry whose
    path is its directory's root ("", "/", ".") shares far more than one feature's files."""
    problems: list[str] = []
    for el in ET.fromstring(paths_xml):
        if not isinstance(el.tag, str):
            continue
        name = el.get("name", "")
        path = el.get("path", "")
        if el.tag == "root-path":
            problems.append(f'<root-path name="{name}"> shares the whole filesystem')
        elif path.strip().strip("/") in ("", "."):
            problems.append(f'<{el.tag} name="{name}" path="{path}"> shares its whole directory')
    return problems


_XMLTREE_LINE = re.compile(r"^(?P<indent> *)(?P<kind>[NEA]): (?P<rest>.*)$")
_XMLTREE_ATTR = re.compile(
    r"^(?:(?P<ns>https?://[^()=\s]*?):)?(?P<name>[^:()=\s]+)(?:\(0x[0-9a-fA-F]+\))?=(?P<value>.*)$"
)
_QUOTED = re.compile(r'^"(?P<v>.*?)"(?: \(Raw: ".*"\))?$')


def parse_xmltree(text: str) -> ET.Element:
    """The `aapt2 dump xmltree --file AndroidManifest.xml <apk>` output as an ElementTree root.

    Nesting follows indentation (an element's attributes and children are indented deeper than
    it). Attribute values: quoted strings unquoted, anything else (true/false, @0x7f… references,
    numbers) kept as printed. Namespaced attributes are keyed `{uri}name`, like ElementTree's.
    """
    root = None
    stack: list[tuple[int, ET.Element]] = []
    for raw in text.splitlines():
        m = _XMLTREE_LINE.match(raw)
        if not m or m["kind"] == "N":
            continue
        indent = len(m["indent"])
        while stack and stack[-1][0] >= indent:
            stack.pop()
        if m["kind"] == "E":
            el = ET.Element(m["rest"].split()[0])
            if stack:
                stack[-1][1].append(el)
            elif root is None:
                root = el
            stack.append((indent, el))
            continue
        a = _XMLTREE_ATTR.match(m["rest"])
        if not a or not stack:
            continue
        q = _QUOTED.match(a["value"])
        value = q["v"] if q else a["value"]
        key = f"{{{a['ns']}}}{a['name']}" if a["ns"] else a["name"]
        stack[-1][1].set(key, value)
    if root is None:
        raise ValueError("no element in aapt2 xmltree output")
    return root


def packaged_manifest(apk: Path, aapt2: Path) -> ET.Element:
    out = subprocess.run([str(aapt2), "dump", "xmltree", "--file", "AndroidManifest.xml", str(apk)],
                         check=True, capture_output=True, text=True, encoding="utf-8").stdout
    return parse_xmltree(out)


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("manifest", nargs="?", help="path to the merged AndroidManifest.xml")
    ap.add_argument("--apk", type=Path, help="also check the manifest packaged in this APK")
    ap.add_argument("--aapt2", type=Path, help="aapt2 to use with --apk (default: found in the SDK)")
    ap.add_argument("--file-provider-paths", type=Path,
                    help="also check this FileProvider paths file (res/xml source)")
    a = ap.parse_args(argv)
    if not a.manifest and not a.apk and not a.file_provider_paths:
        ap.error("give a merged manifest, --apk, --file-provider-paths, or any of them")
    problems: list[str] = []
    if a.manifest:
        problems += [f"merged manifest: {p}" for p in check(open(a.manifest, encoding="utf-8").read())]
    if a.apk:
        root = packaged_manifest(a.apk, a.aapt2 or sdk.find_aapt2(ROOT))
        problems += [f"packaged manifest ({a.apk.name}): {p}" for p in check_root(root)]
    if a.file_provider_paths:
        text = a.file_provider_paths.read_text(encoding="utf-8")
        problems += [f"{a.file_provider_paths.name}: {p}" for p in check_file_provider_paths(text)]
    if problems:
        for p in problems:
            print(p)
        return 1
    print("ok")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
