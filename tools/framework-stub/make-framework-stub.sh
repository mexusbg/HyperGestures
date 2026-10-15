#!/usr/bin/env bash
# Builds prebuilts/device/framework.jar (compile-only stub) from the device's framework.jar, and
# copies the device's framework-res.apk to prebuilts/device/framework-res.apk (aapt2 links private
# @*android: resources against it; build map §3.7).
#   tools/framework-stub/make-framework-stub.sh [path/to/framework.jar [path/to/framework-res.apk]]
# Without arguments, pulls both from /system/framework/ on the connected device. With only a
# framework.jar path, framework-res.apk is taken from the same directory when it exists there,
# otherwise pulled from the device.
#
# Also records which HyperOS build the inputs come from in tools/framework-stub/device-build.txt
# (committed: ro.build.fingerprint plus the inputs' sha256) and in the stub's marker entry
# (META-INF/hg-innerclasses). The build refuses a stub whose fingerprint differs from the stamp,
# and the KernelSU module sets up only on a phone with that fingerprint. The fingerprint comes
# from $HG_DEVICE_FINGERPRINT, else the device's `getprop` dump (getprop.txt) next to the given
# framework.jar, else `adb shell getprop` when the inputs are pulled from the device.
# HG_PYTHON picks the Python 3.11+ interpreter (default: python).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/prebuilts/device"
BIN="$ROOT/tools/bin"
STAMP="$ROOT/tools/framework-stub/device-build.txt"
PY="${HG_PYTHON:-python}"
# NOTE: upstream ThexXTURBOXx/dex2jar releases are tagged/named without a "v" prefix
# (e.g. tag "2.4.38", asset "dex-tools-2.4.38.zip") — the "v$VER" scheme the original
# plan assumed 404s. Pin an exact version here so the script stays reproducible.
D2J_VER="2.4.38"
D2J_ZIP="dex-tools-$D2J_VER.zip"
D2J_URL="https://github.com/ThexXTURBOXx/dex2jar/releases/download/$D2J_VER/$D2J_ZIP"
mkdir -p "$OUT" "$BIN"

SRC="${1:-}"
if [ -z "$SRC" ]; then
  SRC="$OUT/framework-device.jar"
  MSYS2_ARG_CONV_EXCL='*' adb pull /system/framework/framework.jar "$SRC"
fi
[ -f "$SRC" ] || { echo "framework.jar not found: $SRC" >&2; exit 1; }

RES="${2:-}"
if [ -z "$RES" ] && [ -n "${1:-}" ] && [ -f "$(dirname "$SRC")/framework-res.apk" ]; then
  RES="$(dirname "$SRC")/framework-res.apk"
fi
if [ -z "$RES" ]; then
  RES="$OUT/framework-res.apk"
  MSYS2_ARG_CONV_EXCL='*' adb pull /system/framework/framework-res.apk "$RES"
fi
[ -f "$RES" ] || { echo "framework-res.apk not found: $RES" >&2; exit 1; }
if [ "$RES" != "$OUT/framework-res.apk" ]; then
  cp "$RES" "$OUT/framework-res.apk"
fi
echo "framework-res.apk -> $OUT/framework-res.apk"

FINGERPRINT="${HG_DEVICE_FINGERPRINT:-}"
if [ -z "$FINGERPRINT" ] && [ -n "${1:-}" ] && [ -f "$(dirname "$SRC")/getprop.txt" ]; then
  FINGERPRINT="$(tr -d '\r' < "$(dirname "$SRC")/getprop.txt" \
    | sed -n 's/^\[ro\.build\.fingerprint\]: \[\(.*\)\]$/\1/p' | head -n 1)"
fi
if [ -z "$FINGERPRINT" ] && [ -z "${1:-}" ]; then
  FINGERPRINT="$(adb shell getprop ro.build.fingerprint | tr -d '\r')"
fi
if [ -z "$FINGERPRINT" ]; then
  echo "cannot tell which device build $SRC comes from: put that device's getprop dump next to it" \
    "as getprop.txt, or set HG_DEVICE_FINGERPRINT to its ro.build.fingerprint" >&2
  exit 1
fi
echo "device build: $FINGERPRINT"

if [ ! -d "$BIN/dex-tools-$D2J_VER" ]; then
  curl -fL -o "$BIN/$D2J_ZIP" "$D2J_URL"
  (cd "$BIN" && unzip -q -o "$D2J_ZIP")
