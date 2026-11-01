#!/usr/bin/env bash
# Regenerates flags/src/main/java and flags/VALUES.md from the vendored .aconfig files (build map §2.1).
# flags/values/aosp-chain.txt is rewritten from the vendored cp2a release-config inherits (Soong's
# order). A project override that contradicts flags/values/device.txt prints a WARNING and is listed
# in VALUES.md's "Conflicts" section. HG_PYTHON picks the Python 3.11+ interpreter (default: python).
set -euo pipefail
cd "$(dirname "$0")/.."
A=aosp
PY="${HG_PYTHON:-python}"
# Generated into a temporary dir next to the output and swapped in only when the generator
# succeeded, so a failed run leaves the previous sources (and VALUES.md) as they were.
TMP="$(mktemp -d flags/.gen.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
"$PY" -m tools.hgtools.aconfig_gen \
  --decl $A/launcher3/aconfig/*.aconfig \
         $A/frameworks-base/packages/SystemUI/aconfig/*.aconfig \
         $A/libs-systemui/aconfig/*.aconfig \
         $A/frameworks-base/libs/WindowManager/Shell/aconfig/*.aconfig \
         $A/frameworks-base/packages/SettingsLib/SettingsTheme/aconfig/*.aconfig \
         $A/frameworks-base/core/java/android/window/flags/*.aconfig \
         $A/frameworks-base/core/java/android/os/*.aconfig \
         $A/frameworks-base/core/java/android/security/flags.aconfig \
         $A/frameworks-base/core/java/android/security/keychain_flags.aconfig \
         $A/frameworks-base/core/java/android/security/responsible_apis_flags.aconfig \
         $A/frameworks-base/core/java/android/security/conscrypt_nsc_on.aconfig \
         $A/frameworks-base/core/java/android/companion/flags.aconfig \
         $A/frameworks-base/core/java/android/companion/virtual/flags/flags.aconfig \
         $A/frameworks-base/core/java/android/companion/virtual/flags/launched_flags.aconfig \
         $A/frameworks-base/core/java/android/content/pm/multiuser.aconfig \
         $A/frameworks-base/core/java/android/appwidget/flags.aconfig \
         $A/frameworks-base/core/java/android/hardware/input/input_framework.aconfig \
         $A/frameworks-base/services/core/java/com/android/server/uilatencystats/flags.aconfig \
         $A/mediaprovider/mediaprovider_flags.aconfig \
  --values-root $A/build-release/aconfig \
  --release-configs $A/build-release/release_configs --release cp2a \
  --chain flags/values/aosp-chain.txt \
  --device flags/values/device.txt \
  --overrides flags/values/project-overrides.txt \
  --out "$TMP/java" \
  --report "$TMP/VALUES.md"
[ -d "$TMP/java" ] && [ -f "$TMP/VALUES.md" ] || { echo "gen-flags: nothing generated" >&2; exit 1; }
rm -rf flags/src/main/java
mv "$TMP/java" flags/src/main/java
mv -f "$TMP/VALUES.md" flags/VALUES.md

# Why some .aconfig files are named one by one rather than globbed:
#  - android/companion/virtual/flags/*.aconfig is listed file-by-file (not globbed) to exclude
#    deprecated_flags_do_not_edit.aconfig, which declares package "android.companion.virtual.flags" —
#    not one of the 15 packages in build map §2.1 (the real package, "android.companion.virtualdevice.flags",
#    comes from flags.aconfig and launched_flags.aconfig in the same directory).
#  - conscrypt_nsc_on.aconfig is used instead of conscrypt_nsc_off.aconfig: aosp/build-release/flag_values/
#    cp2a/RELEASE_CONSCRYPT_NSC.textproto sets bool_value: true for the cp2a release config.
