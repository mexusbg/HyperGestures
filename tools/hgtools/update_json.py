"""Writes or checks update.json, the file KernelSU's module updater reads (module.prop's
updateJson): the newest version, its versionCode, the release zip's URL and the changelog's.

  python -m tools.hgtools.update_json          # write update.json from version.properties
  python -m tools.hgtools.update_json --check  # fail if update.json is not what it would write

KernelSU offers the update when versionCode is higher than the installed module's. The zip URL is
the GitHub release CI publishes for the tag v<version> (BUILDING.md: releases), so bump
version.properties, run this, commit both, then tag that commit.
"""
import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REPO = "mexusbg/HyperGestures"
UPDATE_JSON = ROOT / "update.json"
# Where the installed module looks for update.json (module.prop.in's updateJson).
UPDATE_JSON_URL = f"https://raw.githubusercontent.com/{REPO}/master/update.json"
CHANGELOG_URL = f"https://raw.githubusercontent.com/{REPO}/master/CHANGELOG.md"


def read_version(props: Path) -> tuple[str, int]:
    """version and versionCode from version.properties."""
    values = {}
    for line in props.read_text(encoding="utf-8").splitlines():
        key, sep, value = line.partition("=")
        if sep and not key.strip().startswith("#"):
            values[key.strip()] = value.strip()
    return values["version"], int(values["versionCode"])


def render(version: str, version_code: int) -> str:
    data = {
        "version": version,
        "versionCode": version_code,
        "zipUrl": f"https://github.com/{REPO}/releases/download/v{version}/"
                  f"HyperGestures-{version}.zip",
        "changelog": CHANGELOG_URL,
    }
    return json.dumps(data, indent=2) + "\n"


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="only check; exit 1 if stale")
    a = ap.parse_args(argv)
    text = render(*read_version(ROOT / "version.properties"))
    current = UPDATE_JSON.read_text(encoding="utf-8") if UPDATE_JSON.is_file() else None
    if a.check:
        if current != text:
            print("update.json does not match version.properties: run "
                  "python -m tools.hgtools.update_json", file=sys.stderr)
            return 1
        return 0
    UPDATE_JSON.write_text(text, encoding="utf-8", newline="\n")
    print(f"{UPDATE_JSON.name}: {text.strip()}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
