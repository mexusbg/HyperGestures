#!/usr/bin/env bash
# Builds the real HyperGestures app, its notification hooks (com.hypergestures.notif, the LSPosed
# module, modules/notif) and the overlay, runs every offline check (the AIDL transaction-code,
# launcher and notification-hook unit tests, the module-script and Python suites), and packages the
# KernelSU module.
# The version comes from version.properties; the environment overrides it, e.g.
# VERSION_CODE=5 VERSION=0.3.2-alpha tools/build-module.sh (VERSION_CODE goes into module.prop
# and the APKs alike). HG_PYTHON picks the Python 3.11+ interpreter (default: python). The
# module's expected-fingerprint comes from tools/framework-stub/device-build.txt.
set -euo pipefail
cd "$(dirname "$0")/.."
PY="${HG_PYTHON:-python}"
VERSION="${VERSION:-$(sed -n 's/^version=//p' version.properties)}"
VERSION_CODE="${VERSION_CODE:-$(sed -n 's/^versionCode=//p' version.properties)}"
# AGP 9 has no testReleaseUnitTest task; the unit tests run on the debug variant.
./gradlew --console=plain "-PhgVersionCode=$VERSION_CODE" "-PhgVersionName=$VERSION" \
  :wmshell-shared:testDebugUnitTest :sysui-shared:testDebugUnitTest :launcher:testDebugUnitTest \
  :notif:testDebugUnitTest :launcher:assembleRelease :notif:assembleRelease \
  :overlay:clean :overlay:assembleRelease
# The module suite spawns tens of thousands of processes. Git Bash (MSYS) emulates fork slowly
# (~80 min on the owner's PC); WSL runs the same suite in ~25 s. Use WSL when this is Git Bash with the
# distro installed (HG_WSL_DISTRO, default Ubuntu-22.04; HG_WSL=0 turns it off), else plain sh.
run_shell_suite() {
  distro="${HG_WSL_DISTRO:-Ubuntu-22.04}"
  win="$(pwd -W 2>/dev/null || true)"
  if [ "${HG_WSL:-1}" != 0 ] && [ -n "$win" ] && command -v wsl.exe >/dev/null 2>&1 \
     && wsl.exe -d "$distro" -- true >/dev/null 2>&1; then
    drive="$(printf '%s' "$win" | cut -c1 | tr 'A-Z' 'a-z')"
    wsl.exe -d "$distro" -- sh -c "cd '/mnt/$drive${win#?:}' && sh tests/module/test_functions.sh"
  else
    sh tests/module/test_functions.sh
  fi
}
run_shell_suite
"$PY" -m unittest discover -s tools/hgtools/tests -t . -q
"$PY" -m tools.hgtools.package_module \
  --template module/template \
  --apk modules/launcher/build/outputs/apk/release/launcher-release.apk \
  --overlay overlay/build/outputs/apk/release/overlay-release.apk \
  --notif-apk modules/notif/build/outputs/apk/release/notif-release.apk \
  --device-build tools/framework-stub/device-build.txt \
  --version "$VERSION" --version-code "$VERSION_CODE" \
  --out "out/HyperGestures-$VERSION.zip"
