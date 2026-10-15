#!/usr/bin/env bash
# Re-creates aosp/ from AOSP at a fixed tag. Usage: tools/aosp/import.sh [cache-dir]
# Clones are blobless and sparse; files are copied (not symlinked) into aosp/.
set -euo pipefail
TAG=android-17.0.0_r1
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CACHE="${1:-$ROOT/../hg-aosp-cache}"
AOSP=https://android.googlesource.com
mkdir -p "$CACHE"
mkdir -p "$ROOT/aosp"
UP="$ROOT/aosp/UPSTREAM.md"

fetch() { # fetch <repo> <cachedir> <pattern...>
  local repo=$1 dir="$CACHE/$2"; shift 2
  if [ ! -d "$dir/.git" ]; then
    git clone --filter=blob:none --no-checkout --depth 1 --branch "$TAG" "$AOSP/$repo" "$dir"
  fi
  # Always (re)fetch the tag itself, so a cache from an earlier run, or one whose tag ref moved,
  # cannot leave an older checkout in place.
  git -C "$dir" fetch -q --filter=blob:none --depth 1 --no-tags origin tag "$TAG"
  git -C "$dir" config core.longpaths true
  # Git Bash (MSYS) mangles bare leading-slash CLI args without a wildcard into
  # Windows paths (e.g. `/tests/` -> `C:/Program Files/Git/tests/`), silently
  # corrupting sparse-checkout patterns as argv. Feed them via --stdin instead,
  # which bypasses MSYS argv path conversion entirely.
  printf '%s\n' "$@" | git -C "$dir" sparse-checkout set --no-cone --stdin
  git -C "$dir" checkout -q --detach "refs/tags/$TAG"
  verify_tag "$dir"
}
verify_tag() { # the checkout must be exactly the tag's commit, or the import is not what UPSTREAM.md says
  local want have
  want="$(git -C "$1" rev-parse --verify -q "refs/tags/$TAG^{commit}")" \
    || { echo "FATAL: $1 has no tag $TAG" >&2; exit 1; }
  have="$(git -C "$1" rev-parse HEAD)"
  if [ "$want" != "$have" ]; then
    echo "FATAL: $1 is at $have, not $TAG ($want)" >&2
    exit 1
  fi
}
copy() { # copy <cachedir> <dest>  (checked-out tracked files only, preserving layout)
  local dir="$CACHE/$1" dest="$ROOT/aosp/$2"
  rm -rf "$dest"; mkdir -p "$dest"
  # `git ls-files -t` tags skip-worktree (outside the sparse set) entries with S; copy only H.
  (cd "$dir" && git ls-files -t -z | tr '\0' '\n' | sed -n 's/^H //p' | while IFS= read -r f; do
     [ -L "$f" ] && continue          # upstream symlinks live only in test trees; never vendor them
     mkdir -p "$dest/$(dirname "$f")" && cp "$f" "$dest/$f"
   done)
  echo "| $3 | $AOSP/$3 | $TAG | $(git -C "$dir" rev-parse --short HEAD) | \`aosp/$2\` |" >> "$UP"
}

printf '# Upstream imports\n\n| repo | url | tag | commit | path |\n|---|---|---|---|---|\n' > "$UP"

fetch platform/packages/apps/Launcher3 launcher3 \
  '/*' '!/tests/' '!/quickstep/tests/' '!/modules/*/tests/' '!/go/' '!/checks/' '!/**/tests/'
# NOTE: do not exclude */testing/* — Launcher3 main code uses com.android.launcher3.testing.*
# `checks/` is a standalone lint module, not in the build map's VENDOR list — dropped entirely.
# `!/**/tests/` is a safety net (no build-map VENDOR path is a directory literally named tests/).
copy launcher3 launcher3 platform/packages/apps/Launcher3

fetch platform/frameworks/base fwb \
  '/NOTICE' '/packages/SystemUI/shared/' '/packages/SystemUI/animation/' \
  '/packages/SystemUI/plugin_core/' '/packages/SystemUI/plugin/src/com/android/systemui/plugins/PluginProtector.kt' \
  '/packages/SystemUI/unfold/src/' '/packages/SystemUI/utils/src/' '/packages/SystemUI/common/src/' \
  '/packages/SystemUI/log/' '/packages/SystemUI/compose/core/' '/packages/SystemUI/aconfig/' \
  '/packages/SettingsLib/SettingsTheme/' \
  '/libs/WindowManager/Shell/shared/' '/libs/WindowManager/Shell/aconfig/' \
  '/libs/WindowManager/Shell/src/**/*.aidl' '/libs/computercontrol/' \
  '/core/java/android/window/flags/*.aconfig' '/core/java/android/os/*.aconfig' \
  '/core/java/android/security/*.aconfig' '/core/java/android/companion/flags.aconfig' \
  '/core/java/android/companion/virtual/flags/*.aconfig' '/core/java/android/content/pm/multiuser.aconfig' \
  '/core/java/android/appwidget/flags.aconfig' '/core/java/android/hardware/input/input_framework.aconfig' \
  '/services/core/java/com/android/server/uilatencystats/flags.aconfig' \
  '!/packages/SystemUI/**/tests/' '!/libs/WindowManager/Shell/**/tests/' '!/packages/SettingsLib/**/tests/' \
  '!/libs/computercontrol/tests/' '!/**/tests/'
copy fwb frameworks-base platform/frameworks/base

fetch platform/frameworks/libs/systemui fls '/*' '!/**/tests/'
copy fls libs-systemui platform/frameworks/libs/systemui

fetch platform/packages/providers/MediaProvider mediaprovider '/mediaprovider_flags.aconfig' '/NOTICE'
copy mediaprovider mediaprovider platform/packages/providers/MediaProvider

PKGS="com.android.launcher3 com.android.systemui com.android.systemui.shared com.android.wm.shell \
com.android.settingslib.widget.theme.flags com.android.window.flags android.os android.security \
android.companion android.companion.virtualdevice.flags android.multiuser android.appwidget.flags \
com.android.hardware.input com.android.server.ui_latency_stats com.android.providers.media.flags"
PATS=('/release_configs/' '/flag_values/')
for p in $PKGS; do PATS+=("/aconfig/*/$p/"); done
fetch platform/build/release build-release "${PATS[@]}"
copy build-release build-release platform/build/release

fetch platform/prebuilts/sdk prebuilts-sdk '/37.0/module-lib/framework-statsd.jar' \
  '/37.0/module-lib/framework-configinfrastructure.jar' '/NOTICE'
mkdir -p "$ROOT/prebuilts/sdk/37.0/module-lib"
cp "$CACHE/prebuilts-sdk/37.0/module-lib/"framework-{statsd,configinfrastructure}.jar "$ROOT/prebuilts/sdk/37.0/module-lib/"
echo "| platform/prebuilts/sdk (2 jars) | $AOSP/platform/prebuilts/sdk | $TAG | $(git -C "$CACHE/prebuilts-sdk" rev-parse --short HEAD) | \`prebuilts/sdk/37.0/module-lib\` |" >> "$UP"
echo "done"