fi
D2J_HOME="$BIN/dex-tools-$D2J_VER"
[ -d "$D2J_HOME" ] || D2J_HOME="$(find "$BIN" -maxdepth 1 -type d -name 'dex-tools-*' | head -1)"

# Invoke dex2jar's Dex2jarCmd directly rather than through the vendored d2j-dex2jar.sh:
# that script only special-cases Cygwin (`uname -a | grep -i cygwin`) when deciding the
# classpath separator, so under Git Bash (MINGW64/MSYS, not Cygwin) it joins jars with
# ':' — which the native Windows java.exe does not accept. Building the classpath here
# with cygpath (when present) keeps this portable to plain Linux/macOS bash too.
if command -v cygpath >/dev/null 2>&1; then
  CP_SEP=';'
  to_native() { cygpath -w "$1"; }
else
  CP_SEP=':'
  to_native() { printf '%s' "$1"; }
fi
CP=""
for jar in "$D2J_HOME"/lib/*.jar; do
  native="$(to_native "$jar")"
  CP="${CP:+$CP$CP_SEP}$native"
done

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
(cd "$WORK" && unzip -q "$SRC" 'classes*.dex')
for dex in "$WORK"/classes*.dex; do
  java -Xms512m -Xmx2048m -classpath "$CP" com.googlecode.dex2jar.tools.Dex2jarCmd \
    --force -o "${dex%.dex}.jar" "$dex" >/dev/null
done

# On Windows a Gradle daemon keeps prebuilts/device/framework.jar open (every AOSP module compiles
# against it), so replacing it fails until the daemon stops.
stop_hint() {
  echo "failed to replace $OUT/framework.jar: if a Gradle daemon holds it open (Windows), run" \
    "./gradlew --stop and re-run this script" >&2
  exit 1
}

"$PY" - "$WORK" "$OUT/framework.jar" "$OUT/framework-classes.txt" <<'PY' || stop_hint
import sys, zipfile, glob, os
work, out_jar, out_list = sys.argv[1], sys.argv[2], sys.argv[3]
seen = set()
with zipfile.ZipFile(out_jar, "w", zipfile.ZIP_DEFLATED) as out:
    for jar in sorted(glob.glob(os.path.join(work, "classes*.jar"))):
        with zipfile.ZipFile(jar) as z:
            for name in z.namelist():
                if name.endswith(".class") and name not in seen:
                    seen.add(name)
                    out.writestr(name, z.read(name))
with open(out_list, "w", newline="\n") as f:
    for name in sorted(seen):
        f.write(name[:-len(".class")] + "\n")
print(f"{len(seen)} classes -> {out_jar}")
PY

# dex drops the InnerClasses entries a class file needs for every nested class it merely references
# (JVMS 4.7.6). javac/kotlinc cope; KSP2 (Dagger in :launcher) does not, so complete them. ASM comes
# with dex-tools (d2j-external). Java 17 runs the single-file source directly.
# The fingerprint goes into the jar's marker entry; the jar is replaced by a rename at the end.
java -Xmx2048m -classpath "$CP" "$(to_native "$ROOT/tools/framework-stub/AddInnerClasses.java")" \
  "$(to_native "$OUT/framework.jar")" "$FINGERPRINT" || stop_hint

sha256() {
  "$PY" -c 'import hashlib, sys; print(hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())' "$1"
}
{
  echo "# The HyperOS build that prebuilts/device/framework.jar and every device-derived value in this"
  echo "# repo come from: hidden-API signatures, the private-resource literals (aosp/PATCHES.md) and"
  echo "# their allowlist, and the AIDL tables in docs/research. Written by make-framework-stub.sh."
  echo "# The build refuses a stub with another fingerprint; the KernelSU module packages it as"
  echo "# expected-fingerprint and sets up only on a phone whose ro.build.fingerprint matches"
  echo "# (IGNORE_FINGERPRINT=1 in its config overrides)."
  echo "fingerprint=$FINGERPRINT"
  echo "framework.jar.sha256=$(sha256 "$SRC")"
  echo "framework-res.apk.sha256=$(sha256 "$OUT/framework-res.apk")"
} > "$STAMP.tmp"
mv -f "$STAMP.tmp" "$STAMP"
echo "device build stamp -> $STAMP"
