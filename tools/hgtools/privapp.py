"""Privileged-permission allowlist for the HyperGestures priv-app.

ro.control_privapp_permissions=enforce on the device: a privileged permission requested by a
priv-app but missing from the allowlist makes system_server throw at boot (bootloop). We
therefore allowlist EVERY platform permission the APK requests (entries for non-privileged
permissions are ignored by the framework), exactly as QuickSwitch does.
"""
import re
from xml.sax.saxutils import quoteattr

# aapt2 prints <uses-permission-sdk-23> entries as "uses-permission-sdk-23: name='...'".
_USES = re.compile(r"^uses-permission(?:-sdk-23)?: name='([^']+)'", re.MULTILINE)
_DECLARED = re.compile(r"^permission: (\S+)", re.MULTILINE)


def parse_permissions(aapt2_output: str) -> list[str]:
    declared = set(_DECLARED.findall(aapt2_output))
    return sorted({p for p in _USES.findall(aapt2_output) if p not in declared})


def render_xml(package: str, permissions: list[str]) -> str:
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<permissions>",
             f"    <privapp-permissions package={quoteattr(package)}>"]
    lines += [f"        <permission name={quoteattr(p)} />" for p in permissions]
    lines += ["    </privapp-permissions>", "</permissions>", ""]
    return "\n".join(lines)


def missing(xml_text: str, permissions: list[str]) -> list[str]:
    present = set(re.findall(r'<permission name="([^"]+)"', xml_text))
    return [p for p in permissions if p not in present]
