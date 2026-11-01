#!/usr/bin/env bash
# After a HyperOS OTA: pull, read-only, the firmware files HyperGestures depends on from the
# connected phone into DEST (default prebuilts/device/firmware-<HyperOS version>/, git-ignored),
# with the phone's getprop next to them. Then compare with the previous firmware:
#   python -m tools.hgtools.firmware_diff prebuilts/device/firmware-OLD \
#     prebuilts/device/firmware-NEW \
#     --apk modules/launcher/build/outputs/apk/release/launcher-release.apk
# and, to build against the new firmware:
#   bash tools/framework-stub/make-framework-stub.sh DEST/framework.jar DEST/framework-res.apk
# Nothing on the phone is changed. ADB_SERIAL picks the device when several are connected.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB=(adb)
[ -n "${ADB_SERIAL:-}" ] && ADB=(adb -s "$ADB_SERIAL")
export MSYS2_ARG_CONV_EXCL='*' # Git Bash: keep device paths as they are

version="$("${ADB[@]}" shell getprop ro.build.version.incremental | tr -d '\r')"
version="${version%.*}" # OS4.0.0.13.XPAEUXM -> OS4.0.0.13
DEST="${1:-$ROOT/prebuilts/device/firmware-$version}"
mkdir -p "$DEST"

for f in /system_ext/framework/Miui-WindowManager-Shell.jar \
         /system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk \
         /product/app/MIUISystemUIPlugin/MIUISystemUIPlugin.apk \
         /product/priv-app/MiuiHome/MiuiHome.apk \
         /product/overlay/MiuiHomeLauncherResOverlay.apk \
         /system/framework/framework-res.apk \
         /system/framework/framework.jar \
         /system/framework/services.jar \
         /system_ext/framework/miui-services.jar \
         /system_ext/framework/miui-framework.jar; do
  "${ADB[@]}" pull "$f" "$DEST/" >/dev/null
  echo "pulled $f"
done
"${ADB[@]}" shell getprop | tr -d '\r' > "$DEST/getprop.txt"
echo "firmware $version -> $DEST"
