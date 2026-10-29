#!/bin/sh
# Runs module/template/common/functions.sh — and the module scripts built on it (guard,
# uninstall.sh, restore.sh, setconf.sh, action.sh) — against fixtures with stubbed device commands.
# Usage: sh tests/module/test_functions.sh   (SH=dash dash tests/module/test_functions.sh runs
# the module scripts under dash too; any POSIX sh works)
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TPL="$ROOT/module/template"
FIX="$ROOT/tests/module/fixtures"
SH="${SH:-sh}" # shell for the module scripts run as separate processes
PASS=0; FAIL=0
T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT

# The crash watch reads the dropbox directory $T/dropbox (dbe adds entries; setup leaves one
# SYSTEM_BOOT entry, as a real dropbox always has, so the directory counts as readable). Its
# fallback runs `dumpsys dropbox --print`, which every dumpsys stub answers as follows.
# Every dumpsys stub answers `dumpsys dropbox --print TAG` from $T/boxseq.TAG (file names for
# successive calls, the last one repeating; a name is looked up in $T, then in the fixtures) or
# $T/dropbox.TAG (default: no entries), and `dumpsys input` from $T/inputseq (fixture names, as
# boxseq) or $T/input (default: our swipe-up monitor registered); BODY/seq only see the rest. A dropbox
# stub exits with the status in $T/boxrc.TAG (default 0), e.g. 124 for `timeout` killing a slow dump.
DUMP_DISPATCH=$(cat <<EOF
case "\$1" in
  dropbox) if [ -f "$T/boxseq.\$3" ]; then
      n=\$(( \$(cat "$T/boxcount.\$3" 2>/dev/null || echo 0) + 1 )); echo \$n > "$T/boxcount.\$3"
      f=\$(sed -n "\${n}p" "$T/boxseq.\$3"); [ -n "\$f" ] || f=\$(tail -n 1 "$T/boxseq.\$3")
      [ -f "$T/\$f" ] && f="$T/\$f" || f="$FIX/\$f"
    else f="$T/dropbox.\$3"; [ -f "\$f" ] || f="$FIX/dropbox_empty.txt"; fi
    cat "\$f"; exit \$(cat "$T/boxrc.\$3" 2>/dev/null || echo 0) ;;
  input) if [ -f "$T/inputseq" ]; then
      n=\$(( \$(cat "$T/inputcount" 2>/dev/null || echo 0) + 1 )); echo \$n > "$T/inputcount"
      f=\$(sed -n "\${n}p" "$T/inputseq"); [ -n "\$f" ] || f=\$(tail -n 1 "$T/inputseq"); f="$FIX/\$f"
    else f="$T/input"; [ -f "\$f" ] || f="$FIX/input_monitor_ours.txt"; fi
    cat "\$f"; exit 0 ;;
esac
EOF
)
# Every settings stub keeps our Settings.Global keys (hypergestures_*) in $T/global/KEY, as the
# provider does: put writes the value, get prints it (or "null" when unset), delete removes it.
# $T/global.fail makes a put of ours fail (a provider that is not up).
GLOBAL_STORE=$(cat <<EOF
case "\$1 \$2 \$3" in
  "put global hypergestures_"*) [ -f "$T/global.fail" ] && exit 1
    mkdir -p "$T/global"; printf '%s\n' "\$4" > "$T/global/\$3"; exit 0 ;;
  "get global hypergestures_"*)
    if [ -f "$T/global/\$3" ]; then cat "$T/global/\$3"; else echo null; fi
    exit 0 ;;
  "delete global hypergestures_"*) rm -f "$T/global/\$3"; exit 0 ;;
esac
EOF
)
stub() { # stub NAME [BODY] -> records "NAME args" to $T/calls, then runs BODY
  case "$1" in
    dumpsys) _pre="$DUMP_DISPATCH" ;; settings) _pre="$GLOBAL_STORE" ;; *) _pre="" ;;
  esac
  printf '#!/bin/sh\necho "%s $*" >> "%s/calls"\n%s\n%s\n' "$1" "$T" "$_pre" "${2:-}" > "$T/$1"
  chmod +x "$T/$1"
}
seq_dumpsys() { # dumpsys answering successive SystemUI calls with the given fixtures; the last one repeats
  printf '%s\n' "$@" > "$T/dumpseq"; rm -f "$T/dumpcount"
  cat > "$T/dumpsys" <<EOF
#!/bin/sh
echo "dumpsys \$*" >> "$T/calls"
$DUMP_DISPATCH
n=\$(( \$(cat "$T/dumpcount" 2>/dev/null || echo 0) + 1 )); echo \$n > "$T/dumpcount"
f=\$(sed -n "\${n}p" "$T/dumpseq"); [ -n "\$f" ] || f=\$(tail -n 1 "$T/dumpseq")
cat "$FIX/\$f"
EOF
  chmod +x "$T/dumpsys"
}
FP='Xiaomi/nezha_eea/nezha:17/CP2A.260605.016/OS4.0.0.11.XPAEUXM:user/release-keys'
su_as() { # su running its -c script; `id -u`, `-g`, `-G` answer $1, $2, $3 (default: the uid asked)
  if [ -n "${1:-}" ]; then _u="$1"; else _u='$1'; fi
  if [ -n "${2:-}" ]; then _g="$2"; else _g='$1'; fi
  if [ -n "${3:-}" ]; then _gs="$3"; else _gs='$1 3003'; fi
  cat > "$T/su" <<EOF
#!/bin/sh
echo "su \$*" >> "$T/calls"
[ "\$2" = -c ] || exit 1
mkdir -p "$T/suid"
printf '#!/bin/sh\ncase "\$1" in -u) echo %s;; -g) echo %s;; -G) echo "%s";; esac\n' "$_u" "$_g" "$_gs" > "$T/suid/id"
chmod +x "$T/suid/id"
PATH="$T/suid:\$PATH" exec sh -c "\$3"
EOF
  chmod +x "$T/su"
}
setup() {
  rm -f "$T/calls" "$T/sleeps" "$T/dumpcount" "$T/gp" "$T/out" "$T"/dropbox.* "$T"/boxseq.* "$T"/boxcount.* "$T"/boxrc.* "$T/input" "$T/inputseq" "$T/inputcount"
  rm -rf "$T/data" "$T/mod" "$T/svc" "$T/service.d" "$T/post-fs-data.d" "$T/rescache" "$T/fakebin" \
    "$T/root" "$T/stage" "$T/mounts" "$T/dropbox" "$T/proc" "$T/modules" "$T/modules_update" "$T/metamodule" \
    "$T/upd" "$T/crashed" "$T/appfiles" "$T/global" "$T/global.fail"
  # the state dir, the dropbox, SystemUI (pid 2311) and our process (7450), and an enabled
  # metamodule that the scan finds (no /data/adb/metamodule link: mklink adds one)
  mkdir -p "$T/data" "$T/dropbox" "$T/proc/2311" "$T/proc/7450" "$T/modules/hybrid_mount"
  : > "$T/dropbox/SYSTEM_BOOT@1789990000000.txt"
  printf 'com.android.systemui' > "$T/proc/2311/cmdline"; printf 'com.hypergestures' > "$T/proc/7450/cmdline"
  printf 'id=hybrid_mount\nname=Hybrid Mount\nversionCode=170\nmetamodule=1\n' > "$T/modules/hybrid_mount/module.prop"
  : > "$T/modules/hybrid_mount/metamount.sh"
  roles com.teslacoilsw.launcher # HOME is Nova: an active boot
  echo 1 > "$T/fsg"                                    # force_fsg_nav_bar as `settings get` reports it
  printf '[x] %s\n[ ] %s\n' "$THREEBUTTON" "$GESTURAL" > "$T/overlays" # `cmd overlay list`
  echo "$FP" > "$T/fingerprint"                        # ro.build.fingerprint
  stub settings "case \"\$*\" in \"get global force_fsg_nav_bar\") cat \"$T/fsg\";; esac"
  stub overlay "case \"\$*\" in list*) cat \"$T/overlays\";; esac"
  stub pm 'case "$*" in "list packages -d"*) echo "";; "list packages -U --user 0 com.hypergestures") printf "package:com.hypergestures.other uid:10999\npackage:com.hypergestures uid:10234\n";; esac'
  stub am ''; stub appops ''; stub role 'echo com.teslacoilsw.launcher'
  stub notify ''
  su_as # runs the -c script, `id` saying the uid asked for
  _hg_ms_off=1 # no log copy jobs in the background unless a test asks (msetup)
  stub pidof 'case "$1" in com.android.systemui) echo 2311;; *) echo 7450;; esac' # pidof SystemUI / com.hypergestures
  stub reboot ''; export HG_CMD_REBOOT="$T/reboot" HG_CMD_REBOOT_FALLBACK="$T/reboot"
  pk_stub miuiAuto
  stub zcat 'exec gzip -dc "$@"'                       # records each .gz entry the crash watch opens
  stub ksud 'exit 1'                                   # no `ksud profile` unless a test says so
  stub getprop "case \"\$1\" in ro.build.fingerprint) cat \"$T/fingerprint\";; *) echo 1;; esac"
  stub dumpsys "cat \"$FIX/sysui_connected.txt\""
  stub mount ''; stub umount ''; stub chcon ''         # never the host's (mountfix sets up a payload)
  unset HG_CMD_STAT                                    # the host's stat, unless a test stubs it
  # A fake /proc/uptime (the monotonic clock hg_wait_provider reads): `sleep` and slow stubs
  # advance it, nothing really waits. $T/date answers `date +%s` from the same clock.
  echo "1000.42 3000.17" > "$T/uptime"
  printf '#!/bin/sh\ncut -d. -f1 "%s/uptime"\n' "$T" > "$T/date"; chmod +x "$T/date"
  echo boot-1 > "$T/bootid"                            # /proc/sys/kernel/random/boot_id
  export HG_CMD_SETTINGS="$T/settings" HG_CMD_OVERLAY="$T/overlay" HG_CMD_PM="$T/pm" \
         HG_CMD_AM="$T/am" HG_CMD_APPOPS="$T/appops" HG_CMD_ROLE="$T/role" \
         HG_CMD_NOTIFY="$T/notify" HG_CMD_DUMPSYS="$T/dumpsys" HG_CMD_GETPROP="$T/getprop" \
         HG_CMD_SU="$T/su" HG_CMD_CONTENT="$T/content" HG_CMD_LOG=true HG_CMD_DATE="$T/date" HG_DATA="$T/data" HG_MODDIR="$T/mod" \
         HG_GUARD="$T/service.d/hypergestures-guard.sh" HG_UPTIME="$T/uptime" HG_BOOT_ID_FILE="$T/bootid" \
         HG_CMD_PIDOF="$T/pidof" HG_CMD_KSUD="$T/ksud" HG_PKG_CACHE="$T/pkgcache" HG_RESCACHE="$T/rescache" \
         HG_OLD_OVERLAY_GUARD="$T/post-fs-data.d/hypergestures-overlay-guard.sh" \
         HG_CMD_MOUNT="$T/mount" HG_CMD_UMOUNT="$T/umount" HG_CMD_CHCON="$T/chcon" HG_STAGE="$T/stage" \
         HG_MOUNTS="$T/mounts" HG_ROOT="$T/root" HG_DROPBOX="$T/dropbox" HG_CMD_ZCAT="$T/zcat" \
         HG_PROC="$T/proc" HG_METAMODULE_LINK="$T/metamodule" HG_MODULES_DIR="$T/modules" HG_TIMEOUT="$T/no-toybox-timeout" \
         HG_ROLES_XML="$T/roles.xml" HG_LOG_MIRROR="$T/appfiles/files/module-log.txt"
  unset NAV_MODE DISABLE_MIUIHOME WATCHDOG IGNORE_FINGERPRINT STRICT_FIRMWARE MOUNT HEALTH_TEST HG_SYNC \
    _hg_cr_mode _hg_cr_seen _hg_cr_ls _hg_cr_lo _hg_cr_age _hg_cr_ref _hg_cr_fast HG_CRASH_SEEN \
    _hg_wd_miss _hg_wd_spid _hg_wd_opid _hg_wd_since _hg_wd_elapsed _hg_pw_at _hg_pw_tries HG_WD CRASH_AT _hg_roles _hg_home_boot _hg_home_told _hg_ms_wait _hg_app_uid _hg_tk \
    HG_STATE HG_HEALTH HG_STALE_APK _hg_xb _hg_xm HG_FACT_PID _hg_hfs
  tick() { advance "$1"; crash_tick; }
  . "$TPL/common/functions.sh"
  HG_HEALTH_WINDOW=15 # one health tick; the health-check tests (hsetup) use longer windows
  HG_ROLLBACK_WATCH=0 # no post-rollback watch; its own tests (rwsetup) use the real 600 s
  sleep() { # records, no waiting; a wait loop that never ends is stopped (exit 99) instead of hanging
    echo "$1" >> "$T/sleeps"; tick "$1"
    if [ "$(wc -l < "$T/sleeps")" -gt 300 ]; then echo "runaway wait loop" >&2; exit 99; fi
  }
}
advance() { # advance the clock by $1 s (one made unreadable on purpose stays so)
  _u="$(cut -d. -f1 "$T/uptime" 2>/dev/null)"; case "$_u" in ''|*[!0-9]*) return 0 ;; esac
  echo "$(( _u + $1 )).42 3000.17" > "$T/uptime"
}
tick() { advance "$1"; crash_tick; } # what `sleep` does: the clock, and crash_at's crashes; a test may swap it
T0=1790000000000 # the dropbox's epoch ms at fake uptime 0 (dbe dates entries by the fake clock)
DBN=0
dbe() { # dbe TAG PROCESS [MS] [txt|crlf|gz|dat|lost|nohdr]: adds the dropbox entry TAG@MS (default MS: now)
  if [ -n "${3:-}" ]; then _ms="$3"; else read _u _r < "$T/uptime"; DBN=$(( DBN + 1 )); _ms=$(( T0 + ${_u%%.*} * 1000 + DBN )); fi
  _f="$T/dropbox/$1@$_ms"
  case "${4:-txt}" in
    txt) printf 'Process: %s\nPID: 4242\nUID: 10187\nFlags: 0x30c8be4d\n\njava.lang.RuntimeException: test\n' "$2" > "$_f.txt" ;;
    crlf) printf 'Process: %s\r\nPID: 4242\r\n\r\n' "$2" > "$_f.txt" ;;
    gz) printf 'Process: %s\nPID: 4242\n\nsignal 11 (SIGSEGV)\n' "$2" | gzip -c > "$_f.txt.gz" ;;
    dat) printf 'Process: %s\nPID: 4242\n' "$2" > "$_f.dat" ;;
    lost) : > "$_f.lost" ;;
    nohdr) printf 'Build: x\nno process header here\n' > "$_f.txt" ;;
  esac
}
crash_at() { # crash_at N PROCESS [TAG] [COUNT] [KIND]: COUNT (2) entries once the Nth sleep has run
  CRASH_AT="$1"; CRASH_WHO="$2"; CRASH_TAG="${3:-system_app_crash}"; CRASH_N="${4:-2}"; CRASH_KIND="${5:-txt}"
}
crash_tick() {
  [ -n "${CRASH_AT:-}" ] && [ "$(wc -l < "$T/sleeps")" -ge "$CRASH_AT" ] || return 0
  CRASH_AT=""; _i=0
  while [ "$_i" -lt "$CRASH_N" ]; do dbe "$CRASH_TAG" "$CRASH_WHO" "" "$CRASH_KIND"; _i=$(( _i + 1 )); done
}
SUI=com.android.systemui
RN=0
roles() { # roles PKG [crlf|oneline|emptyhome|nohome] [new]: $T/roles.xml as RoleService writes it ("" PKG: no file);
  # new: dated later than any earlier write (as a runtime HOME change is), so the watchdog's -nt test sees it
  if [ -z "$1" ]; then rm -f "$T/roles.xml"; return 0; fi
  case "${2:-}" in
    oneline) printf '<?xml version="1.0" encoding="utf-8" standalone="yes" ?><roles version="12" packagesHash="AB"><role name="android.app.role.ASSISTANT"><holder name="com.google.android.googlequicksearchbox" /></role><role name="android.app.role.HOME"><holder name="%s" /></role></roles>' "$1" ;;
    crlf) printf '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\r\n<roles version="12" packagesHash="AB">\r\n  <role name="android.app.role.HOME">\r\n    <holder name="%s" />\r\n  </role>\r\n</roles>\r\n' "$1" ;;
    emptyhome) printf '<roles version="12">\n  <role name="android.app.role.HOME" />\n  <role name="android.app.role.SMS">\n    <holder name="%s" />\n  </role>\n</roles>\n' "$1" ;;
    nohome) printf '<roles version="12">\n  <role name="android.app.role.SMS">\n    <holder name="%s" />\n  </role>\n</roles>\n' "$1" ;;
    *) printf '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<roles version="12" packagesHash="AB">\n  <role name="android.app.role.ASSISTANT">\n    <holder name="com.google.android.googlequicksearchbox" />\n  </role>\n  <role name="android.app.role.HOME">\n    <holder name="%s" />\n  </role>\n</roles>\n' "$1" ;;
  esac > "$T/roles.xml"
  if [ "${3:-}" = new ]; then RN=$(( RN + 1 )); touch -d "@$(( 2000000000 + RN ))" "$T/roles.xml"; fi
  return 0
}
boot() { echo "boot-$1" > "$T/bootid"; }                # a later boot: a new boot id
live_nav() { # settings/overlay stubs that keep state in $T/fsg and $T/overlays, as the phone does
  stub settings "case \"\$*\" in \"get global force_fsg_nav_bar\") cat \"$T/fsg\";; \"put global force_fsg_nav_bar \"*) echo \"\$4\" > \"$T/fsg\";; esac"
  # enable-exclusive switches within the navbar category only.
  cat > "$T/overlay" <<EOF
#!/bin/sh
echo "overlay \$*" >> "$T/calls"
case "\$1" in
  list) cat "$T/overlays" ;;
  enable-exclusive) sed -e '/systemui\.navbar\./s/^\[x\]/[ ]/' -e "s|^\[ \] \$3\\\$|[x] \$3|" "$T/overlays" > "$T/ov.new" && mv "$T/ov.new" "$T/overlays" ;;
  disable) sed "s|^\[x\] \$2\\\$|[ ] \$2|" "$T/overlays" > "$T/ov.new" && mv "$T/ov.new" "$T/overlays" ;;
esac
EOF
  chmod +x "$T/overlay"
}
on() { grep -q "^\[x\] $1\$" "$T/overlays"; }           # navbar overlay $1 is enabled
notified() { grep "^notify" "$T/calls" 2>/dev/null; }  # the posted notification(s)
fakebin() { # PATH stubs for the device commands action.sh may call by name (never the host's cmd.exe)
  mkdir -p "$T/fakebin"
  for c in cmd getprop dumpsys settings pm; do printf '#!/bin/sh\necho "%s $*" >> "%s/calls"\n' "$c" "$T" > "$T/fakebin/$c"; chmod +x "$T/fakebin/$c"; done
}
mkmod() { # $T/svc: the packaged module as service.sh sees it (template + expected-fingerprint)
  rm -rf "$T/svc"; cp -R "$TPL" "$T/svc"; printf '%s\n' "${1-$FP}" > "$T/svc/expected-fingerprint"
}
pk_stub() { # PowerKeeper's provider: our row's bgControl in $T/pk ($1; "" = no row), other rows unless $2=empty
  rm -f "$T/pk" "$T/pkempty"; [ -n "$1" ] && echo "$1" > "$T/pk"; [ "${2:-}" = empty ] && : > "$T/pkempty"
  stub content "case \"\$1\" in
  query) case \"\$*\" in
      *projection\ _id*) [ -f \"$T/pkempty\" ] || echo 'Row: 0 _id=7' ;;
      *) if [ -f \"$T/pk\" ]; then echo \"Row: 0 bgControl=\$(cat \"$T/pk\")\"; else echo 'No result found.'; fi ;;
    esac ;;
  update|insert) [ -f \"$T/pkfail\" ] && exit 1
    for a in \"\$@\"; do case \"\$a\" in bgControl:s:*) echo \"\${a#bgControl:s:}\" > \"$T/pk\" ;; esac; done ;;
esac"
}
ok()     { PASS=$((PASS+1)); echo "ok   $1"; }
bad()    { FAIL=$((FAIL+1)); echo "FAIL $1"; }
has()    { grep -q -- "$1" "$T/calls" 2>/dev/null; }
before() { # the first call matching $1 comes before the first matching $2
  a=$(grep -n -- "$1" "$T/calls" | head -n 1 | cut -d: -f1); b=$(grep -n -- "$2" "$T/calls" | head -n 1 | cut -d: -f1)
  [ -n "$a" ] && [ -n "$b" ] && [ "$a" -lt "$b" ]
}
sleeps() { tr '\n' ' ' < "$T/sleeps" 2>/dev/null; }
nolock() { [ -z "$(ls -d "$T"/data/restore.lock.* 2>/dev/null)" ]; }
locks() { for l in "$T"/data/restore.lock.*; do [ -d "$l" ] && printf '%s ' "${l##*/restore.lock.}"; done; } # boot ids holding a lock
# (our own hypergestures_* settings, the state published for the app, are not a change)
nochange() { ! has "force-stop" && ! has "enable-exclusive" && ! has "overlay disable" \
  && ! grep "settings put" "$T/calls" 2>/dev/null \
    | grep -qv "^settings put global hypergestures_"; }
mountfix() { # payload in $T/mod, mount targets under $T/root; mount (BODY $1) / umount (BODY $2) record calls
  rm -rf "$T/root" "$T/stage"
  mkdir -p "$T/mod/system/priv-app/HyperGestures" "$T/mod/system/priv-app/HyperGesturesNotif" \
           "$T/mod/system/etc/permissions" "$T/mod/system/etc/sysconfig" \
           "$T/mod/overlay" "$T/root/system/priv-app" "$T/root/system/etc/permissions" \
           "$T/root/system/etc/sysconfig" "$T/root/product/overlay"
  echo apk > "$T/mod/system/priv-app/HyperGestures/HyperGestures.apk"
  echo hooks > "$T/mod/system/priv-app/HyperGesturesNotif/HyperGesturesNotif.apk" # LSPosed module
  echo rro > "$T/mod/overlay/HyperGesturesOverlay.apk"   # the static overlay, outside system/
  : > "$T/mounts"
  stub mount "${1:-}"; stub umount "${2:-}"; stub chcon ''
  export HG_CMD_MOUNT="$T/mount" HG_CMD_UMOUNT="$T/umount" HG_CMD_CHCON="$T/chcon" HG_STAGE="$T/stage" \
         HG_MOUNTS="$T/mounts" HG_ROOT="$T/root"
}
mstatus() { cat "$T/data/mount-status" 2>/dev/null; }
nomount() { ! has "^mount "; }
GESTURAL=com.android.internal.systemui.navbar.gestural
THREEBUTTON=com.android.internal.systemui.navbar.threebutton
TWOBUTTON=com.android.internal.systemui.navbar.twobutton

# --- provider detection ---------------------------------------------------------------------
setup
if hg_provider_connected < "$FIX/sysui_connected.txt"; then ok provider_connected_ours; else bad provider_connected_ours; fi
if hg_provider_connected < "$FIX/sysui_miuihome.txt"; then bad provider_rejects_miuihome; else ok provider_rejects_miuihome; fi
if hg_provider_connected < "$FIX/sysui_disconnected.txt"; then bad provider_rejects_disconnected; else ok provider_rejects_disconnected; fi
if hg_provider_connected < "$FIX/sysui_prefix_pkg.txt"; then bad provider_rejects_prefixed_pkg; else ok provider_rejects_prefixed_pkg; fi

setup
if hg_sysui_dump | hg_provider_connected && [ "$(grep -c '^dumpsys' "$T/calls")" = 1 ] \
   && has "dumpsys activity service com.android.systemui/.SystemUIService LauncherProxyService"; then
  ok dump_asks_only_for_launcher_proxy_section; else bad dump_asks_only_for_launcher_proxy_section; fi
setup; stub dumpsys "case \"\$*\" in *LauncherProxyService) ;; *) cat \"$FIX/sysui_connected.txt\";; esac"
if hg_sysui_dump | hg_provider_connected && [ "$(grep -c '^dumpsys' "$T/calls")" = 2 ]; then
  ok dump_falls_back_to_full_dump_without_section; else bad dump_falls_back_to_full_dump_without_section; fi
if grep -q "^sysui dump: the LauncherProxyService dumpable printed no section (0 bytes), so the full dump was used ([0-9]* bytes)$" "$T/data/sysui-dump"; then
  ok dump_records_that_the_full_dump_was_used; else bad "dump_records_that_the_full_dump_was_used ($(cat "$T/data/sysui-dump" 2>/dev/null))"; fi
# The first dump of a boot logs its size once, so the log tells whether SystemUI honours the
# dumpable name (a section is small; a full dump is several MB).
setup; hg_sysui_dump > /dev/null; hg_sysui_dump > /dev/null
if [ "$(grep -c '^.* sysui dump: ' "$T/data/log.txt")" = 1 ] \
   && grep -q "^sysui dump: [0-9][0-9]* bytes via the LauncherProxyService dumpable (the section alone)$" "$T/data/sysui-dump"; then
  ok dump_records_its_size_once_per_boot; else bad "dump_records_its_size_once_per_boot ($(cat "$T/data/sysui-dump" 2>/dev/null))"; fi
setup; { cat "$FIX/sysui_connected.txt"; i=0; while [ "$i" -lt 700 ]; do
  echo "padding: SystemUI printed every dumpable, not only LauncherProxyService ..................."; i=$(( i + 1 )); done; } > "$T/bigdump"
stub dumpsys "cat \"$T/bigdump\""
if hg_sysui_dump | hg_provider_connected && grep -q "(over 50 KB: SystemUI seems to ignore the dumpable name" "$T/data/sysui-dump"; then
  ok dump_flags_a_section_query_that_returned_the_full_dump; else bad "dump_flags_a_section_query_that_returned_the_full_dump ($(cat "$T/data/sysui-dump" 2>/dev/null))"; fi
# hg_timeout prefers toybox's /system/bin/timeout (no grandchild holding the pipe), else any timeout.
setup; printf '#!/bin/sh\necho "toybox-timeout $*" >> "%s/calls"\nshift; exec "$@"\n' "$T" > "$T/tbt"; chmod +x "$T/tbt"
HG_TIMEOUT="$T/tbt"; r=$(hg_timeout echo hi); HG_TIMEOUT="$T/no-toybox-timeout"; r2=$(hg_timeout echo hi)
if [ "$r/$r2" = hi/hi ] && [ "$(grep -c '^toybox-timeout 10 echo hi$' "$T/calls")" = 1 ]; then
  ok timeout_prefers_toybox_and_falls_back; else bad "timeout_prefers_toybox_and_falls_back ($r/$r2)"; fi

# --- PackageManager cache and KernelSU profile (post-fs-data) --------------------------------
setup; mkdir -p "$T/pkgcache/eb21"
for f in HyperGestures-16--1063287365 HyperGesturesNotif-16-77421093 \
         HyperGesturesOverlay.apk-16-1209505302 HyperPhone-16--882714881 com.hyperlauncher-Fam-0-1; do
  : > "$T/pkgcache/eb21/$f"; done
hg_drop_package_cache
if [ ! -e "$T/pkgcache/eb21/HyperGestures-16--1063287365" ] && [ ! -e "$T/pkgcache/eb21/HyperGesturesOverlay.apk-16-1209505302" ] \
   && [ ! -e "$T/pkgcache/eb21/HyperGesturesNotif-16-77421093" ] \
   && [ -e "$T/pkgcache/eb21/HyperPhone-16--882714881" ] && [ -e "$T/pkgcache/eb21/com.hyperlauncher-Fam-0-1" ]; then
  ok package_cache_drops_only_our_entries; else bad package_cache_drops_only_our_entries; fi
setup; rm -rf "$T/pkgcache"; if hg_drop_package_cache; then ok package_cache_missing_dir_is_fine; else bad package_cache_missing_dir_is_fine; fi

kprof() { # kprof UID UMOUNT [root]: what `ksud profile get com.hypergestures` prints (the phone's format)
  if [ "${3:-}" = root ]; then
    printf '{\n  "key": "com.hypergestures",\n  "uid": %s,\n  "stored": true,\n  "allow_su": true,\n  "root": {\n    "use_default": true,\n    "uid": 0\n  }\n}\n' "$1" > "$T/kprof"
  else
    printf '{\n  "key": "com.hypergestures",\n  "uid": %s,\n  "stored": false,\n  "allow_su": false,\n  "non_root": {\n    "use_default": true,\n    "umount_modules": %s\n  }\n}\n' "$1" "$2" > "$T/kprof"
  fi
  stub ksud "case \"\$1 \$2\" in \"profile get\") cat \"$T/kprof\";; \"profile set\") cp \"\$3\" \"$T/kset\"; cp \"\$3\" \"$T/kprof\";; *) exit 1;; esac"
}
setup; kprof 10315 true
if hg_ksu_keep_mounts && grep -q '"umount_modules": false' "$T/kset" && grep -q '"use_default": false' "$T/kset" \
   && grep -q '"stored": true' "$T/kset" && grep -q '"uid": 10315' "$T/kset" && grep -q '"key": "com.hypergestures"' "$T/kset"; then
  ok ksu_profile_keeps_mounts_for_our_package; else bad ksu_profile_keeps_mounts_for_our_package; fi
setup; kprof 10315 false
if ! hg_ksu_keep_mounts && ! has "ksud profile set"; then ok ksu_profile_already_set_is_left_alone; else bad ksu_profile_already_set_is_left_alone; fi
setup; kprof -1 true
if ! hg_ksu_keep_mounts && ! has "ksud profile set"; then ok ksu_profile_waits_for_a_uid; else bad ksu_profile_waits_for_a_uid; fi
setup; kprof 10315 true root
if ! hg_ksu_keep_mounts && ! has "ksud profile set"; then ok ksu_profile_root_profile_is_not_ours; else bad ksu_profile_root_profile_is_not_ours; fi
setup # the default ksud stub fails: no KernelSU profiles (another root solution)
if ! hg_ksu_keep_mounts && ! has "ksud profile set"; then ok ksu_profile_absent_ksud_is_a_no_op; else bad ksu_profile_absent_ksud_is_a_no_op; fi
setup; kprof 10315 true; stub ksud "case \"\$1 \$2\" in \"profile get\") cat \"$T/kprof\";; *) exit 1;; esac"
if ! hg_ksu_keep_mounts && grep -q 'could not set umount_modules false' "$T/data/log.txt"; then
  ok ksu_profile_set_failure_is_logged; else bad ksu_profile_set_failure_is_logged; fi
# `profile set` succeeds but a re-read still says umount_modules true (a renamed key, say): not a
# success, so service.sh does not force-stop our app at every boot; logged once.
setup; kprof 10315 true; stub ksud "case \"\$1 \$2\" in \"profile get\") cat \"$T/kprof\";; \"profile set\") cp \"\$3\" \"$T/kset\";; *) exit 1;; esac"
r1=0; hg_ksu_keep_mounts && r1=1; r2=0; hg_ksu_keep_mounts && r2=1
if [ "$r1$r2" = 00 ] && [ -f "$T/kset" ] && [ "$(grep -c "ksud profile set succeeded, but" "$T/data/log.txt")" = 1 ]; then
  ok ksu_profile_set_that_did_not_stick_is_not_a_success; else bad "ksu_profile_set_that_did_not_stick_is_not_a_success ($r1$r2)"; fi
setup; mkdir -p "$T/mod" "$T/pkgcache/x"; : > "$T/pkgcache/x/HyperGestures-1-2"; kprof 10315 true; hg_post_fs_data_main "$T/mod"
if [ ! -e "$T/pkgcache/x/HyperGestures-1-2" ] && [ -f "$T/kset" ]; then
  ok post_fs_data_drops_cache_and_sets_ksu_profile; else bad post_fs_data_drops_cache_and_sets_ksu_profile; fi

# --- nav modes ------------------------------------------------------------------------------
setup; hg_apply_nav_mode gestural
if has "overlay enable-exclusive --category $GESTURAL"; then ok nav_gestural; else bad nav_gestural; fi
setup; hg_apply_nav_mode threebutton
if has "overlay enable-exclusive --category $THREEBUTTON" && has "settings put global force_fsg_nav_bar 0"; then ok nav_threebutton; else bad nav_threebutton; fi
setup; hg_apply_nav_mode fsg
if before "overlay disable $GESTURAL" "settings put global force_fsg_nav_bar 1"; then ok nav_fsg_drops_gestural_overlay_first; else bad nav_fsg_drops_gestural_overlay_first; fi
setup; printf 'FSG=1\nNAVBAR=%s\n' "$THREEBUTTON" > "$T/data/original-nav.txt"; hg_apply_nav_mode fsg
if before "overlay enable-exclusive --category $THREEBUTTON" "settings put global force_fsg_nav_bar 1" && ! has "overlay disable"; then
  ok nav_fsg_restores_recorded_navbar_first; else bad nav_fsg_restores_recorded_navbar_first; fi

setup; stub cmd ''; export HG_CMD_OVERLAY="$T/cmd overlay"; hg_apply_nav_mode gestural
if has "cmd overlay enable-exclusive --category $GESTURAL"; then ok multiword_command_is_split; else bad multiword_command_is_split; fi

# --- record / restore nav state -------------------------------------------------------------
setup; hg_record_nav_state; hg_record_nav_state
if [ "$(grep -c '^FSG=1$' "$T/data/original-nav.txt")" = 1 ] && grep -q "^NAVBAR=$THREEBUTTON\$" "$T/data/original-nav.txt"; then ok record_nav_state_once; else bad record_nav_state_once; fi
setup; printf 'FSG=1\nNAVBAR=%s\n' "$THREEBUTTON" > "$T/data/original-nav.txt"; hg_restore_nav_state
if has "settings put global force_fsg_nav_bar 1" && has "overlay enable-exclusive --category $THREEBUTTON"; then ok restore_nav_state_uses_recorded_values; else bad restore_nav_state_uses_recorded_values; fi
setup; printf 'FSG=1\nNAVBAR=\n' > "$T/data/original-nav.txt"; hg_restore_nav_state
if has "overlay disable $GESTURAL" && ! has "enable-exclusive" && has "settings put global force_fsg_nav_bar 1"; then
  ok restore_with_empty_navbar_disables_gestural; else bad restore_with_empty_navbar_disables_gestural; fi
setup; printf '[ ] %s\n[x] %s\n[x] %s\n' "$GESTURAL" "$THREEBUTTON" "$TWOBUTTON" > "$T/overlays"
printf 'FSG=0\nNAVBAR=\n' > "$T/data/original-nav.txt"; hg_restore_nav_state
if has "overlay disable $GESTURAL" && has "overlay disable $THREEBUTTON" && has "overlay disable $TWOBUTTON" && ! has "enable-exclusive"; then
  ok restore_with_empty_navbar_disables_every_enabled_navbar_overlay; else bad restore_with_empty_navbar_disables_every_enabled_navbar_overlay; fi
setup; printf '[ ] %s\n[ ] %s\n' "$GESTURAL" "$THREEBUTTON" > "$T/overlays"; printf 'FSG=0\nNAVBAR=\n' > "$T/data/original-nav.txt"
if hg_restore_nav_state && [ "$(grep -c 'overlay disable' "$T/calls")" = 1 ]; then
  ok restore_with_empty_navbar_leaves_disabled_overlays_alone; else bad restore_with_empty_navbar_leaves_disabled_overlays_alone; fi
setup; printf 'FSG=null\nNAVBAR=\n' > "$T/data/original-nav.txt"; hg_restore_nav_state
if ! has "settings put"; then ok restore_leaves_unknown_fsg_alone; else bad restore_leaves_unknown_fsg_alone; fi
setup; stub overlay 'exit 1'; printf 'FSG=1\nNAVBAR=\n' > "$T/data/original-nav.txt"
if hg_restore_nav_state; then bad restore_reports_failed_command; else ok restore_reports_failed_command; fi
setup
if hg_restore_nav_state && [ ! -f "$T/calls" ]; then ok restore_without_record_changes_nothing; else bad restore_without_record_changes_nothing; fi

# --- MiuiHome -------------------------------------------------------------------------------
setup; hg_disable_miuihome
if before "appops set com.miui.home RUN_ANY_IN_BACKGROUND ignore" "am force-stop com.miui.home" && ! has "disable-user"; then
  ok disable_miuihome_denies_background_then_stops; else bad disable_miuihome_denies_background_then_stops; fi
setup; stub role 'echo com.miui.home'; hg_disable_miuihome
if ! has "force-stop" && ! has "appops set"; then ok disable_miuihome_skipped_while_it_is_home; else bad disable_miuihome_skipped_while_it_is_home; fi
setup; stub appops 'exit 1'
if ! hg_disable_miuihome && ! has "force-stop" && grep -q "MiuiHome stop FAILED" "$T/data/log.txt"; then
  ok disable_miuihome_failure_is_logged; else bad disable_miuihome_failure_is_logged; fi
setup; hg_enable_miuihome
if before "appops set com.miui.home RUN_ANY_IN_BACKGROUND default" "pm enable com.miui.home"; then ok enable_miuihome; else bad enable_miuihome; fi
setup; stub pm 'exit 1'
if hg_enable_miuihome; then bad enable_miuihome_reports_failure; else ok enable_miuihome_reports_failure; fi

# --- notifications --------------------------------------------------------------------------
setup; hg_notify "hello"
if has "notify -S bigtext -t HyperGestures hypergestures hello" && ! has "^su "; then ok notify_posts_as_root; else bad notify_posts_as_root; fi
setup; stub notify 'exit 1'
if hg_notify "hello" && has "^su 2000 -c .*notify -S bigtext -t HyperGestures hypergestures"; then ok notify_retries_as_shell_uid; else bad notify_retries_as_shell_uid; fi
# Our app's HgNotifyReceiver first: it answers result 1 once it has posted (am's own output
# format, CRLF too); anything else falls back to `cmd notification post`.
AM_NOTIFY="am broadcast --receiver-foreground --include-stopped-packages -a com.hypergestures.action.NOTIFY -n com.hypergestures/com.hypergestures.HgNotifyReceiver --es text"
am_says() { # am_says LINE...: an am stub whose broadcast prints these lines
  _b="case \"\$1\" in broadcast) echo 'Broadcasting: Intent { flg=0x10400030 cmp=com.hypergestures/.HgNotifyReceiver (has extras) }'"
  for _l in "$@"; do _b="$_b; printf '%s\\r\\n' '$_l'"; done
  stub am "$_b;; esac"
}
setup; am_says "Broadcast completed: result=1"
if hg_notify "hello" && has "^$AM_NOTIFY hello --es tag hypergestures$" && ! notified && ! has "^su "; then
  ok notify_posts_through_the_app; else bad notify_posts_through_the_app; fi
for _r in 0 10 -1; do
  setup; am_says "Broadcast completed: result=$_r"
  if hg_notify "hello" && has "^$AM_NOTIFY hello" && [ "$(notified)" = "notify -S bigtext -t HyperGestures hypergestures hello" ]; then
    ok "notify_falls_back_when_the_app_answers_$_r"; else bad "notify_falls_back_when_the_app_answers_$_r"; fi
done
setup; stub am 'echo "Error: Broadcast to com.hypergestures failed" >&2; exit 1'
if hg_notify "hello" && has "^$AM_NOTIFY hello" && [ "$(notified)" = "notify -S bigtext -t HyperGestures hypergestures hello" ]; then
  ok notify_falls_back_when_am_fails; else bad notify_falls_back_when_am_fails; fi
setup; am_says "Broadcast completed: result=1"; stub notify 'exit 1'
if hg_notify "hello" && ! notified && ! has "^su "; then ok notify_through_the_app_needs_no_shell_uid; else bad notify_through_the_app_needs_no_shell_uid; fi
# The text reaches the receiver as one argument, byte for byte: quotes, $, backticks, newlines.
setup; stub am "while [ \$# -gt 0 ]; do [ \"\$1\" = text ] && printf '%s' \"\$2\" > \"$T/amtext\"; shift; done; echo 'Broadcast completed: result=1'"
_msg="$(printf 'It'\''s "$HOME" `x`\nline 2: '\''q'\'' \\ end')"
hg_notify "$_msg"
if [ "$(cat "$T/amtext" 2>/dev/null)" = "$_msg" ] && ! notified; then
  ok notify_app_text_is_passed_verbatim; else bad "notify_app_text_is_passed_verbatim ($(cat "$T/amtext" 2>/dev/null))"; fi
# POST_NOTIFICATIONS for our app, once per boot, before setup can notify; a refused grant changes nothing.
setup; hg_grant_notifications
if has "^pm grant com.hypergestures android.permission.POST_NOTIFICATIONS$"; then ok grant_notifications; else bad grant_notifications; fi
setup; stub pm 'case "$1" in grant) echo "Exception occurred while executing grant" >&2; exit 255;; esac'
if hg_grant_notifications; then ok grant_notifications_ignores_failure; else bad grant_notifications_ignores_failure; fi

# --- watchdog -------------------------------------------------------------------------------
# The watchdog checks SystemUI's bind only when SystemUI or our process is no longer the one it saw
# (its pid gone, or reused by another process), after a miss, and every 30 minutes; otherwise a
# round runs no command at all. wsetup: a module dir, the crash watch's reference, a fresh watchdog;
# wtick N: what the loop's sleep N adds (the clock and the watchdog's own time).
wsetup() { setup; mkdir -p "$T/mod"; hg_crash_reset; hg_watchdog_init; }
wd() { hg_watchdog_step "$T/mod"; }
wtick() { advance "$1"; _hg_wd_since=$(( _hg_wd_since + $1 )); _hg_wd_elapsed=$(( _hg_wd_elapsed + $1 )); }
sysui_moves() { # SystemUI restarted as pid $1
  rm -rf "$T/proc/2311" "$T/proc/$1"; mkdir -p "$T/proc/$1"; printf 'com.android.systemui' > "$T/proc/$1/cmdline"
  stub pidof "case \"\$1\" in com.android.systemui) echo $1;; *) echo 7450;; esac"
}
wsetup; stub dumpsys "cat \"$FIX/sysui_disconnected.txt\""
wd; a=$HG_WD; wd; b=$HG_WD; wd; r=$HG_WD
if [ "$a/$b/$r" = 1/2/fallback ] && has "navbar.threebutton" && has "notify" && [ "$(grep -c '^dumpsys activity' "$T/calls")" = 3 ]; then
  ok watchdog_falls_back_after_3_failures; else bad "watchdog_falls_back_after_3_failures ($a/$b/$r)"; fi
if grep -q "^NAVBAR=$THREEBUTTON\$" "$T/data/original-nav.txt" 2>/dev/null && before "overlay list" "enable-exclusive"; then
  ok watchdog_records_nav_state_before_fallback; else bad watchdog_records_nav_state_before_fallback; fi
wsetup; _hg_wd_miss=2; wd
if [ "$HG_WD" = 0 ] && [ "$_hg_wd_miss" = 0 ] && ! has "navbar.threebutton"; then ok watchdog_stays_when_connected; else bad watchdog_stays_when_connected; fi
wsetup; wd
if [ "$HG_WD" = 0 ] && has "^dumpsys activity service" && has "^pidof com.android.systemui" && has "^pidof com.hypergestures" \
   && [ "$_hg_wd_spid/$_hg_wd_opid" = 2311/7450 ]; then
  ok watchdog_first_round_checks_the_bind_and_remembers_the_pids; else bad "watchdog_first_round_checks_the_bind_and_remembers_the_pids ($_hg_wd_spid/$_hg_wd_opid)"; fi
dbe system_app_crash "$SUI"; dbe data_app_crash com.example # one SystemUI crash and an unrelated one: nothing to act on
wtick 120; wd; ls -l "$T/data" > "$T/ls1"; rm -f "$T/calls"; wtick 600; wd; ls -l "$T/data" > "$T/ls2"
if [ "$HG_WD" = 0 ] && [ ! -f "$T/calls" ] && cmp -s "$T/ls1" "$T/ls2"; then
  ok watchdog_round_with_nothing_changed_runs_no_command_and_writes_nothing
else bad "watchdog_round_with_nothing_changed_runs_no_command_and_writes_nothing ($(tr '\n' '|' < "$T/calls" 2>/dev/null))"; fi
wsetup; NAV_MODE=gestural; printf '[ ] %s\n[x] %s\n' "$THREEBUTTON" "$GESTURAL" > "$T/overlays"; wd; rm -f "$T/calls"
wtick 120; wd; a=0; has "^overlay list" && a=1; rm -f "$T/calls"; wtick 480; wd; b=0; has "^overlay list" && b=1
if [ "$a$b" = 10 ] && ! has "^dumpsys"; then ok watchdog_watches_for_a_gestural_revert_every_round_only_for_10_minutes
else bad "watchdog_watches_for_a_gestural_revert_every_round_only_for_10_minutes ($a$b)"; fi
wsetup; wd; rm -f "$T/calls"; sysui_moves 2400; wd; a="$HG_WD/$_hg_wd_spid"; d1=0; has "^dumpsys activity" && d1=1
rm -f "$T/calls"; wd
if [ "$a" = 0/2400 ] && [ "$d1" = 1 ] && [ "$HG_WD" = 0 ] && ! has "^dumpsys"; then
  ok watchdog_checks_the_bind_after_a_systemui_restart_then_goes_quiet; else bad "watchdog_checks_the_bind_after_a_systemui_restart_then_goes_quiet ($a $d1)"; fi
wsetup; wd; rm -f "$T/calls"; printf 'com.example.other' > "$T/proc/7450/cmdline"; wd # our pid now belongs to another process
if has "^dumpsys activity" && has "^pidof com.hypergestures"; then ok watchdog_checks_the_bind_when_our_pid_was_reused
else bad watchdog_checks_the_bind_when_our_pid_was_reused; fi
wsetup; wd; rm -f "$T/calls"; rm -rf "$T/proc/7450"; wd
if has "^dumpsys activity"; then ok watchdog_checks_the_bind_when_our_process_died; else bad watchdog_checks_the_bind_when_our_process_died; fi
wsetup; NAV_MODE=gestural; printf '[ ] %s\n[x] %s\n' "$THREEBUTTON" "$GESTURAL" > "$T/overlays"; wd; wtick 1680; rm -f "$T/calls"
wd; a=0; has "^dumpsys" && a=1; wtick 120; wd
if [ "$a" = 0 ] && has "^dumpsys activity" && has "^overlay list" && [ "$_hg_wd_since" = 0 ]; then
  ok watchdog_rechecks_the_bind_and_gestural_every_30_minutes; else bad "watchdog_rechecks_the_bind_and_gestural_every_30_minutes ($a)"; fi
wsetup; stub pidof ''; wd; wd; wd
if [ "$HG_WD" = 0 ] && [ "$(grep -c '^dumpsys activity' "$T/calls")" = 3 ] \
   && [ "$(grep -c "watchdog: pidof printed nothing" "$T/data/log.txt")" = 1 ]; then
  ok watchdog_without_pids_checks_the_bind_every_round; else bad watchdog_without_pids_checks_the_bind_every_round; fi
setup; hg_crash_reset; seq_dumpsys sysui_connected.txt sysui_disconnected.txt
tick() { advance "$1"; [ "$(wc -l < "$T/sleeps")" = 2 ] && rm -rf "$T/proc/2311"; return 0; } # SystemUI dies in the 2nd sleep
hg_watchdog_loop; tick() { advance "$1"; crash_tick; }
if [ "$(sleeps)" = "120 120 30 30 " ] && has "navbar.threebutton" && [ "$(grep -c '^dumpsys activity' "$T/calls")" = 4 ]; then
  ok watchdog_ticks_every_120s_while_bound_30s_after_a_miss; else bad "watchdog_ticks_every_120s_while_bound_30s_after_a_miss ($(sleeps))"; fi
# Nothing left to guard: restore.sh ran, the module is disabled or marked for removal, or WATCHDOG=0 was set.
for m in restored disable remove watchdog-stop; do
  wsetup; case "$m" in restored|watchdog-stop) : > "$T/data/$m" ;; *) : > "$T/mod/$m" ;; esac; wd
  if [ "$HG_WD" = stop ] && [ ! -f "$T/calls" ] && grep -q "watchdog: stopped (" "$T/data/log.txt"; then ok "watchdog_stops_when_$m"
  else bad "watchdog_stops_when_$m ($HG_WD)"; fi
done
wsetup; tick() { advance "$1"; : > "$T/mod/disable"; }; hg_watchdog_loop "$T/mod"; tick() { advance "$1"; crash_tick; }
if [ "$(sleeps)" = "120 " ] && grep -q "watchdog: stopped (the module is disabled)" "$T/data/log.txt"; then
  ok watchdog_loop_ends_once_the_module_is_disabled; else bad "watchdog_loop_ends_once_the_module_is_disabled ($(sleeps))"; fi

# --- boot: bind-gated setup -----------------------------------------------------------------
ones() { _n=$1; _s=""; while [ "$_n" -gt 0 ]; do _s="${_s}1 "; _n=$((_n - 1)); done; echo "$_s"; }
# The wait checks every 5 s and reads the app's value every second in between: 1 s sleeps.
setup; seq_dumpsys sysui_disconnected.txt sysui_disconnected.txt sysui_connected.txt
if hg_wait_provider 60 && [ "$(sleeps)" = "$(ones 10)" ]; then ok wait_provider_polls_until_bound; else bad wait_provider_polls_until_bound; fi
setup; stub dumpsys "cat \"$FIX/sysui_disconnected.txt\""
if hg_wait_provider 10; then bad wait_provider_gives_up; else ok wait_provider_gives_up; fi
setup; cat > "$T/dumpsys" <<EOF
#!/bin/sh
echo "dumpsys \$*" >> "$T/calls"
echo "\$(( \$(cut -d. -f1 "$T/uptime") + 20 )).42 3000.17" > "$T/uptime"
cat "$FIX/sysui_disconnected.txt"
EOF
chmod +x "$T/dumpsys" # each dump takes 20 s: the 60 s budget allows 3 dumps, not 13 (5 s sleeps only)
if ! hg_wait_provider 60 && [ "$(grep -c '^dumpsys' "$T/calls")" = 3 ]; then
  ok wait_provider_budget_counts_slow_dumps; else bad "wait_provider_budget_counts_slow_dumps ($(grep -c '^dumpsys' "$T/calls") dumps)"; fi
# The budget runs on /proc/uptime, which NTP or a clock change can't move. A wall clock jumping
# back 30 s at every read (and one that reads empty) must not stretch or break the wait.
setup; stub dumpsys "cat \"$FIX/sysui_disconnected.txt\""
printf '#!/bin/sh\nn=$(cat "%s/wall" 2>/dev/null || echo 5000); echo $((n - 30)) > "%s/wall"; echo $((n - 30))\n' "$T" "$T" > "$T/date"
( hg_wait_provider 60 ); r=$?
if [ "$r" = 1 ] && [ "$(grep -c '^dumpsys' "$T/calls")" = 13 ]; then
  ok wait_provider_ignores_wall_clock_jumps; else bad "wait_provider_ignores_wall_clock_jumps (rc=$r, $(grep -c '^dumpsys' "$T/calls") dumps)"; fi
# Without a readable clock the wait is still bounded: at most $1/5+1 dumps, 5 s apart.
setup; stub dumpsys "cat \"$FIX/sysui_disconnected.txt\""; printf '#!/bin/sh\n' > "$T/date"
export HG_UPTIME="$T/no-such-uptime"
( hg_wait_provider 60 ); r=$?
if [ "$r" = 1 ] && [ "$(grep -c '^dumpsys' "$T/calls")" = 13 ] && [ "$(sleeps)" = "$(ones 60)" ]; then
  ok wait_provider_without_clock_is_bounded; else bad "wait_provider_without_clock_is_bounded (rc=$r, $(grep -c '^dumpsys' "$T/calls") dumps)"; fi
setup; stub dumpsys "cat \"$FIX/sysui_disconnected.txt\""; echo "garbage" > "$T/uptime"; printf '#!/bin/sh\necho x\n' > "$T/date"
( hg_wait_provider 10 ); r=$?
if [ "$r" = 1 ] && [ "$(grep -c '^dumpsys' "$T/calls")" = 3 ]; then
  ok wait_provider_with_unparsable_clock_is_bounded; else bad "wait_provider_with_unparsable_clock_is_bounded (rc=$r, $(grep -c '^dumpsys' "$T/calls") dumps)"; fi
setup; stub dumpsys "cat \"$FIX/sysui_disconnected.txt\""
tick() { :; } # a clock that never advances
( hg_wait_provider 60 ); r=$?
tick() { advance "$1"; }
if [ "$r" = 1 ] && [ "$(grep -c '^dumpsys' "$T/calls")" = 13 ]; then
  ok wait_provider_with_stuck_clock_is_bounded; else bad "wait_provider_with_stuck_clock_is_bounded (rc=$r, $(grep -c '^dumpsys' "$T/calls") dumps)"; fi

setup; mkmod; printf 'NAV_MODE=gestural\nDISABLE_MIUIHOME=1\nWATCHDOG=0\n' > "$T/data/config"
touch "$T/data/guard-restored" "$T/data/restore-gave-up"; echo 3 > "$T/data/restore-attempts"; mkdir "$T/data/restore.lock.boot-0"
hg_service_main "$T/svc"
if [ -f "$T/data/original-nav.txt" ] && has "am force-stop com.miui.home" && has "overlay enable-exclusive --category $GESTURAL" \
   && before "am force-stop" "overlay enable-exclusive"; then
  ok service_applies_changes_once_bound; else bad service_applies_changes_once_bound; fi
if [ -x "$HG_GUARD" ] && cmp -s "$TPL/common/hypergestures-guard.sh" "$HG_GUARD" && cmp -s "$TPL/common/functions.sh" "$T/data/functions.sh"    && [ -z "$(ls -A "$T/data" | grep '^\.')" ]; then
  ok service_installs_guard_and_functions_copy; else bad service_installs_guard_and_functions_copy; fi
if [ ! -f "$T/data/guard-restored" ] && [ ! -f "$T/data/restore-gave-up" ] && [ ! -f "$T/data/restore-attempts" ] && nolock \
   && [ ! -f "$T/svc/disable" ]; then
  ok service_clears_guard_markers_and_stale_locks; else bad service_clears_guard_markers_and_stale_locks; fi

setup; mkmod; printf 'NAV_MODE=gestural\nDISABLE_MIUIHOME=1\nWATCHDOG=0\n' > "$T/data/config"; : > "$T/notadir"
HG_GUARD="$T/notadir/hypergestures-guard.sh"; hg_service_main "$T/svc"
if nochange && ! has "^dumpsys activity" && has "^notify" && [ ! -f "$T/data/original-nav.txt" ]; then
  ok service_aborts_setup_when_guard_install_fails; else bad service_aborts_setup_when_guard_install_fails; fi

# Not bound: changes nothing, unless a gesture nav mode is on while no provider at all is bound
# (then the phone has no navigation, and 3-button is the only safe mode).
setup; mkmod; printf 'NAV_MODE=gestural\nDISABLE_MIUIHOME=1\nWATCHDOG=1\n' > "$T/data/config"; echo 0 > "$T/fsg"
stub dumpsys "cat \"$FIX/sysui_disconnected.txt\""; hg_service_main "$T/svc"
if [ ! -f "$T/data/original-nav.txt" ] && nochange && has "^notify" && [ -x "$HG_GUARD" ]; then
  ok service_unbound_changes_nothing; else bad service_unbound_changes_nothing; fi
setup; mkmod; printf 'NAV_MODE=gestural\nDISABLE_MIUIHOME=1\nWATCHDOG=1\n' > "$T/data/config"; echo 0 > "$T/fsg"
printf '[x] %s\n[ ] %s\n' "$GESTURAL" "$THREEBUTTON" > "$T/overlays"; stub dumpsys "cat \"$FIX/sysui_disconnected.txt\""
hg_service_main "$T/svc"
if has "overlay enable-exclusive --category $THREEBUTTON" && has "settings put global force_fsg_nav_bar 0" && ! has "force-stop" && has "^notify"; then
  ok service_unbound_with_gestural_on_falls_back_to_threebutton; else bad service_unbound_with_gestural_on_falls_back_to_threebutton; fi
setup; mkmod; printf 'NAV_MODE=gestural\nDISABLE_MIUIHOME=1\nWATCHDOG=1\n' > "$T/data/config"; echo 0 > "$T/fsg"
printf 'FSG=0\nNAVBAR=\n' > "$T/data/original-nav.txt"; stub dumpsys "cat \"$FIX/sysui_disconnected.txt\""
hg_service_main "$T/svc"
if nochange; then ok service_unbound_after_earlier_restore_changes_nothing; else bad service_unbound_after_earlier_restore_changes_nothing; fi
setup; mkmod; printf 'NAV_MODE=gestural\nDISABLE_MIUIHOME=1\nWATCHDOG=1\n' > "$T/data/config"; stub dumpsys "cat \"$FIX/sysui_disconnected.txt\""
hg_service_main "$T/svc" # fsg=1, gestural off, nothing bound
if has "overlay enable-exclusive --category $THREEBUTTON" && ! has "force-stop"; then
  ok service_unbound_with_fsg_on_falls_back_to_threebutton; else bad service_unbound_with_fsg_on_falls_back_to_threebutton; fi
setup; mkmod; printf 'NAV_MODE=gestural\nDISABLE_MIUIHOME=1\nWATCHDOG=1\n' > "$T/data/config"
printf '[x] %s\n' "$GESTURAL" > "$T/overlays"; stub dumpsys "cat \"$FIX/sysui_miuihome.txt\""
hg_service_main "$T/svc" # our overlay not mounted: SystemUI still binds MiuiHome, gestures work
if nochange && has "^notify"; then ok service_unbound_while_miuihome_bound_changes_nothing; else bad service_unbound_while_miuihome_bound_changes_nothing; fi

# The unbound fallback is undoable: the nav state it replaces is recorded first, so restore.sh
# (hg_restore_all) brings back the original, and a later bound boot does not record 3-button as it.
setup; live_nav; mkmod; printf 'NAV_MODE=gestural\nDISABLE_MIUIHOME=1\nWATCHDOG=0\n' > "$T/data/config"
printf '[x] %s\n[ ] %s\n' "$GESTURAL" "$THREEBUTTON" > "$T/overlays"; echo 1 > "$T/fsg"
stub dumpsys "cat \"$FIX/sysui_disconnected.txt\""
hg_service_main "$T/svc" # fresh install: no original-nav.txt yet
if on "$THREEBUTTON" && ! on "$GESTURAL" && [ "$(cat "$T/fsg")" = 0 ]; then fell_back=1; else fell_back=0; fi
hg_restore_all
if [ "$fell_back" = 1 ] && on "$GESTURAL" && ! on "$THREEBUTTON" && [ "$(cat "$T/fsg")" = 1 ] \
   && grep -q "^NAVBAR=$GESTURAL\$" "$T/data/original-nav.txt" && grep -q '^FSG=1$' "$T/data/original-nav.txt"; then
  ok service_unbound_fallback_is_undone_by_restore; else bad "service_unbound_fallback_is_undone_by_restore (fell_back=$fell_back fsg=$(cat "$T/fsg"))"; fi
hg_apply_nav_mode threebutton; stub dumpsys "cat \"$FIX/sysui_connected.txt\""
hg_service_main "$T/svc" # next boot: bound; the original must still be the pre-fallback state
if grep -q "^NAVBAR=$GESTURAL\$" "$T/data/original-nav.txt" && grep -q '^FSG=1$' "$T/data/original-nav.txt"; then
  ok service_bound_after_fallback_keeps_true_original; else bad service_bound_after_fallback_keeps_true_original; fi

# Strict firmware pinning (STRICT_FIRMWARE=1): the module only sets up on the HyperOS build it was
# compiled against. On any other build (typically after an OTA with the module still enabled) it
# disables itself from the next boot, so the guard returns the phone to stock, and for the rest of
# this boot it moves a gesture nav mode to 3 buttons, even while a provider is bound (the stale APK
# is not trusted).
OTA='Xiaomi/nezha_eea/nezha:17/CP2A.270101.001/OS4.0.1.0.XPAEUXM:user/release-keys'
SELF_OFF="HyperGestures disabled itself; reboot to return to stock. Rebuild for the new firmware, or set IGNORE_FINGERPRINT=1 to test anyway."
setup; live_nav; mkmod; echo "$OTA" > "$T/fingerprint"
printf 'NAV_MODE=gestural\nDISABLE_MIUIHOME=1\nWATCHDOG=1\nSTRICT_FIRMWARE=1\n' > "$T/data/config"
printf '[x] %s\n[ ] %s\n' "$GESTURAL" "$THREEBUTTON" > "$T/overlays"; echo 0 > "$T/fsg"
stub pm 'case "$*" in "list packages -d"*) echo "package:com.miui.home";; esac' # disabled by earlier boots
hg_service_main "$T/svc" # the stale APK is still bound (sysui_connected)
if [ -f "$T/svc/disable" ] && grep -q "^NAVBAR=$GESTURAL\$" "$T/data/original-nav.txt" && grep -q '^FSG=0$' "$T/data/original-nav.txt" \
   && on "$THREEBUTTON" && ! on "$GESTURAL" && before "overlay list" "enable-exclusive" && ! has "force-stop" && ! has "^pm enable" \
   && notified | grep -qF "Firmware changed ($FP → $OTA). $SELF_OFF" && [ "$(notified | grep -c .)" = 1 ] \
   && grep -q "firmware mismatch: built for $FP, this phone runs $OTA; disabled the module" "$T/data/log.txt" \
   && [ ! -f "$T/sleeps" ]; then
  ok service_firmware_mismatch_disables_module_and_falls_back_while_bound
else bad service_firmware_mismatch_disables_module_and_falls_back_while_bound; fi
HG_SYNC=1 HG_MODDIR="$T/svc" $SH "$HG_GUARD" # next boot: the module is disabled, the guard restores
if has "pm enable com.miui.home" && on "$GESTURAL" && ! on "$THREEBUTTON" && [ "$(cat "$T/fsg")" = 0 ] && [ -f "$T/data/guard-restored" ]; then
  ok firmware_self_disable_then_guard_restores_at_next_boot; else bad firmware_self_disable_then_guard_restores_at_next_boot; fi
setup; live_nav; mkmod; echo "$OTA" > "$T/fingerprint"; printf 'NAV_MODE=gestural\nSTRICT_FIRMWARE=1\n' > "$T/data/config"
printf 'FSG=1\nNAVBAR=\n' > "$T/data/original-nav.txt" # recorded on the old build, before the module changed nav
printf '[x] %s\n[ ] %s\n' "$GESTURAL" "$THREEBUTTON" > "$T/overlays"; echo 0 > "$T/fsg"
hg_service_main "$T/svc"
if [ -f "$T/svc/disable" ] && on "$THREEBUTTON" && [ "$(printf 'FSG=1\nNAVBAR=\n')" = "$(cat "$T/data/original-nav.txt")" ]; then
  ok service_firmware_mismatch_keeps_earlier_recorded_original; else bad service_firmware_mismatch_keeps_earlier_recorded_original; fi
setup; mkmod "$OTA"; printf 'NAV_MODE=gestural\nDISABLE_MIUIHOME=1\nWATCHDOG=1\nSTRICT_FIRMWARE=1\n' > "$T/data/config"
echo 0 > "$T/fsg" # stock 3-button: threebutton overlay on, no gesture mode
hg_service_main "$T/svc"
if [ -f "$T/svc/disable" ] && nochange && [ ! -f "$T/data/original-nav.txt" ] && notified | grep -qF "$SELF_OFF" \
   && ! notified | grep -q "3 buttons" && has "getprop ro.build.fingerprint"; then
  ok service_firmware_mismatch_on_stock_nav_only_disables; else bad service_firmware_mismatch_on_stock_nav_only_disables; fi
setup; mkmod 'Xiaomi/other/other:17/X/Y:user/release-keys'; printf 'NAV_MODE=gestural\nDISABLE_MIUIHOME=1\nWATCHDOG=1\nSTRICT_FIRMWARE=1\n' > "$T/data/config"
printf '[x] %s\n' "$GESTURAL" > "$T/overlays"; echo 0 > "$T/fsg"; stub dumpsys "cat \"$FIX/sysui_disconnected.txt\""
hg_service_main "$T/svc"
if [ -f "$T/svc/disable" ] && has "overlay enable-exclusive --category $THREEBUTTON" && ! has "force-stop"; then
  ok service_firmware_mismatch_without_provider_falls_back_to_threebutton; else bad service_firmware_mismatch_without_provider_falls_back_to_threebutton; fi
setup; mkmod "$OTA"; printf 'NAV_MODE=gestural\nDISABLE_MIUIHOME=1\nWATCHDOG=1\nSTRICT_FIRMWARE=1\n' > "$T/data/config"; mkdir "$T/svc/disable"
hg_service_main "$T/svc" # the disable flag can't be written (a directory is in the way)
if notified | grep -q "could not disable itself" && ! notified | grep -qF "$SELF_OFF" && grep -q "could not disable" "$T/data/log.txt" \
   && has "overlay enable-exclusive --category $THREEBUTTON"; then
  ok service_firmware_mismatch_reports_failed_self_disable; else bad service_firmware_mismatch_reports_failed_self_disable; fi
setup; mkmod 'Xiaomi/other/other:17/X/Y:user/release-keys'
printf 'NAV_MODE=gestural\nDISABLE_MIUIHOME=1\nWATCHDOG=0\nSTRICT_FIRMWARE=1\nIGNORE_FINGERPRINT=1\n' > "$T/data/config"; hg_service_main "$T/svc"
if has "am force-stop" && has "overlay enable-exclusive --category $GESTURAL" && [ ! -f "$T/svc/disable" ] \
   && grep -q "firmware mismatch.*ignored (IGNORE_FINGERPRINT=1)" "$T/data/log.txt" && ! notified | grep -q "disabled itself"; then
  ok service_ignore_fingerprint_sets_up_anyway; else bad service_ignore_fingerprint_sets_up_anyway; fi
setup; mkmod; rm -f "$T/svc/expected-fingerprint"; printf 'NAV_MODE=gestural\nDISABLE_MIUIHOME=1\nWATCHDOG=1\nSTRICT_FIRMWARE=1\n' > "$T/data/config"
hg_service_main "$T/svc"
if [ -f "$T/svc/disable" ] && ! has "force-stop" && grep -q "expected-fingerprint" "$T/data/log.txt" \
   && notified | grep -q "Firmware check impossible"; then
  ok service_without_expected_fingerprint_disables_itself; else bad service_without_expected_fingerprint_disables_itself; fi

setup; mkmod; printf 'NAV_MODE=keep\n' > "$T/data/config"
seq_dumpsys sysui_connected.txt sysui_connected.txt sysui_connected.txt sysui_connected.txt sysui_disconnected.txt
tick() { advance "$1"; [ "$(wc -l < "$T/sleeps")" = 2 ] && rm -rf "$T/proc/2311"; return 0; } # SystemUI dies after the window
hg_service_main "$T/svc" # bound through the health window, then the watchdog sees it go
tick() { advance "$1"; crash_tick; }
if grep -q 'DISABLE_MIUIHOME=1 WATCHDOG=1 IGNORE_FINGERPRINT=0 STRICT_FIRMWARE=0' "$T/data/log.txt" && has "am force-stop" \
   && has "navbar.threebutton" && [ ! -e "$T/svc/disable" ] && grep -q '^ok' "$T/data/health-status"; then
  ok service_defaults_values_missing_from_old_config; else bad service_defaults_values_missing_from_old_config; fi

# --- guard decision -------------------------------------------------------------------------
setup; mkdir -p "$T/mod"
if [ "$(hg_guard_decide "$T/mod" "$T/data")" = none ]; then ok guard_idle_while_module_enabled; else bad guard_idle_while_module_enabled; fi
touch "$T/mod/disable"
if [ "$(hg_guard_decide "$T/mod" "$T/data")" = restore ]; then ok guard_restores_when_disabled; else bad guard_restores_when_disabled; fi
touch "$T/data/guard-restored"
if [ "$(hg_guard_decide "$T/mod" "$T/data")" = none ]; then ok guard_marker_prevents_repeat; else bad guard_marker_prevents_repeat; fi
rm -f "$T/mod/disable" "$T/data/guard-restored"; touch "$T/mod/remove"
if [ "$(hg_guard_decide "$T/mod" "$T/data")" = restore ]; then ok guard_restores_when_marked_for_removal; else bad guard_restores_when_marked_for_removal; fi
rm -rf "$T/mod"; touch "$T/data/guard-restored"
if [ "$(hg_guard_decide "$T/mod" "$T/data")" = restore_and_cleanup ]; then ok guard_restores_and_cleans_up_when_module_gone; else bad guard_restores_and_cleans_up_when_module_gone; fi

# --- deferred restore (shared by the guard and uninstall.sh) ---------------------------------
setup; printf 'FSG=1\nNAVBAR=\n' > "$T/data/original-nav.txt"; mkdir -p "$T/service.d"; touch "$HG_GUARD"
hg_restore_after_boot cleanup
if has "pm enable com.miui.home" && has "overlay disable $GESTURAL" && [ ! -e "$T/data" ] && [ ! -e "$HG_GUARD" ]; then
  ok restore_after_boot_cleanup_removes_state_and_guard; else bad restore_after_boot_cleanup_removes_state_and_guard; fi
setup; printf 'FSG=1\nNAVBAR=\n' > "$T/data/original-nav.txt"; hg_restore_after_boot mark
if has "pm enable com.miui.home" && [ -f "$T/data/guard-restored" ] && [ -f "$T/data/original-nav.txt" ] && nolock; then
  ok restore_after_boot_mark_leaves_marker; else bad restore_after_boot_mark_leaves_marker; fi
setup; printf 'FSG=1\nNAVBAR=\n' > "$T/data/original-nav.txt"; mkdir "$T/data/restore.lock.$(hg_boot_id)"
hg_restore_after_boot cleanup
if ! has "pm enable" && [ -f "$T/data/original-nav.txt" ]; then ok restore_after_boot_second_actor_does_nothing; else bad restore_after_boot_second_actor_does_nothing; fi
setup; stub pm 'case "$*" in enable*) exit 1;; esac'; printf 'FSG=1\nNAVBAR=\n' > "$T/data/original-nav.txt"
mkdir -p "$T/service.d"; touch "$HG_GUARD"
if hg_restore_after_boot cleanup; then r=0; else r=1; fi
if [ "$r" = 1 ] && [ -f "$T/data/original-nav.txt" ] && [ -e "$HG_GUARD" ] && [ ! -f "$T/data/guard-restored" ] && [ "$(locks)" = "boot-1 " ]; then
  ok restore_after_boot_failure_keeps_state_for_retry; else bad restore_after_boot_failure_keeps_state_for_retry; fi
# On the uninstall boot both uninstall.sh's job and the guard run the deferred restore. A failed
# attempt keeps this boot's lock, so the second actor does not try again: one boot, one attempt.
setup; stub pm 'case "$*" in enable*) exit 1;; esac'; printf 'FSG=0\nNAVBAR=\n' > "$T/data/original-nav.txt"
mkdir -p "$T/service.d"; touch "$HG_GUARD"
hg_restore_after_boot cleanup; hg_restore_after_boot cleanup # boot 1: uninstall.sh's job, then the guard
n1="$(cat "$T/data/restore-attempts" 2>/dev/null)"; e1="$(grep -c '^pm enable' "$T/calls")"
boot 2; hg_restore_after_boot cleanup; hg_restore_after_boot cleanup
if [ "$n1" = 1 ] && [ "$e1" = 1 ] && [ "$(cat "$T/data/restore-attempts" 2>/dev/null)" = 2 ] && [ "$(grep -c '^pm enable' "$T/calls")" = 2 ] \
   && [ "$(locks)" = "boot-2 " ] && [ -e "$HG_GUARD" ]; then
  ok restore_failure_counts_once_per_boot; else bad "restore_failure_counts_once_per_boot (n1=$n1 e1=$e1 locks=$(locks))"; fi
setup; stub getprop "n=\$(( \$(cat \"$T/gp\" 2>/dev/null || echo 0) + 1 )); echo \$n > \"$T/gp\"; [ \$n -ge 3 ] && echo 1"
printf 'FSG=1\nNAVBAR=\n' > "$T/data/original-nav.txt"; hg_restore_after_boot mark
if [ "$(sleeps)" = "2 2 " ] && has "pm enable"; then ok restore_after_boot_waits_for_boot_completed; else bad restore_after_boot_waits_for_boot_completed; fi

# A restore that keeps failing is retried at 3 boots at most, then left to the owner.
setup; stub pm 'case "$*" in enable*) exit 1;; esac'; printf 'FSG=0\nNAVBAR=\n' > "$T/data/original-nav.txt"
mkdir -p "$T/mod"; touch "$T/mod/disable"
hg_restore_after_boot mark; boot 2; hg_restore_after_boot mark
n2="$(cat "$T/data/restore-attempts" 2>/dev/null)"; if has "^notify"; then notified2=1; else notified2=0; fi
boot 3; if hg_restore_after_boot mark; then r=0; else r=1; fi
if [ "$n2" = 2 ] && [ "$notified2" = 0 ] && [ "$r" = 1 ] && [ -f "$T/data/restore-gave-up" ] && has "^notify.*manual restore" \
   && grep -q "manual restore needed.*FSG=0" "$T/data/log.txt" && [ "$(hg_guard_decide "$T/mod" "$T/data")" = none ] && [ "$(locks)" = "boot-3 " ]; then
  ok restore_gives_up_after_3_failed_boots_and_guard_idles; else bad restore_gives_up_after_3_failed_boots_and_guard_idles; fi
setup; stub pm 'case "$*" in enable*) exit 1;; esac'; printf 'FSG=0\nNAVBAR=\n' > "$T/data/original-nav.txt"
mkdir -p "$T/service.d"; touch "$HG_GUARD"
hg_restore_after_boot cleanup; boot 2; hg_restore_after_boot cleanup
if [ -f "$T/data/original-nav.txt" ]; then kept2=1; else kept2=0; fi
boot 3; hg_restore_after_boot cleanup
if [ "$kept2" = 1 ] && [ ! -e "$T/data" ] && [ ! -e "$HG_GUARD" ] && has "^notify.*manual restore"; then
  ok restore_gives_up_after_3_failed_boots_and_still_cleans_up_when_module_gone; else bad restore_gives_up_after_3_failed_boots_and_still_cleans_up_when_module_gone; fi
setup; echo 2 > "$T/data/restore-attempts"; printf 'FSG=0\nNAVBAR=\n' > "$T/data/original-nav.txt"; hg_restore_after_boot mark
if [ ! -f "$T/data/restore-attempts" ] && [ -f "$T/data/guard-restored" ]; then
  ok restore_success_resets_retry_count; else bad restore_success_resets_retry_count; fi

# --- the scripts, end to end ----------------------------------------------------------------
setup; hg_install_guard "$TPL"; mkdir -p "$T/mod"; touch "$T/mod/disable"; printf 'FSG=1\nNAVBAR=\n' > "$T/data/original-nav.txt"
HG_SYNC=1 $SH "$HG_GUARD"
if has "pm enable com.miui.home" && has "overlay disable $GESTURAL" && [ -f "$T/data/guard-restored" ] && [ -e "$HG_GUARD" ]; then
  ok guard_script_restores_when_module_disabled; else bad guard_script_restores_when_module_disabled; fi
rm -f "$T/calls"; HG_SYNC=1 $SH "$HG_GUARD"
if [ ! -f "$T/calls" ]; then ok guard_script_does_not_repeat_while_disabled; else bad guard_script_does_not_repeat_while_disabled; fi
setup; hg_install_guard "$TPL"; mkdir -p "$T/mod"; HG_SYNC=1 $SH "$HG_GUARD"
if [ ! -f "$T/calls" ] && [ -e "$HG_GUARD" ]; then ok guard_script_idle_while_module_enabled; else bad guard_script_idle_while_module_enabled; fi
setup; hg_install_guard "$TPL"; printf 'FSG=1\nNAVBAR=\n' > "$T/data/original-nav.txt"; HG_SYNC=1 $SH "$HG_GUARD"
if has "pm enable com.miui.home" && [ ! -e "$T/data" ] && [ ! -e "$HG_GUARD" ]; then
  ok guard_script_cleans_up_when_module_gone; else bad guard_script_cleans_up_when_module_gone; fi

setup; hg_install_guard "$TPL"; printf 'FSG=0\nNAVBAR=\n' > "$T/data/original-nav.txt"; HG_SYNC=1 $SH "$TPL/uninstall.sh"
if has "pm enable com.miui.home" && has "overlay disable $GESTURAL" && has "settings put global force_fsg_nav_bar 0" \
   && [ ! -e "$T/data" ] && [ ! -e "$HG_GUARD" ]; then
  ok uninstall_restores_then_cleans_up; else bad uninstall_restores_then_cleans_up; fi
setup; hg_install_guard "$TPL"; printf 'FSG=0\nNAVBAR=\n' > "$T/data/original-nav.txt"
started=$(date +%s); $SH "$TPL/uninstall.sh"; returned=$(date +%s)
i=0; while [ -e "$T/data" ] && [ "$i" -lt 50 ]; do command sleep 0.1; i=$((i+1)); done
if [ $((returned - started)) -le 1 ] && [ ! -e "$T/data" ] && has "pm enable com.miui.home"; then
  ok uninstall_returns_at_once_and_restores_in_background; else bad uninstall_returns_at_once_and_restores_in_background; fi

setup; printf 'FSG=1\nNAVBAR=%s\n' "$THREEBUTTON" > "$T/data/original-nav.txt"
if $SH "$TPL/restore.sh" > "$T/out" 2>&1 && has "pm enable com.miui.home" && has "overlay enable-exclusive --category $THREEBUTTON" \
   && has "settings put global force_fsg_nav_bar 1" && [ -f "$T/data/original-nav.txt" ] \
   && has "role add-role-holder android.app.role.HOME com.miui.home"; then
  ok restore_script_restores_immediately; else bad restore_script_restores_immediately; fi
if [ -f "$T/data/restored" ]; then ok restore_script_marks_restored; else bad restore_script_marks_restored; fi
setup; stub pm 'case "$*" in enable*) exit 1;; esac'
if $SH "$TPL/restore.sh" > "$T/out" 2>&1; then bad restore_script_reports_failure; else ok restore_script_reports_failure; fi
if [ ! -e "$T/data/restored" ]; then ok restore_script_failure_leaves_no_marker; else bad restore_script_failure_leaves_no_marker; fi
setup; stub role 'case "$*" in add-role-holder*) exit 1;; esac'
if ! $SH "$TPL/restore.sh" > "$T/out" 2>&1 && has "pm enable com.miui.home" && [ ! -e "$T/data/restored" ]; then
  ok restore_script_reports_failed_home; else bad restore_script_reports_failed_home; fi

# --- setconf.sh: set one setting whether or not the config has a line for it -----------------
cfg() { sed -n "s/^$1=//p" "$T/data/config"; }
setup; mkmod; rm -f "$T/data/config"
if hg_setconf "$T/svc" NAV_MODE threebutton > "$T/out" 2>&1 && [ "$(cfg NAV_MODE)" = threebutton ] && [ "$(grep -c '^NAV_MODE=' "$T/data/config")" = 1 ] \
   && [ "$(cfg DISABLE_MIUIHOME)" = 1 ] && [ "$(cfg WATCHDOG)" = 1 ] && [ "$(cfg IGNORE_FINGERPRINT)" = 0 ] && grep -q '^# NAV_MODE:' "$T/data/config"; then
  ok setconf_creates_config_from_default; else bad setconf_creates_config_from_default; fi
setup; mkmod; printf 'IGNORE_FINGERPRINT=1\r\n' > "$T/data/config" # what the old runbook recipe left (CRLF from a Windows editor too)
if hg_setconf "$T/svc" NAV_MODE threebutton > "$T/out" 2>&1 && [ "$(cfg NAV_MODE)" = threebutton ] && [ "$(cfg IGNORE_FINGERPRINT)" = 1 ] \
   && ! grep -q "$(printf '\r')" "$T/data/config" && [ "$(. "$T/data/config"; echo "$NAV_MODE/$IGNORE_FINGERPRINT")" = threebutton/1 ]; then
  ok setconf_adds_a_missing_key; else bad setconf_adds_a_missing_key; fi
setup; mkmod; printf '# mine\nNAV_MODE=gestural\nWATCHDOG=0\n  NAV_MODE=fsg\n' > "$T/data/config"
if hg_setconf "$T/svc" NAV_MODE keep > "$T/out" 2>&1 \
   && [ "$(cat "$T/data/config")" = "$(printf '# mine\nNAV_MODE=keep\nWATCHDOG=0')" ]; then
  ok setconf_replaces_the_key_in_place_once; else bad "setconf_replaces_the_key_in_place_once ($(tr '\n' '|' < "$T/data/config"))"; fi
setup; mkmod; printf 'NAV_MODE=gestural\n' > "$T/data/config"
r=0
hg_setconf "$T/svc" NAV_MOD threebutton > "$T/out" 2>&1 && r=1
hg_setconf "$T/svc" NAV_MODE 3button >> "$T/out" 2>&1 && r=1
hg_setconf "$T/svc" WATCHDOG yes >> "$T/out" 2>&1 && r=1
hg_setconf "$T/svc" 'NAV_MODE' 'gestural;reboot' >> "$T/out" 2>&1 && r=1
hg_setconf "$T/svc" NAV_MODE >> "$T/out" 2>&1 && r=1
if [ "$r" = 0 ] && [ "$(cat "$T/data/config")" = NAV_MODE=gestural ] && grep -q "unknown setting" "$T/out"; then
  ok setconf_rejects_unknown_keys_and_bad_values; else bad setconf_rejects_unknown_keys_and_bad_values; fi
setup; mkmod; printf 'IGNORE_FINGERPRINT=1\n' > "$T/data/config"
if $SH "$T/svc/setconf.sh" NAV_MODE threebutton > "$T/out" 2>&1 && grep -q "NAV_MODE=threebutton" "$T/out"; then
  printf 'WATCHDOG=0\n' >> "$T/data/config"; hg_service_main "$T/svc" # the next boot applies it
  if has "overlay enable-exclusive --category $THREEBUTTON" && ! has "enable-exclusive --category $GESTURAL"; then
    ok setconf_script_sets_what_the_next_boot_applies; else bad setconf_script_sets_what_the_next_boot_applies; fi
else bad "setconf_script_sets_what_the_next_boot_applies (script failed: $(cat "$T/out"))"; fi

# MOUNT=metamodule|self, and KernelSU's skip_mount flag that keeps a metamodule off our system/ tree.
setup; mkmod; rm -f "$T/data/config"
if hg_setconf "$T/svc" MOUNT self > "$T/out" 2>&1 && [ "$(cfg MOUNT)" = self ] && [ -f "$T/svc/skip_mount" ] && [ "$(grep -c '^MOUNT=' "$T/data/config")" = 1 ]; then
  ok setconf_mount_self_creates_skip_mount; else bad "setconf_mount_self_creates_skip_mount ($(cat "$T/out"))"; fi
if hg_setconf "$T/svc" MOUNT metamodule > "$T/out" 2>&1 && [ "$(cfg MOUNT)" = metamodule ] && [ ! -e "$T/svc/skip_mount" ]; then
  ok setconf_mount_metamodule_removes_skip_mount; else bad "setconf_mount_metamodule_removes_skip_mount ($(cat "$T/out"))"; fi
setup; mkmod; printf 'NAV_MODE=gestural\n' > "$T/data/config"
if hg_setconf "$T/svc" MOUNT self > "$T/out" 2>&1 && [ "$(cfg MOUNT)" = self ] && [ "$(cfg NAV_MODE)" = gestural ]; then
  ok setconf_mount_adds_a_missing_line; else bad setconf_mount_adds_a_missing_line; fi
setup; mkmod; printf 'MOUNT=self\n' > "$T/data/config"; touch "$T/svc/skip_mount"
r=0
hg_setconf "$T/svc" MOUNT hybrid > "$T/out" 2>&1 && r=1
hg_setconf "$T/svc" MOUNT 1 >> "$T/out" 2>&1 && r=1
hg_setconf "$T/svc" MOUNT 'self;reboot' >> "$T/out" 2>&1 && r=1
hg_setconf "$T/svc" MOUNT '' >> "$T/out" 2>&1 && r=1
if [ "$r" = 0 ] && [ "$(cat "$T/data/config")" = MOUNT=self ] && [ -f "$T/svc/skip_mount" ] && grep -q "MOUNT must be auto, metamodule or self" "$T/out"; then
  ok setconf_rejects_a_bad_mount_value_and_keeps_skip_mount; else bad "setconf_rejects_a_bad_mount_value_and_keeps_skip_mount ($(cat "$T/out"))"; fi
setup; mkmod; rm -f "$T/data/config"
if $SH "$T/svc/setconf.sh" MOUNT self > "$T/out" 2>&1 && [ -f "$T/svc/skip_mount" ] && grep -q "reboot" "$T/out" \
   && $SH "$T/svc/setconf.sh" MOUNT metamodule > "$T/out" 2>&1 && [ ! -e "$T/svc/skip_mount" ]; then
  ok setconf_script_toggles_skip_mount; else bad "setconf_script_toggles_skip_mount ($(cat "$T/out"))"; fi
grep -q '^MOUNT=auto$' "$TPL/config.default" && ok config_default_mounts_auto || bad config_default_mounts_auto
setup; mkmod; rm -f "$T/data/config"; rm -rf "$T/modules/hybrid_mount"; echo "hybrid_mount 170" > "$T/data/metamodule-missed"
if hg_setconf "$T/svc" MOUNT auto > "$T/out" 2>&1 && [ "$(cfg MOUNT)" = auto ] && [ -f "$T/svc/skip_mount" ] && [ ! -e "$T/data/metamodule-missed" ] \
   && grep -qF "MOUNT=auto (in $T/data/config; now: auto → self (no active metamodule); skip_mount set; reboot to apply)" "$T/out"; then
  ok setconf_mount_auto_without_a_metamodule_sets_skip_mount; else bad "setconf_mount_auto_without_a_metamodule_sets_skip_mount ($(cat "$T/out"))"; fi
setup; mkmod; rm -f "$T/data/config"; touch "$T/svc/skip_mount"; echo "hybrid_mount 170" > "$T/data/metamodule-missed"
if hg_setconf "$T/svc" MOUNT auto > "$T/out" 2>&1 && [ ! -e "$T/svc/skip_mount" ] && [ ! -e "$T/data/metamodule-missed" ] \
   && grep -qF "now: auto → mixed (hybrid_mount); skip_mount removed" "$T/out"; then
  ok setconf_mount_auto_drops_the_missed_marker_and_follows_the_metamodule; else bad "setconf_mount_auto_drops_the_missed_marker_and_follows_the_metamodule ($(cat "$T/out"))"; fi
setup; mkmod; rm -f "$T/data/config"
if hg_setconf "$T/svc" WATCHDOG 0 > "$T/out" 2>&1 && [ -f "$T/data/watchdog-stop" ] && grep -q "a running watchdog stops at its next check" "$T/out" \
   && hg_setconf "$T/svc" WATCHDOG 1 > "$T/out" 2>&1 && [ ! -e "$T/data/watchdog-stop" ] && [ "$(cfg WATCHDOG)" = 1 ]; then
  ok setconf_watchdog_0_stops_the_running_watchdog; else bad "setconf_watchdog_0_stops_the_running_watchdog ($(cat "$T/out"))"; fi

# --- payload mount (post-fs-data) ----------------------------------------------------------
# The overlay APK ships outside system/ (overlay/HyperGesturesOverlay.apk), staged as
# $HG_STAGE/overlay and stacked on /product/overlay by post-fs-data in both MOUNT modes; each
# system/<dir> is staged as $HG_STAGE/system/<dir> (MOUNT=self only).
OVL=HyperGesturesOverlay.apk
ovlmounts() { grep -c "^mount -t overlay hg_payload -o ro,lowerdir=$T/stage/overlay:$T/root/product/overlay $T/root/product/overlay\$" "$T/calls" 2>/dev/null; }
sysmounts() { grep -c "^mount -t overlay .*lowerdir=$T/stage/system/" "$T/calls" 2>/dev/null; }
# MOUNT=self: all four, allowlist and hidden-API config before the APK, the overlay last.
R=""; setup; mountfix; hg_self_mount "$T/mod" self && R=0
if [ "$R" = 0 ] && [ "$(mstatus)" = ok ] && before "mount -t tmpfs" "mount -t overlay" \
   && before "lowerdir=$T/stage/system/etc/permissions:$T/root/system/etc/permissions " "lowerdir=$T/stage/system/priv-app:" \
   && before "lowerdir=$T/stage/system/etc/sysconfig:$T/root/system/etc/sysconfig " "lowerdir=$T/stage/system/priv-app:" \
   && before "lowerdir=$T/stage/system/priv-app:$T/root/system/priv-app " "lowerdir=$T/stage/overlay:" \
   && has "chcon -R u:object_r:system_file:s0 $T/stage" && [ -f "$T/stage/system/priv-app/HyperGestures/HyperGestures.apk" ] \
   && cmp -s "$T/mod/system/priv-app/HyperGesturesNotif/HyperGesturesNotif.apk" \
             "$T/stage/system/priv-app/HyperGesturesNotif/HyperGesturesNotif.apk" \
   && cmp -s "$T/mod/overlay/$OVL" "$T/stage/overlay/$OVL" && [ "$(ovlmounts)" = 1 ] && [ "$(sysmounts)" = 3 ] \
   && [ "$(grep -c '^mount -t overlay' "$T/calls")" = 4 ] && ! has "^umount"; then
  ok mount_self_mounts_all_four_allowlist_before_apk_overlay_last; else bad "mount_self_mounts_all_four_allowlist_before_apk_overlay_last ($(mstatus))"; fi
# MOUNT=metamodule: the metamodule mounts system/; we mount only the overlay.
R=""; setup; mountfix; hg_self_mount "$T/mod" metamodule && R=0
if [ "$R" = 0 ] && [ "$(mstatus)" = "metamodule + overlay ok" ] && before "mount -t tmpfs" "mount -t overlay" \
   && [ "$(ovlmounts)" = 1 ] && [ "$(grep -c '^mount -t overlay' "$T/calls")" = 1 ] && [ "$(sysmounts)" = 0 ] \
   && has "chcon -R u:object_r:system_file:s0 $T/stage" && cmp -s "$T/mod/overlay/$OVL" "$T/stage/overlay/$OVL" \
   && [ ! -e "$T/stage/system" ] && ! has "^umount"; then
  ok mount_metamodule_mounts_only_the_overlay; else bad "mount_metamodule_mounts_only_the_overlay ($(mstatus))"; fi
# A failed overlay mount leaves nothing behind: the phone keeps MiuiHome's recents.
setup; mountfix "case \"\$*\" in *\" $T/root/product/overlay\") exit 1;; esac"
if ! hg_self_mount "$T/mod" metamodule && has "^umount $T/stage\$" && [ "$(grep -c '^umount' "$T/calls")" = 1 ] \
   && [ "$(mstatus)" = "metamodule + overlay FAILED: overlay on /product/overlay; rolled back" ]; then
  ok mount_metamodule_overlay_failure_rolls_back; else bad "mount_metamodule_overlay_failure_rolls_back ($(mstatus))"; fi
setup; mountfix "case \"\$*\" in *\" $T/root/product/overlay\") exit 1;; esac"
if ! hg_self_mount "$T/mod" self && before "umount $T/root/system/priv-app" "umount $T/root/system/etc/sysconfig" \
   && before "umount $T/root/system/etc/sysconfig" "umount $T/root/system/etc/permissions" \
   && before "umount $T/root/system/etc/permissions" "umount $T/stage" && mstatus | grep -q "^FAILED: overlay on /product/overlay; rolled back$"; then
  ok mount_failure_rolls_back_newest_first; else bad "mount_failure_rolls_back_newest_first ($(mstatus))"; fi
setup; mountfix "case \"\$*\" in *\" $T/root/product/overlay\") exit 1;; esac" "case \"\$*\" in *priv-app) exit 1;; esac"
if ! hg_self_mount "$T/mod" self && ! has "umount $T/root/system/etc/permissions" && ! has "umount $T/stage" \
   && mstatus | grep -q "still mounted: $T/root/system/priv-app $T/root/system/etc/sysconfig $T/root/system/etc/permissions"; then
  ok mount_rollback_never_leaves_apk_without_allowlist; else bad "mount_rollback_never_leaves_apk_without_allowlist ($(mstatus))"; fi
setup; mountfix 'case "$*" in "-t tmpfs"*) exit 1;; esac'
if ! hg_self_mount "$T/mod" metamodule && ! has "mount -t overlay" && mstatus | grep -q '^metamodule + overlay FAILED: tmpfs'; then
  ok mount_tmpfs_failure_mounts_nothing; else bad mount_tmpfs_failure_mounts_nothing; fi
setup; mountfix; stub chcon 'exit 1'
if ! hg_self_mount "$T/mod" self && ! has "mount -t overlay" && has "umount $T/stage" && mstatus | grep -q '^FAILED: staging'; then
  ok mount_staging_failure_mounts_nothing; else bad mount_staging_failure_mounts_nothing; fi
setup; mountfix; echo "hg_stage $T/stage tmpfs rw 0 0" > "$T/mounts"
if hg_self_mount "$T/mod" self && nomount && [ "$(mstatus)" = "ok (already mounted this boot)" ] \
   && hg_self_mount "$T/mod" metamodule && nomount && [ "$(mstatus)" = "metamodule + overlay ok (already mounted this boot)" ]; then
  ok mount_twice_in_a_boot_is_a_noop; else bad "mount_twice_in_a_boot_is_a_noop ($(mstatus))"; fi
# MOUNT=self, but system/ is already there (a metamodule mounted it despite skip_mount): only the
# overlay, which no metamodule mounts, is ours to add.
setup; mountfix; mkdir -p "$T/root/system/priv-app/HyperGestures"
if hg_self_mount "$T/mod" self && [ "$(ovlmounts)" = 1 ] && [ "$(sysmounts)" = 0 ] \
   && [ "$(mstatus)" = "ok (already there, mounted by something else, left alone: /system/priv-app/HyperGestures)" ]; then
  ok mount_self_adds_only_the_overlay_when_the_apk_is_already_there; else bad "mount_self_adds_only_the_overlay_when_the_apk_is_already_there ($(mstatus))"; fi
setup; mountfix; mkdir -p "$T/root/system/priv-app/HyperGestures"; : > "$T/root/product/overlay/$OVL"
if hg_self_mount "$T/mod" self && nomount \
   && [ "$(mstatus)" = "skipped: already there (mounted by something else): /system/priv-app/HyperGestures /product/overlay/$OVL" ]; then
  ok mount_skips_when_apk_and_overlay_already_present; else bad "mount_skips_when_apk_and_overlay_already_present ($(mstatus))"; fi
setup; mountfix; : > "$T/root/product/overlay/$OVL"
if hg_self_mount "$T/mod" metamodule && nomount && mstatus | grep -q "^metamodule + overlay skipped: already there .*/product/overlay/$OVL\$"; then
  ok mount_metamodule_skips_an_overlay_already_present; else bad "mount_metamodule_skips_an_overlay_already_present ($(mstatus))"; fi
setup; mountfix; rm -rf "$T/mod/system/etc/permissions"
if ! hg_self_mount "$T/mod" self && nomount && mstatus | grep -q '^FAILED: missing'; then ok mount_missing_payload_mounts_nothing; else bad mount_missing_payload_mounts_nothing; fi
setup; mountfix; rm -rf "$T/mod/overlay"
if ! hg_self_mount "$T/mod" metamodule && nomount && mstatus | grep -q "^metamodule + overlay FAILED: missing $T/mod/overlay"; then
  ok mount_missing_overlay_mounts_nothing; else bad "mount_missing_overlay_mounts_nothing ($(mstatus))"; fi
setup; mountfix; rm -rf "$T/mod/overlay"
if ! hg_self_mount "$T/mod" self && nomount && mstatus | grep -q "^FAILED: missing $T/mod/overlay"; then
  ok mount_self_without_the_overlay_mounts_nothing; else bad "mount_self_without_the_overlay_mounts_nothing ($(mstatus))"; fi
# app (a dormant boot in self mode): system/ alone, allowlist and hidden-API config before the APK,
# never the recents overlay (SystemUI keeps MiuiHome's recents).
R=""; setup; mountfix; hg_self_mount "$T/mod" app && R=0
if [ "$R" = 0 ] && [ "$(mstatus)" = "dormant (HOME is com.miui.home), app only: ok" ] \
   && before "lowerdir=$T/stage/system/etc/permissions:" "lowerdir=$T/stage/system/priv-app:" \
   && before "lowerdir=$T/stage/system/etc/sysconfig:" "lowerdir=$T/stage/system/priv-app:" \
   && [ "$(sysmounts)" = 3 ] && [ "$(ovlmounts)" = 0 ] && [ "$(grep -c '^mount -t overlay' "$T/calls")" = 3 ] \
   && [ -f "$T/stage/system/priv-app/HyperGestures/HyperGestures.apk" ] && [ ! -e "$T/stage/overlay" ] && ! has "^umount" \
   && [ -f "$T/stage/system/priv-app/HyperGesturesNotif/HyperGesturesNotif.apk" ]; then # any HOME
  ok mount_app_mounts_the_system_tree_without_the_overlay; else bad "mount_app_mounts_the_system_tree_without_the_overlay ($(mstatus))"; fi
setup; mountfix; rm -rf "$T/mod/overlay" # the overlay is not needed, so not missed either
R=""; hg_self_mount "$T/mod" app && R=0
if [ "$R" = 0 ] && [ "$(sysmounts)" = 3 ] && [ "$(ovlmounts)" = 0 ]; then
  ok mount_app_does_not_need_the_overlay; else bad "mount_app_does_not_need_the_overlay ($(mstatus))"; fi
setup; mountfix; mkdir -p "$T/root/system/priv-app/HyperGestures"
if hg_self_mount "$T/mod" app && nomount \
   && [ "$(mstatus)" = "dormant (HOME is com.miui.home), app only: skipped: already there (mounted by something else): /system/priv-app/HyperGestures" ]; then
  ok mount_app_skips_an_apk_already_there; else bad "mount_app_skips_an_apk_already_there ($(mstatus))"; fi
setup; mountfix "case \"\$*\" in *priv-app) exit 1;; esac"
if ! hg_self_mount "$T/mod" app && before "umount $T/root/system/etc/sysconfig" "umount $T/root/system/etc/permissions" \
   && before "umount $T/root/system/etc/permissions" "umount $T/stage" && [ "$(ovlmounts)" = 0 ] \
   && [ "$(mstatus)" = "dormant (HOME is com.miui.home), app only: FAILED: overlay on /system/priv-app; rolled back" ]; then
  ok mount_app_failure_rolls_back; else bad "mount_app_failure_rolls_back ($(mstatus))"; fi
selfcfg() { printf 'MOUNT=self\n%b' "${1:-}" > "$T/data/config"; } # config that opts in to self-mount
setup; mountfix; selfcfg 'STRICT_FIRMWARE=1\n'; echo "$OTA" > "$T/mod/expected-fingerprint"; hg_post_fs_data_main "$T/mod"
if nomount && mstatus | grep -q '^skipped: firmware mismatch'; then
  ok post_fs_data_strict_skips_mount_on_firmware_mismatch; else bad post_fs_data_strict_skips_mount_on_firmware_mismatch; fi
setup; mountfix; printf 'STRICT_FIRMWARE=1\n' > "$T/data/config"; echo "$OTA" > "$T/mod/expected-fingerprint"; hg_post_fs_data_main "$T/mod"
if nomount && mstatus | grep -q '^metamodule + overlay skipped: firmware mismatch.*(STRICT_FIRMWARE=1)$'; then
  ok post_fs_data_strict_leaves_the_overlay_out_on_firmware_mismatch; else bad "post_fs_data_strict_leaves_the_overlay_out_on_firmware_mismatch ($(mstatus))"; fi
setup; mountfix; selfcfg; echo "$OTA" > "$T/mod/expected-fingerprint"; hg_post_fs_data_main "$T/mod"
if [ "$(mstatus)" = ok ] && [ "$(grep -c '^mount -t overlay' "$T/calls")" = 4 ]; then
  ok post_fs_data_default_policy_self_mounts_on_firmware_mismatch; else bad "post_fs_data_default_policy_self_mounts_on_firmware_mismatch ($(mstatus))"; fi
setup; mountfix; selfcfg 'STRICT_FIRMWARE=1\nIGNORE_FINGERPRINT=1\n'; echo "$OTA" > "$T/mod/expected-fingerprint"
hg_post_fs_data_main "$T/mod"
if [ "$(mstatus)" = ok ]; then ok post_fs_data_mounts_on_ignored_mismatch; else bad post_fs_data_mounts_on_ignored_mismatch; fi
setup; mountfix; selfcfg; mkmod; cp -R "$T/mod/system/." "$T/svc/system/"; cp -R "$T/mod/overlay" "$T/svc/"
if $SH "$T/svc/post-fs-data.sh" > "$T/out" 2>&1 && [ "$(mstatus)" = ok ] && [ "$(grep -c '^mount -t overlay' "$T/calls")" = 4 ]; then
  ok post_fs_data_script_mounts_system_tree_and_overlay_when_self; else bad "post_fs_data_script_mounts_system_tree_and_overlay_when_self ($(cat "$T/out"; mstatus))"; fi
setup; mountfix; mkmod; cp -R "$T/mod/overlay" "$T/svc/"
if $SH "$T/svc/post-fs-data.sh" > "$T/out" 2>&1 && [ "$(mstatus)" = "metamodule + overlay ok" ] && [ "$(ovlmounts)" = 1 ] \
   && [ "$(grep -c '^mount -t overlay' "$T/calls")" = 1 ]; then
  ok post_fs_data_script_mounts_only_the_overlay_by_default; else bad "post_fs_data_script_mounts_only_the_overlay_by_default ($(cat "$T/out"; mstatus))"; fi
# No MOUNT line: auto, and the fixture's metamodule is active, so mixed: the metamodule mounts
# system/, post-fs-data only the overlay.
setup; mountfix; hg_post_fs_data_main "$T/mod"
if [ "$(ovlmounts)" = 1 ] && [ "$(sysmounts)" = 0 ] && ! has "^umount" && [ "$(mstatus)" = "metamodule + overlay ok" ]; then
  ok post_fs_data_default_mounts_only_the_overlay; else bad "post_fs_data_default_mounts_only_the_overlay ($(mstatus))"; fi
setup; mountfix; printf 'MOUNT=metamodule\n' > "$T/data/config"; echo "$OTA" > "$T/mod/expected-fingerprint"; hg_post_fs_data_main "$T/mod"
if [ "$(ovlmounts)" = 1 ] && [ "$(mstatus)" = "metamodule + overlay ok" ]; then
  ok post_fs_data_metamodule_mounts_the_overlay_on_firmware_mismatch; else bad "post_fs_data_metamodule_mounts_the_overlay_on_firmware_mismatch ($(mstatus))"; fi
setup; mountfix; selfcfg; echo "$FP" > "$T/mod/expected-fingerprint"; hg_post_fs_data_main "$T/mod"
if [ "$(mstatus)" = ok ] && [ "$(grep -c '^mount -t overlay' "$T/calls")" = 4 ] && has "lowerdir=$T/stage/system/priv-app:$T/root/system/priv-app" \
   && [ "$(ovlmounts)" = 1 ]; then
  ok post_fs_data_self_mounts_the_system_tree_and_the_overlay; else bad "post_fs_data_self_mounts_the_system_tree_and_the_overlay ($(mstatus))"; fi
setup; mountfix; printf 'MOUNT=bogus\n' > "$T/data/config"; hg_post_fs_data_main "$T/mod"
if [ "$(sysmounts)" = 0 ] && [ "$(ovlmounts)" = 1 ] && [ "$(mstatus)" = "metamodule + overlay ok" ] && [ ! -e "$T/mod/skip_mount" ]; then
  ok post_fs_data_unknown_mount_value_means_auto; else bad "post_fs_data_unknown_mount_value_means_auto ($(mstatus))"; fi
# A failed overlay mount (MOUNT=metamodule): recorded, and the boot goes on as "not set up" (SystemUI
# keeps MiuiHome's recents, the provider never binds, nothing is changed).
setup; mountfix "case \"\$*\" in *\" $T/root/product/overlay\") exit 1;; esac"; mkmod; cp -R "$T/mod/overlay" "$T/svc/"
printf 'MOUNT=metamodule\nNAV_MODE=gestural\nDISABLE_MIUIHOME=1\nWATCHDOG=1\n' > "$T/data/config"; echo 0 > "$T/fsg"
hg_post_fs_data_main "$T/svc"; st="$(mstatus)"; rm -f "$T/calls"
stub dumpsys "cat \"$FIX/sysui_miuihome.txt\""; hg_service_main "$T/svc"
if [ "$st" = "metamodule + overlay FAILED: overlay on /product/overlay; rolled back" ] && nochange && [ ! -f "$T/svc/disable" ] \
   && [ ! -f "$T/data/original-nav.txt" ] && grep -q '^not run: provider not bound' "$T/data/health-status"; then
  ok overlay_mount_failure_leaves_the_phone_stock; else bad "overlay_mount_failure_leaves_the_phone_stock ($st)"; fi
# skip_mount (KernelSU's flag, honoured by metamodules) follows the config on every boot.
setup; mountfix; selfcfg; echo "$FP" > "$T/mod/expected-fingerprint"; hg_post_fs_data_main "$T/mod"
if [ -f "$T/mod/skip_mount" ]; then ok post_fs_data_creates_skip_mount_for_self; else bad post_fs_data_creates_skip_mount_for_self; fi
setup; mountfix; selfcfg 'STRICT_FIRMWARE=1\n'; echo "$OTA" > "$T/mod/expected-fingerprint"; hg_post_fs_data_main "$T/mod"
if [ -f "$T/mod/skip_mount" ]; then ok post_fs_data_keeps_skip_mount_even_when_self_mount_skipped; else bad post_fs_data_keeps_skip_mount_even_when_self_mount_skipped; fi
setup; mountfix; touch "$T/mod/skip_mount"; hg_post_fs_data_main "$T/mod"
if [ ! -e "$T/mod/skip_mount" ]; then ok post_fs_data_removes_stale_skip_mount_by_default; else bad post_fs_data_removes_stale_skip_mount_by_default; fi
setup; mountfix; touch "$T/mod/skip_mount"; printf 'MOUNT=metamodule\n' > "$T/data/config"; hg_post_fs_data_main "$T/mod"
if [ ! -e "$T/mod/skip_mount" ]; then ok post_fs_data_removes_skip_mount_for_metamodule; else bad post_fs_data_removes_skip_mount_for_metamodule; fi

# --- mount mode: MOUNT=auto --------------------------------------------------------------------
# setup's fixture: $T/modules/hybrid_mount, an enabled metamodule (metamodule=1, metamount.sh),
# found by scanning; mklink adds KernelSU's /data/adb/metamodule link.
mkmeta() { # mkmeta ID [true|crlf|noflag|nometamount|disable|remove|vc=N]...: a module in $T/modules
  _d="$T/modules/$1"; shift; rm -rf "$_d"; mkdir -p "$_d"; _flag=1; _nl='\n'; _mm=1; _vc=170
  for _o in "$@"; do case "$_o" in
    true) _flag=true ;; crlf) _nl='\r\n' ;; noflag) _flag="" ;; nometamount) _mm="" ;;
    disable|remove) : > "$_d/$_o" ;; vc=*) _vc="${_o#vc=}" ;;
  esac; done
  printf "id=%s${_nl}name=A metamodule${_nl}versionCode=%s${_nl}" "${_d##*/}" "$_vc" > "$_d/module.prop"
  [ -z "$_flag" ] || printf "metamodule=%s${_nl}" "$_flag" >> "$_d/module.prop"
  [ -z "$_mm" ] || : > "$_d/metamount.sh"
}
mklink() { rm -rf "$T/metamodule"; MSYS=winsymlinks:nativestrict ln -s "$1" "$T/metamodule" 2>/dev/null || ln -s "$1" "$T/metamodule"; }
mmactive() { if hg_metamodule_active; then echo "yes:$HG_MM_ID:$HG_MM_VC"; else echo no; fi; }
setup; a=$(mmactive); mklink "$T/modules/hybrid_mount"; b=$(mmactive)
if [ "$a|$b" = "yes:hybrid_mount:170|yes:hybrid_mount:170" ] && [ -L "$T/metamodule" ]; then ok metamodule_found_by_the_link_or_by_scanning
else bad "metamodule_found_by_the_link_or_by_scanning ($a|$b)"; fi
setup; mkmeta gone; mklink "$T/modules/gone"; rm -rf "$T/modules/gone"; a=$(mmactive) # dangling: gone, and no scan
setup; mkdir -p "$T/modules_update"; mkmeta x; mv "$T/modules/x" "$T/modules_update/x"; mklink "$T/modules_update/x"; b=$(mmactive)
if [ "$a|$b" = "no|no" ]; then ok metamodule_dangling_link_or_one_outside_the_modules_dir_is_not_active
else bad "metamodule_dangling_link_or_one_outside_the_modules_dir_is_not_active ($a|$b)"; fi
r=""; for o in disable remove nometamount noflag; do
  setup; mkmeta hybrid_mount "$o"; r="$r$(mmactive)|"; mklink "$T/modules/hybrid_mount"; r="$r$(mmactive)|"; done
if [ "$r" = "no|no|no|no|no|no|no|no|" ]; then ok metamodule_disabled_removed_without_metamount_or_flag_is_not_active
else bad "metamodule_disabled_removed_without_metamount_or_flag_is_not_active ($r)"; fi
setup; mkmeta hybrid_mount true crlf vc=171; a=$(mmactive)
if [ "$a" = "yes:hybrid_mount:171" ]; then ok metamodule_true_and_a_crlf_module_prop_are_accepted; else bad "metamodule_true_and_a_crlf_module_prop_are_accepted ($a)"; fi
setup; mkmeta other_meta; a=$(mmactive); mklink "$T/modules/other_meta"; b=$(mmactive) # two, no link: ambiguous; the link decides
if [ "$a|$b" = "no|yes:other_meta:170" ]; then ok metamodule_scan_needs_exactly_one_and_the_link_decides
else bad "metamodule_scan_needs_exactly_one_and_the_link_decides ($a|$b)"; fi
setup; rm -rf "$T/modules/hybrid_mount"; mkmeta hypergestures; a=$(mmactive)
if [ "$a" = no ]; then ok metamodule_is_never_this_module; else bad "metamodule_is_never_this_module ($a)"; fi
res() { hg_mount_resolve "$T/mod" "$1"; echo "$HG_MOUNT_MODE|$HG_MOUNT_DESC"; }
setup; a=$(res auto); b=$(res ""); c=$(res bogus); d=$(res metamodule); e=$(res self)
rm -rf "$T/modules/hybrid_mount"; f=$(res auto); g=$(res metamodule)
if [ "$a" = "metamodule|auto → mixed (hybrid_mount)" ] && [ "$b" = "$a" ] && [ "$c" = "$a" ] \
   && [ "$d" = "metamodule|metamodule → mixed (hybrid_mount)" ] && [ "$e" = "self|self (MOUNT=self)" ] \
   && [ "$f" = "self|auto → self (no active metamodule)" ] \
   && [ "$g" = "metamodule|metamodule → mixed (MOUNT=metamodule, but no active metamodule was found)" ]; then
  ok mount_resolve_auto_follows_the_metamodule_and_explicit_values_override; else bad "mount_resolve_auto_follows_the_metamodule_and_explicit_values_override ($a/$d/$e/$f/$g)"; fi
setup; echo "hybrid_mount 170" > "$T/data/metamodule-missed"; a=$(res auto); d=$(res metamodule); mkmeta hybrid_mount vc=171; b=$(res auto)
if [ "$a" = "self|auto → self (hybrid_mount did not mount the app at an earlier boot; setconf.sh MOUNT auto tries it again)" ] \
   && [ "$b" = "metamodule|auto → mixed (hybrid_mount)" ] && [ "${d%%|*}" = metamodule ]; then
  ok mount_resolve_auto_skips_a_metamodule_that_missed_until_it_changes; else bad "mount_resolve_auto_skips_a_metamodule_that_missed_until_it_changes ($a/$b)"; fi
setup; mountfix; rm -rf "$T/modules/hybrid_mount"; hg_post_fs_data_main "$T/mod"
if [ "$(mstatus)" = ok ] && [ "$(grep -c '^mount -t overlay' "$T/calls")" = 4 ] && [ -f "$T/mod/skip_mount" ] \
   && [ "$(head -n 1 "$T/data/mount-mode")" = "auto self - -" ] && grep -q "mount mode: auto → self (no active metamodule)" "$T/data/log.txt"; then
  ok post_fs_data_auto_without_a_metamodule_mounts_everything_itself; else bad "post_fs_data_auto_without_a_metamodule_mounts_everything_itself ($(mstatus))"; fi
setup; mountfix; touch "$T/mod/skip_mount"; mklink "$T/modules/hybrid_mount"; hg_post_fs_data_main "$T/mod"
if [ "$(mstatus)" = "metamodule + overlay ok" ] && [ "$(ovlmounts)" = 1 ] && [ "$(sysmounts)" = 0 ] && [ ! -e "$T/mod/skip_mount" ] \
   && [ "$(head -n 1 "$T/data/mount-mode")" = "auto metamodule hybrid_mount 170" ] && [ "$(sed -n 2p "$T/data/mount-mode")" = "auto → mixed (hybrid_mount)" ]; then
  ok post_fs_data_auto_with_a_metamodule_mounts_only_the_overlay; else bad "post_fs_data_auto_with_a_metamodule_mounts_only_the_overlay ($(mstatus))"; fi
setup; mountfix "case \"\$*\" in \"-t tmpfs\"*) [ -f \"$T/mod/skip_mount\" ] && echo skip_mount-present >> \"$T/calls\";; esac"
rm -rf "$T/modules/hybrid_mount"; hg_post_fs_data_main "$T/mod"
if has "^skip_mount-present"; then ok post_fs_data_writes_skip_mount_before_it_mounts; else bad post_fs_data_writes_skip_mount_before_it_mounts; fi
# MOUNT=auto chose mixed, but the metamodule did not mount the APK (hybrid_mount can have rules of
# its own): noted once, and the next boot mounts everything itself.
automiss() { setup; mountfix; mkmod; cp -R "$T/mod/overlay" "$T/svc/"; cp -R "$T/mod/system/." "$T/svc/system/"
  printf 'NAV_MODE=gestural\nWATCHDOG=0\n%b' "${1:-}" > "$T/data/config"; stub dumpsys "cat \"$FIX/sysui_miuihome.txt\""; }
automiss; hg_post_fs_data_main "$T/svc"; rm -f "$T/calls"; hg_service_main "$T/svc"
if [ "$(cat "$T/data/metamodule-missed" 2>/dev/null)" = "hybrid_mount 170" ] && [ -f "$T/svc/skip_mount" ] && [ "$(notified | grep -c .)" = 1 ] \
   && notified | grep -q "the metamodule hybrid_mount did not mount the app; from the next boot the module mounts it itself" \
   && grep -q "^not run: the metamodule hybrid_mount did not mount the app" "$T/data/health-status" && [ ! -f "$T/sleeps" ] && nochange \
   && grep -q "MOUNT=auto chose mixed (hybrid_mount), but .*/system/priv-app/HyperGestures is not mounted" "$T/data/log.txt"; then first=1; else first=0; fi
rm -f "$T/calls"; hg_post_fs_data_main "$T/svc" # the next boot
if [ "$first" = 1 ] && [ "$(mstatus)" = ok ] && [ "$(grep -c '^mount -t overlay' "$T/calls")" = 4 ] && [ -f "$T/svc/skip_mount" ] \
   && sed -n 2p "$T/data/mount-mode" | grep -q "hybrid_mount did not mount the app at an earlier boot"; then
  ok mount_auto_falls_back_to_self_once_the_metamodule_missed_the_apk; else bad "mount_auto_falls_back_to_self_once_the_metamodule_missed_the_apk (first=$first $(mstatus))"; fi
mkmeta hybrid_mount vc=171; rm -f "$T/calls"; hg_post_fs_data_main "$T/svc" # the metamodule was updated
if [ "$(head -n 1 "$T/data/mount-mode")" = "auto metamodule hybrid_mount 171" ] && [ ! -e "$T/svc/skip_mount" ] && [ "$(ovlmounts)" = 1 ]; then
  ok mount_auto_tries_an_updated_metamodule_again; else bad "mount_auto_tries_an_updated_metamodule_again ($(head -n 1 "$T/data/mount-mode"))"; fi
automiss; echo "hybrid_mount 169" > "$T/data/metamodule-missed"; hg_post_fs_data_main "$T/svc"
mkdir -p "$T/root/system/priv-app/HyperGestures"; stub dumpsys "cat \"$FIX/sysui_connected.txt\""; hg_service_main "$T/svc"
if [ ! -e "$T/data/metamodule-missed" ] && has "am force-stop com.miui.home" && ! notified | grep -q "did not mount"; then
  ok service_auto_mixed_with_the_apk_mounted_sets_up_and_drops_a_stale_marker; else bad service_auto_mixed_with_the_apk_mounted_sets_up_and_drops_a_stale_marker; fi
automiss 'MOUNT=metamodule\n'; hg_post_fs_data_main "$T/svc"; hg_service_main "$T/svc"
if [ ! -e "$T/data/metamodule-missed" ] && [ ! -e "$T/svc/skip_mount" ] && grep -q '^not run: provider not bound' "$T/data/health-status"; then
  ok service_explicit_metamodule_never_switches_to_self; else bad "service_explicit_metamodule_never_switches_to_self ($(cat "$T/data/health-status"))"; fi

# The package cache and the overlay idmap are dropped only when the APKs (or the mount mode)
# changed: a normal boot keeps PackageManager's parse of our APKs.
recache() { mkdir -p "$T/pkgcache/x" "$T/rescache"; : > "$T/pkgcache/x/HyperGestures-1-2"; : > "$T/rescache/product@overlay@HyperGesturesOverlay.apk@idmap"; }
cached() { [ -e "$T/pkgcache/x/HyperGestures-1-2" ] && [ -e "$T/rescache/product@overlay@HyperGesturesOverlay.apk@idmap" ]; }
stampfix() { setup; mountfix; printf 'id=hypergestures\nversionCode=12\n' > "$T/mod/module.prop"; recache; }
stampfix; hg_post_fs_data_main "$T/mod"; c1=0; cached && c1=1 # the first boot: no stamp yet
recache; hg_post_fs_data_main "$T/mod"; c2=0; cached && c2=1 # unchanged
echo "apk, version 2" > "$T/mod/system/priv-app/HyperGestures/HyperGestures.apk"; hg_post_fs_data_main "$T/mod"; c3=0; cached && c3=1
if [ "$c1$c2$c3" = 010 ] && grep -q '^versionCode=12 mode=metamodule$' "$T/data/apk-stamp"; then
  ok post_fs_data_drops_the_package_cache_only_when_the_apks_change; else bad "post_fs_data_drops_the_package_cache_only_when_the_apks_change ($c1$c2$c3)"; fi
# The notification hooks' APK (the LSPosed module) counts as one of ours: a change to it alone
# drops the cache, its own entry included.
NAPK="$T/mod/system/priv-app/HyperGesturesNotif/HyperGesturesNotif.apk"
NCACHE="$T/pkgcache/x/HyperGesturesNotif-1-2"
stampfix; hg_post_fs_data_main "$T/mod"; recache; : > "$NCACHE"
hg_post_fs_data_main "$T/mod"; c1=0; cached && [ -e "$NCACHE" ] && c1=1 # unchanged
echo "hooks, version 2" > "$NAPK"; hg_post_fs_data_main "$T/mod"
c2=0; [ -e "$T/pkgcache/x/HyperGestures-1-2" ] && c2=1; c3=0; [ -e "$NCACHE" ] && c3=1
if [ "$c1$c2$c3" = 100 ] && grep -q "^$NAPK 17 " "$T/data/apk-stamp"; then
  ok post_fs_data_drops_the_package_cache_when_the_notif_apk_changes
else bad "post_fs_data_drops_the_package_cache_when_the_notif_apk_changes ($c1$c2$c3)"; fi
stampfix; hg_post_fs_data_main "$T/mod"; recache; rm -rf "$T/modules/hybrid_mount"; hg_post_fs_data_main "$T/mod"; c1=0; cached && c1=1
if [ "$c1" = 0 ] && grep -q '^versionCode=12 mode=self$' "$T/data/apk-stamp"; then
  ok post_fs_data_drops_the_package_cache_when_the_mount_mode_changes; else bad post_fs_data_drops_the_package_cache_when_the_mount_mode_changes; fi

# --- HOME follows: dormant boots while com.miui.home is HOME ----------------------------------
NOVA=com.teslacoilsw.launcher; MIUI=com.miui.home
hstate() { hg_home_state; echo "$HG_HOME_STATE:$HG_HOME"; }
setup; a=$(hstate); roles "$MIUI"; b=$(hstate); roles "$MIUI" crlf; c=$(hstate); roles "$MIUI" oneline; d=$(hstate)
roles "$NOVA" oneline; e=$(hstate); roles sms.app emptyhome; f=$(hstate); roles sms.app nohome; g=$(hstate)
printf 'ABX\000\001binary xml' > "$T/roles.xml"; h=$(hstate); roles ""; i=$(hstate)
if [ "$a|$b|$c|$d|$e" = "active:$NOVA|dormant:$MIUI|dormant:$MIUI|dormant:$MIUI|active:$NOVA" ] \
   && [ "$f|$g|$h|$i" = "unknown:|unknown:|unknown:|unknown:" ]; then
  ok home_holder_is_read_from_roles_xml_and_anything_unreadable_is_unknown; else bad "home_holder_is_read_from_roles_xml_and_anything_unreadable_is_unknown ($a|$b|$c|$d|$e|$f|$g|$h|$i)"; fi
setup; roles "$MIUI"; mv "$T/roles.xml" "$T/roles-old.xml"; HG_ROLES_XML=""; HG_ROLES_PATHS="$T/roles-new.xml $T/roles-old.xml"
a=$(hstate); roles "$NOVA"; mv "$T/roles.xml" "$T/roles-new.xml"; b=$(hstate); HG_ROLES_PATHS=""; export HG_ROLES_XML="$T/roles.xml"
if [ "$a|$b" = "dormant:$MIUI|active:$NOVA" ]; then ok home_roles_xml_is_the_first_known_path_that_exists
else bad "home_roles_xml_is_the_first_known_path_that_exists ($a|$b)"; fi
unset HG_ROLES_PATHS; . "$TPL/common/functions.sh"
# post-fs-data on a dormant boot mounts no recents overlay; the metamodule still mounts system/.
setup; mountfix; roles "$MIUI"; hg_post_fs_data_main "$T/mod"
if nomount && [ "$(cat "$T/data/dormant")" = "$MIUI" ] && [ ! -e "$T/mod/skip_mount" ] \
   && [ "$(mstatus)" = "metamodule + overlay skipped: dormant (HOME is com.miui.home: stock HyperOS gestures and recents this boot)" ]; then
  ok post_fs_data_dormant_mounts_no_overlay; else bad "post_fs_data_dormant_mounts_no_overlay ($(mstatus))"; fi
# In self mode it mounts system/ alone: the app stays installed (its gesture-line overlay works
# with any HOME), SystemUI keeps MiuiHome's recents.
setup; mountfix; roles "$MIUI"; rm -rf "$T/modules/hybrid_mount"; hg_post_fs_data_main "$T/mod"
if [ "$(sysmounts)" = 3 ] && [ "$(ovlmounts)" = 0 ] && [ -f "$T/data/dormant" ] && [ -f "$T/mod/skip_mount" ] \
   && [ "$(mstatus)" = "dormant (HOME is com.miui.home), app only: ok" ] \
   && grep -q "post-fs-data: dormant (HOME is com.miui.home: .*): the app alone is mounted (no recents overlay)" "$T/data/log.txt"; then
  ok post_fs_data_dormant_in_self_mode_mounts_only_the_app; else bad "post_fs_data_dormant_in_self_mode_mounts_only_the_app ($(mstatus))"; fi
setup; mountfix; roles "$MIUI"; selfcfg 'STRICT_FIRMWARE=1\n'; echo "$OTA" > "$T/mod/expected-fingerprint"; hg_post_fs_data_main "$T/mod"
if nomount && [ -f "$T/data/dormant" ] && mstatus | grep -q "^skipped: dormant (HOME is com.miui.home: .*), the app too: firmware mismatch.*(STRICT_FIRMWARE=1)\$"; then
  ok post_fs_data_dormant_strict_mounts_nothing_on_firmware_mismatch; else bad "post_fs_data_dormant_strict_mounts_nothing_on_firmware_mismatch ($(mstatus))"; fi
setup; mountfix; echo "$MIUI" > "$T/data/dormant"; hg_post_fs_data_main "$T/mod"
a=0; [ -e "$T/data/dormant" ] && a=1; roles ""; hg_post_fs_data_main "$T/mod"
if [ "$a" = 0 ] && [ ! -e "$T/data/dormant" ] && [ "$(ovlmounts)" = 2 ] && grep -q "HOME unknown (.* unreadable or without a HOME holder): active boot" "$T/data/log.txt"; then
  ok post_fs_data_is_active_with_another_or_an_unknown_home; else bad "post_fs_data_is_active_with_another_or_an_unknown_home ($a $(mstatus))"; fi
# service.sh on a dormant boot: nothing set up; once after an active boot, what it changed goes back.
dormant() { # a dormant boot of $T/svc: $1 = more config lines
  setup; live_nav; mountfix; mkmod; cp -R "$T/mod/overlay" "$T/svc/"; roles "$MIUI"
  mkdir -p "$T/root/system/priv-app/HyperGestures" # what the metamodule mounts
  printf 'NAV_MODE=gestural\nDISABLE_MIUIHOME=1\nWATCHDOG=0\n%b' "${1:-}" > "$T/data/config"
  printf 'FSG=1\nNAVBAR=\n' > "$T/data/original-nav.txt"; printf '[ ] %s\n[x] %s\n' "$THREEBUTTON" "$GESTURAL" > "$T/overlays"; echo 0 > "$T/fsg"
  hg_post_fs_data_main "$T/svc"; rm -f "$T/calls"
}
dormant; hg_service_main "$T/svc"
if has "appops set com.miui.home RUN_ANY_IN_BACKGROUND default" && has "pm enable com.miui.home" && ! on "$GESTURAL" && [ "$(cat "$T/fsg")" = 1 ] \
   && ! has "force-stop" && ! has "enable-exclusive --category $GESTURAL" && ! has "^dumpsys" && ! has "^notify" && ! has "add-role-holder" \
   && [ -f "$T/data/dormant-restored" ] && [ -x "$HG_GUARD" ] \
   && grep -q "^not run: dormant: HOME is com.miui.home (choose another launcher and reboot to use the module)" "$T/data/health-status"; then first=1; else first=0; fi
rm -f "$T/calls"; printf '[ ] %s\n[x] %s\n' "$THREEBUTTON" "$GESTURAL" > "$T/overlays"; hg_service_main "$T/svc" # the next dormant boot
if [ "$first" = 1 ] && ! has "pm enable" && ! has "overlay disable" && on "$GESTURAL"; then
  ok service_dormant_sets_nothing_up_and_restores_once_after_an_active_boot; else bad "service_dormant_sets_nothing_up_and_restores_once_after_an_active_boot (first=$first)"; fi
roles "$NOVA"; hg_post_fs_data_main "$T/svc"; stub dumpsys "cat \"$FIX/sysui_connected.txt\""; hg_service_main "$T/svc" # Nova again: active
if [ ! -e "$T/data/dormant" ] && [ ! -e "$T/data/dormant-restored" ] && has "am force-stop com.miui.home" && grep -q '^ok' "$T/data/health-status"; then
  ok service_active_again_after_dormant_sets_up_and_rearms_the_restore; else bad "service_active_again_after_dormant_sets_up_and_rearms_the_restore ($(cat "$T/data/health-status"))"; fi
# A rollback gives HOME back to MiuiHome; re-enabled while it still is, the module stays dormant.
dormant; hg_rollback "a test" "$T/svc" >/dev/null 2>&1; rm -f "$T/svc/disable" "$T/calls" "$T/data/dormant-restored"
hg_post_fs_data_main "$T/svc"; hg_service_main "$T/svc"
if [ -f "$T/data/dormant" ] && nomount && ! has "force-stop" && ! has "^notify" && grep -q "^not run: dormant" "$T/data/health-status"; then
  ok boot_after_a_rollback_with_miuihome_as_home_is_dormant; else bad "boot_after_a_rollback_with_miuihome_as_home_is_dormant ($(cat "$T/data/health-status"))"; fi
# The dormant boot's watchdog only watches HOME: one notification when another launcher is chosen.
dormant 'WATCHDOG=1\n'
tick() { advance "$1"; n=$(wc -l < "$T/sleeps"); [ "$n" = 1 ] && roles "$NOVA" "" new; [ "$n" = 2 ] && roles "$NOVA" "" new
  [ "$n" = 3 ] && : > "$T/svc/disable"; return 0; }
hg_service_main "$T/svc"; tick() { advance "$1"; crash_tick; }
if [ "$(sleeps)" = "120 120 120 " ] && [ "$(notified | grep -c .)" = 1 ] \
   && notified | grep -qF "HyperGestures: reboot to switch gestures to the new launcher (com.teslacoilsw.launcher)" \
   && ! has "^dumpsys" && grep -q "watchdog: stopped (the module is disabled)" "$T/data/log.txt"; then
  ok dormant_watchdog_asks_for_a_reboot_once_another_launcher_is_home; else bad "dormant_watchdog_asks_for_a_reboot_once_another_launcher_is_home ($(sleeps); $(notified | grep -c .))"; fi
# An active boot's watchdog: HOME becomes MiuiHome, one notification per change across the boundary.
wsetup; hg_home_init; wd; rm -f "$T/calls"; wtick 700; wd; a=0; [ -f "$T/calls" ] && a=1 # nothing written: no command
roles "$MIUI" "" new; wtick 120; wd; n1=$(notified | grep -c .)
roles "$MIUI" "" new; wtick 120; wd; n2=$(notified | grep -c .) # RoleService saved again: same HOME
roles "$NOVA" "" new; wtick 120; wd; n3=$(notified | grep -c .) # back: nothing to say
roles "$MIUI" "" new; wtick 120; wd; n4=$(notified | grep -c .)
if [ "$a$n1$n2$n3$n4" = 01112 ] && [ "$HG_WD" = 0 ] && notified | grep -qF "HyperGestures: reboot to switch gestures to MiuiHome" \
   && grep -q "HOME is now com.miui.home: reboot to switch gestures to MiuiHome" "$T/data/log.txt"; then
  ok active_watchdog_asks_for_a_reboot_once_per_home_change; else bad "active_watchdog_asks_for_a_reboot_once_per_home_change ($a$n1$n2$n3$n4)"; fi
wsetup; roles "$MIUI"; hg_home_init # HOME changed between post-fs-data (active) and service.sh
if [ "$(notified | grep -c .)" = 1 ] && notified | grep -qF "reboot to switch gestures to MiuiHome"; then
  ok home_watch_start_catches_a_change_since_post_fs_data; else bad home_watch_start_catches_a_change_since_post_fs_data; fi
wsetup; roles ""; hg_home_init; wd
if [ -z "$_hg_roles" ] && [ "$HG_WD" = 0 ] && ! has "^notify"; then ok home_watch_without_roles_xml_stays_quiet; else bad home_watch_without_roles_xml_stays_quiet; fi
wsetup; roles ""; stub role "echo $MIUI"; hg_home_init
if [ -z "$_hg_roles" ] && [ "$(notified | grep -c .)" = 1 ] && notified | grep -qF "reboot to switch gestures to MiuiHome"; then
  ok home_watch_without_roles_xml_checks_cmd_role_once; else bad home_watch_without_roles_xml_checks_cmd_role_once; fi
# Once a boot, roles.xml against `cmd role`: a roles.xml naming no holder (a changed format)
# leaves the module going by cmd role, from the next post-fs-data on (home-role), not "unknown".
setup; mountfix; printf 'ABX\000\001binary xml' > "$T/roles.xml"; stub role "echo $MIUI"; hg_home_crosscheck
a=$(cat "$T/data/home-role" 2>/dev/null); hg_post_fs_data_main "$T/mod"
if [ "$a" = "$MIUI" ] && [ "$(cat "$T/data/dormant")" = "$MIUI" ] && nomount \
   && has "role get-role-holders --user 0 android.app.role.HOME" \
   && grep -q "HOME check: roles.xml (.*) names no HOME holder, cmd role says com.miui.home" "$T/data/log.txt"    && grep -q "going by cmd role's answer at the last boot (com.miui.home)" "$T/data/log.txt"; then
  ok home_crosscheck_lets_cmd_role_stand_in_for_an_unreadable_roles_xml
else bad "home_crosscheck_lets_cmd_role_stand_in_for_an_unreadable_roles_xml ($a)"; fi
# A mismatch is logged; roles.xml still decides. A failed cmd role changes nothing; no holder
# (setup, the chooser) drops the record; anything not a package name is no holder.
setup; roles "$NOVA"; stub role "echo $MIUI"; hg_home_crosscheck; a=$(hstate)
b=0; grep -q "HOME check: roles.xml says $NOVA but cmd role says $MIUI; roles.xml decides" "$T/data/log.txt" && b=1
stub role 'exit 1'; hg_home_crosscheck; c=$(cat "$T/data/home-role" 2>/dev/null)
stub role 'echo "Error: unknown role"'; hg_home_crosscheck; d=0; [ -e "$T/data/home-role" ] || d=1
stub role "echo $MIUI"; hg_home_crosscheck; stub role 'echo'; hg_home_crosscheck; e=0; [ -e "$T/data/home-role" ] || e=1
if [ "$a|$b|$c|$d|$e" = "active:$NOVA|1|$MIUI|1|1" ] && grep -q "HOME check: cmd role failed" "$T/data/log.txt"; then
  ok home_crosscheck_logs_a_mismatch_and_keeps_roles_xml_first; else bad "home_crosscheck_logs_a_mismatch_and_keeps_roles_xml_first ($a|$b|$c|$d|$e)"; fi
# Live (watchdog, action.sh): without a holder in roles.xml, cmd role now; several holders: the
# first. Not live (post-fs-data): never cmd role.
setup; roles sms.app nohome; stub role "printf '%s;other.pkg\\n' $MIUI"; a=$(hstate)
hg_home_state live; b="$HG_HOME_STATE:$HG_HOME:$HG_HOME_SRC"; c=$(cat "$T/data/home-role" 2>/dev/null)
if [ "$a" = "unknown:" ] && [ "$b" = "dormant:$MIUI:cmd role" ] && [ "$c" = "$MIUI" ]; then
  ok home_state_live_asks_cmd_role_when_roles_xml_names_none; else bad "home_state_live_asks_cmd_role_when_roles_xml_names_none ($a|$b|$c)"; fi
# Every service.sh run checks once (here: an active boot that ends without the watchdog).
setup; mkmod; printf 'NAV_MODE=gestural\nWATCHDOG=0\n' > "$T/data/config"; stub role "echo $MIUI"; hg_service_main "$T/svc"
if [ "$(grep -c "role get-role-holders --user 0" "$T/calls")" = 1 ] && [ "$(cat "$T/data/home-role")" = "$MIUI" ]; then
  ok service_checks_home_with_cmd_role_once; else bad service_checks_home_with_cmd_role_once; fi

# --- 0.3.5-alpha leftovers and the static overlay's idmap (post-fs-data) ---------------------
rescache() { # 0.3.5's fabricated overlay files, our static overlay's idmap, and others that must stay
  mkdir -p "$T/rescache"
  for f in com.android.shell-HyperGestures-a1B2.frro data@resource-cache@com.android.shell-HyperGestures-a1B2.frro@idmap \
           product@overlay@HyperGesturesOverlay.apk@idmap \
           com.android.shell-HyperGesturesX-zz99.frro com.android.systemui-Other-q1w2.frro product@overlay@Foo.apk@idmap; do
    : > "$T/rescache/$f"; done
}
others_kept() { for f in com.android.shell-HyperGesturesX-zz99.frro com.android.systemui-Other-q1w2.frro product@overlay@Foo.apk@idmap; do
  [ -e "$T/rescache/$f" ] || return 1; done; }
frro_gone() { [ ! -e "$T/rescache/com.android.shell-HyperGestures-a1B2.frro" ] \
  && [ ! -e "$T/rescache/data@resource-cache@com.android.shell-HyperGestures-a1B2.frro@idmap" ] && others_kept; }
oldguard() { mkdir -p "$T/post-fs-data.d"; printf '#!/system/bin/sh\nexit 0\n' > "$HG_OLD_OVERLAY_GUARD"; chmod 755 "$HG_OLD_OVERLAY_GUARD"
  printf '#!/system/bin/sh\n' > "$T/post-fs-data.d/someone-else.sh"; }
setup; mountfix; rescache; oldguard; hg_post_fs_data_main "$T/mod"
if frro_gone && [ ! -e "$T/rescache/product@overlay@HyperGesturesOverlay.apk@idmap" ] && [ ! -e "$HG_OLD_OVERLAY_GUARD" ] \
   && [ -e "$T/post-fs-data.d/someone-else.sh" ] && [ "$(mstatus)" = "metamodule + overlay ok" ] \
   && grep -q "0.3.5 leftovers deleted (fabricated recents overlay, post-fs-data.d guard): com.android.shell-HyperGestures-a1B2.frro data@resource-cache@com.android.shell-HyperGestures-a1B2.frro@idmap hypergestures-overlay-guard.sh" "$T/data/log.txt"; then
  ok post_fs_data_deletes_the_0_3_5_leftovers_and_the_static_overlay_idmap; else bad post_fs_data_deletes_the_0_3_5_leftovers_and_the_static_overlay_idmap; fi
rm -f "$T/data/log.txt"; hg_post_fs_data_main "$T/mod" # the next boot: nothing left, nothing logged
if ! grep -q "0.3.5 leftovers" "$T/data/log.txt"; then ok post_fs_data_leftover_cleanup_is_quiet_once_done; else bad post_fs_data_leftover_cleanup_is_quiet_once_done; fi
setup; mountfix; rescache; oldguard; echo 3 > "$T/data/boot-attempts"; hg_post_fs_data_main "$T/mod"
if frro_gone && [ ! -e "$HG_OLD_OVERLAY_GUARD" ] && [ -f "$T/mod/disable" ] && nomount; then
  ok post_fs_data_deletes_the_leftovers_on_a_boot_loop_trip_too; else bad post_fs_data_deletes_the_leftovers_on_a_boot_loop_trip_too; fi
setup; rescache; oldguard; hg_install_guard "$TPL"; printf 'FSG=0\nNAVBAR=\n' > "$T/data/original-nav.txt"; HG_SYNC=1 $SH "$TPL/uninstall.sh"
if frro_gone && [ ! -e "$HG_OLD_OVERLAY_GUARD" ] && [ ! -e "$HG_GUARD" ] && [ ! -e "$T/data" ] && has "pm enable com.miui.home"; then
  ok uninstall_deletes_the_0_3_5_leftovers; else bad uninstall_deletes_the_0_3_5_leftovers; fi
setup; mkmod; printf 'NAV_MODE=gestural\nWATCHDOG=0\n' > "$T/data/config"; hg_service_main "$T/svc"
if [ -x "$HG_GUARD" ] && [ ! -e "$HG_OLD_OVERLAY_GUARD" ] && [ ! -d "$T/post-fs-data.d" ] && ! has "overlay fabricate" \
   && ! has "^overlay enable " && ! has "^kill" && ! has "pidof com.android.systemui"; then
  ok service_installs_only_the_service_d_guard_and_no_fabricated_overlay; else bad service_installs_only_the_service_d_guard_and_no_fabricated_overlay; fi

# --- watchdog keeps gestural -----------------------------------------------------------------
setup; live_nav; NAV_MODE=gestural; printf '[x] %s\n[ ] %s\n' "$THREEBUTTON" "$GESTURAL" > "$T/overlays"
if hg_watchdog_step "$T/mod" && [ "$HG_WD" = 0 ] && on "$GESTURAL" && grep -q "gestural was reverted" "$T/data/log.txt"; then
  ok watchdog_reapplies_reverted_gestural; else bad watchdog_reapplies_reverted_gestural; fi
setup; live_nav; NAV_MODE=gestural; printf '[ ] %s\n[x] %s\n' "$THREEBUTTON" "$GESTURAL" > "$T/overlays"
if hg_watchdog_step "$T/mod" && [ "$HG_WD" = 0 ] && ! has "enable-exclusive"; then
  ok watchdog_leaves_active_gestural_alone; else bad watchdog_leaves_active_gestural_alone; fi
setup; live_nav; NAV_MODE=threebutton; printf '[x] %s\n[ ] %s\n' "$THREEBUTTON" "$GESTURAL" > "$T/overlays"
if hg_watchdog_step "$T/mod" && [ "$HG_WD" = 0 ] && ! has "enable-exclusive"; then
  ok watchdog_reapply_only_for_gestural; else bad watchdog_reapply_only_for_gestural; fi
# restore.sh ran this boot (marker restored): the owner's nav stays, gestural is not re-applied, and
# the watchdog stops (nothing left to guard).
setup; live_nav; NAV_MODE=gestural; printf '[x] %s\n[ ] %s\n' "$THREEBUTTON" "$GESTURAL" > "$T/overlays"
: > "$T/data/restored"
if hg_watchdog_step "$T/mod" && [ "$HG_WD" = stop ] && ! has "enable-exclusive" && on "$THREEBUTTON" && ! hg_gestural_reverted; then
  ok watchdog_honours_restored_marker; else bad watchdog_honours_restored_marker; fi
# SystemUI keeps reverting (the overlay stub never changes state): 3 re-applies per boot, then the
# 4th reverted check leaves navigation alone and notifies once; later checks stay quiet.
setup; NAV_MODE=gestural; printf '[x] %s\n[ ] %s\n' "$THREEBUTTON" "$GESTURAL" > "$T/overlays"
hg_watchdog_step "$T/mod"; hg_watchdog_step "$T/mod"; hg_watchdog_step "$T/mod"
three=$(grep -c "^overlay enable-exclusive --category $GESTURAL" "$T/calls")
hg_watchdog_step "$T/mod"; r=$HG_WD
four=$(grep -c "^overlay enable-exclusive --category $GESTURAL" "$T/calls"); n4=$(notified | wc -l)
hg_watchdog_step "$T/mod"
five=$(grep -c "^overlay enable-exclusive --category $GESTURAL" "$T/calls"); n5=$(notified | wc -l)
if [ "$three" = 3 ] && [ "$r" = 0 ] && [ "$four" = 3 ] && [ "$five" = 3 ] && [ "$n4" = 1 ] && [ "$n5" = 1 ] \
   && notified | grep -q "navigation keeps switching away from gestures" \
   && [ "$(grep -c "gestural reverted 3 times this boot, leaving navigation as it is" "$T/data/log.txt")" = 1 ]; then
  ok watchdog_reapplies_gestural_at_most_3_times_per_boot
else bad "watchdog_reapplies_gestural_at_most_3_times_per_boot (re-applies $three/$four/$five, notifications $n4/$n5)"; fi
# Real `cmd overlay list` output (grouped by target, CRLF from adb): gestural counts as active.
setup; live_nav; NAV_MODE=gestural
printf 'android\r\n[ ] com.android.internal.display.cutout.emulation.corner\r\n[ ] %s\r\n[x] %s\r\n[ ] %s\r\ncom.android.systemui\r\n[x] com.android.systemui.gesture.line.overlay\r\n' \
  "$THREEBUTTON" "$GESTURAL" "$TWOBUTTON" > "$T/overlays"
if hg_watchdog_step "$T/mod" && [ "$HG_WD" = 0 ] && ! has "enable-exclusive" && [ "$(hg_enabled_navbar_overlays)" = "$GESTURAL" ]; then
  ok watchdog_parses_overlay_list_with_cr; else bad watchdog_parses_overlay_list_with_cr; fi
# navigation_mode (SystemUI's own record of the nav mode) first: 2 is gestural without listing the
# overlays, whatever format the list has; 0 is confirmed by the list (SystemUI may not have
# written it yet right after our enable); unreadable: the list alone, as before.
navmode() { # live_nav, and `settings get secure navigation_mode` answering $1
  live_nav; echo "$1" > "$T/navmode"
  stub settings "case \"\$*\" in \"get secure navigation_mode\") cat \"$T/navmode\";; \"get global force_fsg_nav_bar\") cat \"$T/fsg\";; \"put global force_fsg_nav_bar \"*) echo \"\$4\" > \"$T/fsg\";; esac"
}
setup; navmode 2; NAV_MODE=gestural; printf 'a format we do not know\n' > "$T/overlays"
if hg_watchdog_step "$T/mod" && [ "$HG_WD" = 0 ] && ! has "enable-exclusive" && ! has "^overlay list" \
   && has "settings get secure navigation_mode"; then
  ok gestural_revert_watch_goes_by_navigation_mode; else bad gestural_revert_watch_goes_by_navigation_mode; fi
setup; navmode 0; NAV_MODE=gestural; printf '[ ] %s\n[x] %s\n' "$THREEBUTTON" "$GESTURAL" > "$T/overlays"; a=0; hg_gestural_reverted && a=1
printf '[x] %s\n[ ] %s\n' "$THREEBUTTON" "$GESTURAL" > "$T/overlays"; hg_watchdog_step "$T/mod"
if [ "$a" = 0 ] && on "$GESTURAL" && grep -q "gestural was reverted" "$T/data/log.txt"; then
  ok gestural_revert_by_navigation_mode_is_confirmed_by_the_overlays; else bad "gestural_revert_by_navigation_mode_is_confirmed_by_the_overlays ($a)"; fi
setup; navmode 'null'; NAV_MODE=gestural; printf '[x] %s\n[ ] %s\n' "$THREEBUTTON" "$GESTURAL" > "$T/overlays"; hg_watchdog_step "$T/mod"
if on "$GESTURAL" && has "^overlay list"; then ok gestural_revert_without_navigation_mode_reads_the_overlays
else bad gestural_revert_without_navigation_mode_reads_the_overlays; fi
# A gesture mode for the 3-button fallback: navigation_mode 2 alone suffices; with 0 the overlays
# and force_fsg_nav_bar still count (a stale value must not hide a gesture mode).
setup; navmode 2; echo 0 > "$T/fsg"; printf 'a format we do not know\n' > "$T/overlays"; a=0; hg_gesture_nav_active && a=1
b=0; has "^overlay list" && b=1
navmode 0; printf '[ ] %s\n[x] %s\n' "$THREEBUTTON" "$GESTURAL" > "$T/overlays"; c=0; hg_gesture_nav_active && c=1
printf '[x] %s\n' "$THREEBUTTON" > "$T/overlays"; d=0; hg_gesture_nav_active && d=1
echo 1 > "$T/fsg"; e=0; hg_gesture_nav_active && e=1
if [ "$a$b$c$d$e" = 10101 ]; then ok gesture_nav_active_goes_by_navigation_mode_then_the_rest
else bad "gesture_nav_active_goes_by_navigation_mode_then_the_rest ($a$b$c$d$e)"; fi
# Right after the rollback's restore navigation_mode may still say gestural: the rollback goes by
# the overlays and force_fsg_nav_bar alone.
setup; navmode 2; echo 0 > "$T/fsg"; printf '[x] %s\n[ ] %s\n' "$THREEBUTTON" "$GESTURAL" > "$T/overlays"
printf 'FSG=0\nNAVBAR=%s\n' "$THREEBUTTON" > "$T/data/original-nav.txt"; mkdir -p "$T/mod"; hg_rollback "a test" "$T/mod" >/dev/null 2>&1
if grep -q "rollback: 2/6 navigation for the rest of this boot: not needed (no gesture mode)" "$T/data/log.txt"; then
  ok rollback_goes_by_the_overlays_not_navigation_mode; else bad rollback_goes_by_the_overlays_not_navigation_mode; fi
# Both per-boot markers are cleared at boot: a new boot re-applies again and forgets restore.sh.
setup; mkmod; printf 'NAV_MODE=gestural\nDISABLE_MIUIHOME=1\nWATCHDOG=0\n' > "$T/data/config"
: > "$T/data/restored"; echo 4 > "$T/data/gestural-reapplies"; : > "$T/data/watchdog-stop"; echo "sysui dump: old" > "$T/data/sysui-dump"
hg_service_main "$T/svc"
if [ ! -e "$T/data/restored" ] && [ ! -e "$T/data/gestural-reapplies" ] && [ ! -e "$T/data/watchdog-stop" ] \
   && ! grep -q old "$T/data/sysui-dump" && has "overlay enable-exclusive --category $GESTURAL"; then
  ok service_clears_per_boot_watchdog_markers; else bad service_clears_per_boot_watchdog_markers; fi
# log.txt is capped: over 256 KB at boot it becomes log.old.txt (one old file kept).
setup; mkmod; printf 'NAV_MODE=gestural\nWATCHDOG=0\n' > "$T/data/config"; head -c 300000 /dev/zero | tr '\0' x > "$T/data/log.txt"
hg_service_main "$T/svc"
if [ "$(wc -c < "$T/data/log.old.txt")" -eq 300000 ] && [ "$(wc -c < "$T/data/log.txt")" -lt 20000 ] \
   && grep -q "log rotated: the previous 300000 bytes are in log.old.txt" "$T/data/log.txt"; then
  ok service_rotates_a_log_over_256_kb_keeping_one_old_file; else bad service_rotates_a_log_over_256_kb_keeping_one_old_file; fi
setup; mkmod; printf 'NAV_MODE=gestural\nWATCHDOG=0\n' > "$T/data/config"; echo "earlier boots" > "$T/data/log.txt"; hg_service_main "$T/svc"
if [ ! -e "$T/data/log.old.txt" ] && grep -q "earlier boots" "$T/data/log.txt"; then ok service_keeps_a_small_log; else bad service_keeps_a_small_log; fi
# The app's log copy (Save logs): root never writes it. hg_mirror_sync hands root's own log.txt
# and log.old.txt to `su <app uid> -c`, which writes them (tmp + mv) as the app, in a background
# job root never waits for; only when a log changed, at most every HG_MIRROR_ROUNDS rounds.
AF="$T/appfiles/files"
msetup() { setup; unset _hg_ms_off; }
mwait() { # until the background copy has ended (at most 15 s)
  [ -f "$T/data/mirror-pid" ] || return 0
  _p=$(cat "$T/data/mirror-pid"); _n=0
  while hg_job_running "$_p" && [ "$_n" -lt 300 ]; do /bin/sleep 0.05; _n=$((_n + 1)); done
}
msync() { hg_mirror_sync "$@"; mwait; }
sus() { cat "$T/calls" 2>/dev/null | grep -c "^su "; }
# Root never looks at the app's dir: the su'd script finds it missing (rc 3), and the copy then
# waits for log.txt's next change, taking the old log along.
msetup; echo "rotated out" > "$T/data/log.old.txt"; hg_log "no app dir"; msync now; a=$(sus)
msync now; msync now; b=$(sus)
mkdir -p "$AF"; touch -d '2000-01-01' "$T/data/mirror-mark"; hg_log "page opened"; msync now
if [ "$a|$b" = "1|1" ] && [ "$(cat "$AF/module-log.old.txt")" = "rotated out" ] && grep -q "page opened$" "$AF/module-log.txt"; then
  ok mirror_sync_waits_for_the_app_dir_without_looking_at_it; else bad "mirror_sync_waits_for_the_app_dir_without_looking_at_it ($a|$b)"; fi
msetup; mkdir -p "$AF"; echo "rotated out" > "$T/data/log.old.txt"; hg_log "a line"; msync
if cmp -s "$T/data/log.txt" "$AF/module-log.txt" && [ "$(cat "$AF/module-log.old.txt")" = "rotated out" ] \
   && [ "$(grep -c "^su 10234 -c " "$T/calls")" = 2 ] && ! grep -q "^su [^1]" "$T/calls" \
   && [ ! -e "$AF/module-log.txt.tmp" ] && [ -f "$T/data/mirror-mark" ] && [ -f "$T/data/mirror-mark-old" ] \
   && [ "$(cat "$T/data/mirror-rc")" = 0 ]; then
  ok mirror_sync_copies_roots_logs_as_the_apps_uid; else bad mirror_sync_copies_roots_logs_as_the_apps_uid; fi
# Unchanged logs: no su, no pm, nothing (a round's cost stays builtins). Changed: the next copy
# waits HG_MIRROR_ROUNDS rounds; `now` (service.sh's end) does not wait; the uid is looked up once.
rm -f "$T/calls"; msync; msync now; a=0; [ -f "$T/calls" ] && a=1
touch -d '2000-01-01' "$T/data/mirror-mark"; hg_log "later"
msync; msync; msync; msync; b=$(sus)
msync; c=$(sus)
touch -d '2000-01-01' "$T/data/mirror-mark"; hg_log "at the end"; msync now; d=$(sus)
if [ "$a|$b|$c|$d" = "0|0|1|2" ] && ! has "list packages -U" && tail -n 1 "$AF/module-log.txt" | grep -q "at the end$"; then
  ok mirror_sync_runs_only_for_a_changed_log_and_at_most_every_few_rounds
else bad "mirror_sync_runs_only_for_a_changed_log_and_at_most_every_few_rounds ($a|$b|$c|$d)"; fi
# Anything the app plants at the copy's path (a link to root's file, a FIFO, a dir) only ever meets
# the app's own uid: root hands the data to su and never opens the path (here su only records).
plant() { msetup; hg_log "before"; mkdir -p "$AF" "$T/victimdir"; echo "root's file" > "$T/victim"; }
victim_ok() { [ "$(cat "$T/victim")" = "root's file" ] && [ -z "$(ls -A "$T/victimdir")" ]; }
plant; ln -s "$T/victim" "$AF/module-log.txt"; stub su 'cat > /dev/null'; msync now
a=0; victim_ok && [ -L "$AF/module-log.txt" ] && has "^su 10234 -c .* cd '$AF' " && a=1
plant; rm -rf "$AF"; ln -s "$T/victimdir" "$AF"; stub su 'cat > /dev/null'; msync now
b=0; victim_ok && has "^su 10234 -c" && b=1
plant; mkfifo "$AF/module-log.txt"; stub su 'cat > /dev/null'
timeout 20 $SH -c ". \"$TPL/common/functions.sh\"; hg_log fifo; hg_mirror_sync now"; r=$?; mwait
c=0; [ "$r" = 0 ] && [ -p "$AF/module-log.txt" ] && has "^su 10234 -c" && c=1
if [ "$a$b$c" = 111 ]; then ok mirror_sync_leaves_what_the_app_planted_to_the_apps_uid
else bad "mirror_sync_leaves_what_the_app_planted_to_the_apps_uid ($a$b$c)"; fi
# Root never waits for the copy: a su'd writer that hangs and ignores TERM (as one asleep on FUSE
# would) leaves the round returning at once; while it runs no second su starts; once the time
# limit (and the KILL 2 s after) ended it, the next round turns copying off, logged once.
plant; head -c 100000 /dev/zero | tr '\0' x >> "$T/data/log.txt"
stub su 'trap "" TERM; while :; do /bin/sleep 1; done'
HG_MIRROR_TIMEOUT=1 timeout 5 $SH -c ". \"$TPL/common/functions.sh\"; hg_mirror_sync now"; r=$?
_n=0; while ! has "^su " && [ "$_n" -lt 100 ]; do /bin/sleep 0.05; _n=$((_n + 1)); done # in flight
hg_log "more"; hg_mirror_sync now; a=$(sus); mwait; hg_log "even more"; hg_mirror_sync now; hg_mirror_sync now
if [ "$r" = 0 ] && [ "$a" = 1 ] && [ "$(sus)" = 1 ] && [ ! -e "$T/data/mirror-put" ] \
   && [ "$(grep -c "log copy: su to our app's uid failed (rc 1[23][0-9])" "$T/data/log.txt")" = 1 ]; then
  ok mirror_sync_never_waits_for_a_hung_copy; else bad "mirror_sync_never_waits_for_a_hung_copy (rc=$r su=$a/$(sus))"; fi
# The data goes from a root-only file, not a pipe: a child left holding stdin blocks nothing.
plant; head -c 100000 /dev/zero | tr '\0' x >> "$T/data/log.txt"; stub su 'sleep 25 & exit 0'
timeout 20 $SH -c ". \"$TPL/common/functions.sh\"; hg_mirror_sync now"; r=$?; mwait
if [ "$r" = 0 ] && [ ! -e "$T/data/mirror-put" ] && has "^su 10234 -c" && [ "$(cat "$T/data/mirror-rc")" = 0 ]; then
  ok mirror_sync_never_blocks_on_a_writer_that_does_not_read; else bad "mirror_sync_never_blocks_on_a_writer_that_does_not_read (rc=$r)"; fi
# A su that does not drop to the app's uid, gid and groups (it would write as root): the script
# stops (rc 4), nothing is written, copying is off for the boot, logged once.
dropcase() { msetup; mkdir -p "$AF"; su_as "$@"; hg_log "x"; msync now; hg_log "y"; msync now; hg_log "z"; msync now
  [ -z "$(ls -A "$AF")" ] && [ "$(sus)" = 1 ] \
    && [ "$(grep -c "log copy: su did not switch to our app's uid and groups" "$T/data/log.txt")" = 1 ]; }
a=0; dropcase 0 && a=1; b=0; dropcase '' 0 && b=1; c=0; dropcase '' '' '10234 0 3003' && c=1
if [ "$a$b$c" = 111 ]; then ok mirror_sync_stops_when_su_does_not_drop_to_the_apps_ids
else bad "mirror_sync_stops_when_su_does_not_drop_to_the_apps_ids ($a$b$c)"; fi
# su to the uid refused (a KernelSU without it): no copy, logged once, no root write instead.
msetup; mkdir -p "$AF"; stub su 'exit 1'; hg_log "x"; msync now; hg_log "y"; msync now; hg_log "z"; msync now
if [ ! -e "$AF/module-log.txt" ] && [ "$(sus)" = 1 ] \
   && [ "$(grep -c "log copy: su to our app's uid failed (rc 1)" "$T/data/log.txt")" = 1 ]; then
  ok mirror_sync_without_su_to_the_uid_makes_no_copy; else bad mirror_sync_without_su_to_the_uid_makes_no_copy; fi
# A job gone without a result (killed): off too. A pid file left by an earlier boot is cleared.
msetup; mkdir -p "$AF"; sh -c 'exit 0' & wait; echo "$!" > "$T/data/mirror-pid"; hg_log "x"; msync now
if [ "$(sus)" = 0 ] && grep -q "log copy: the copy ended without a result" "$T/data/log.txt"; then
  ok mirror_sync_turns_off_after_a_job_without_a_result; else bad mirror_sync_turns_off_after_a_job_without_a_result; fi
# The uid comes from pm's exact line only, and must be an app uid.
uidcase() { msetup; mkdir -p "$AF"; stub pm "printf '$1'"; hg_log "x"; msync now; }
uidcase 'package:com.hypergestures.other uid:10999\n'; a=0; ! has "^su " && a=1
uidcase 'package:com.hypergestures uid:1000\n'; b=0; ! has "^su " && b=1
uidcase 'package:com.hypergestures uid:10x34\n'; c=0; ! has "^su " && c=1
uidcase 'package:com.hypergestures uid:10234\r\n'; d=0; has "^su 10234 -c" && d=1
if [ "$a$b$c$d" = 1111 ]; then
  ok mirror_sync_takes_the_uid_from_pms_exact_line; else bad "mirror_sync_takes_the_uid_from_pms_exact_line ($a$b$c$d)"; fi
msetup; HG_LOG_MIRROR="$T/app'files/module-log.txt"; mkdir -p "$T/app'files"; hg_mirror_put "$T/data/log.txt" module-log.txt 10234; r=$?
HG_LOG_MIRROR="$T/appfiles/files/module-log.txt"
if [ "$r" = 1 ] && ! has "^su "; then ok mirror_put_refuses_a_quote_in_the_path; else bad mirror_put_refuses_a_quote_in_the_path; fi
# service.sh copies once more as it ends; each boot logs the version, which reaches the copy.
msetup; mkmod; printf 'NAV_MODE=gestural\nWATCHDOG=0\n' > "$T/data/config"; mkdir -p "$AF"
printf 'id=hypergestures\nversion=9.9-test\nversionCode=99\n' > "$T/svc/module.prop"
echo 99999 > "$T/data/mirror-pid"; hg_service_main "$T/svc"; msync now
if grep -q "service: module version 9.9-test (99)" "$AF/module-log.txt" && grep -q '^hg_mirror_sync now' "$TPL/service.sh"; then
  ok service_end_copies_the_log_with_the_version; else bad service_end_copies_the_log_with_the_version; fi
# The boot-loop count is reset before the log copy on /sdcard (whose FUSE mirror may block) is touched.
setup; mkmod; printf 'NAV_MODE=gestural
WATCHDOG=0
' > "$T/data/config"; echo 1 > "$T/data/boot-attempts"
printf 'id=hypergestures
version=9.9-test
versionCode=99
' > "$T/svc/module.prop"
hg_service_main "$T/svc"
f=$(grep -n "boot finished (boot_completed reached)" "$T/data/log.txt" | head -n 1 | cut -d: -f1)
v=$(grep -n "service: module version 9.9-test" "$T/data/log.txt" | head -n 1 | cut -d: -f1)
if [ ! -e "$T/data/boot-attempts" ] && [ -n "$f" ] && [ -n "$v" ] && [ "$f" -lt "$v" ]; then
  ok service_resets_the_boot_loop_count_before_the_log_copy
else bad "service_resets_the_boot_loop_count_before_the_log_copy ($f $v)"; fi
# --- the module version for the app (hg_publish_version: Settings.Global) --------------------
# The app's About and Save logs read hypergestures_module_version ("<version> (<code>)
# boot=<id>") and hypergestures_module_update. Settings, never files: root does not touch the
# app's data dir at all (a planted link there could lead it anywhere).
pvprop() { printf 'id=hypergestures\nversion=9.9-test\nversionCode=99\r\n' > "$1/module.prop"; }
pvsetup() { setup; mkmod; pvprop "$T/svc"; }
gget() { cat "$T/global/$1" 2>/dev/null; }
PVK=hypergestures_module_version; PVUK=hypergestures_module_update
PVWANT="9.9-test (99) boot=boot-1" # the running module's
PVUWANT="9.9-test (99)"            # customize.sh's: the installed one, until the reboot
pvlog() { cat "$T/data/log.txt" 2>/dev/null | grep -c "version setting: .* not written"; }
pvputs() { cat "$T/calls" 2>/dev/null | grep -c "^settings put global $1 "; }
if ! grep -rq 'user_de\|HG_APP_DE\|hg_app_file\|hg_app_rm' "$TPL"; then
  ok module_never_names_the_apps_data_dir
else bad "module_never_names_the_apps_data_dir ($(grep -rl 'user_de\|HG_APP_DE' "$TPL"))"; fi
pvsetup; hg_publish_version "$T/svc"; r=$?
if [ "$r" = 0 ] && [ "$(gget $PVK)" = "$PVWANT" ] && [ "$(pvputs $PVK)" = 1 ] \
   && [ -z "$(gget $PVUK)" ] && [ "$(pvlog)" = 0 ]; then
  ok publish_writes_version_code_and_boot_id
else bad "publish_writes_version_code_and_boot_id ($r $(gget $PVK))"; fi
setup; mkmod; hg_publish_version "$T/svc"; r=$? # no module.prop
if [ "$r" = 1 ] && ! has "^settings put" && [ ! -e "$T/data/log.txt" ]; then
  ok publish_without_a_module_version_is_quiet
else bad "publish_without_a_module_version_is_quiet ($r)"; fi
pvsetup; : > "$T/global.fail"; hg_publish_version "$T/svc"; r=$?
if [ "$r" = 2 ] && [ -z "$(gget $PVK)" ] && [ "$(pvlog)" = 1 ] \
   && grep -q "version setting: $PVK not written (settings put failed" "$T/data/log.txt"; then
  ok publish_failing_put_returns_2_and_logs_why
else bad "publish_failing_put_returns_2_and_logs_why ($r)"; fi
# An installed update (customize.sh) goes to hypergestures_module_update, without a boot id;
# the running module's value stays as it is.
pvsetup; mkdir -p "$T/global"; echo "0.1-old (1) boot=boot-1" > "$T/global/$PVK"
hg_publish_version "$T/svc" update; r=$?
if [ "$r" = 0 ] && [ "$(gget $PVUK)" = "$PVUWANT" ] \
   && [ "$(gget $PVK)" = "0.1-old (1) boot=boot-1" ]; then
  ok publish_update_writes_module_update_only
else bad "publish_update_writes_module_update_only ($r $(gget $PVUK))"; fi
# service.sh: once a boot, after the version line, on active and dormant boots alike; the update
# customize.sh noted is the running module now, so hypergestures_module_update goes.
pvsetup; printf 'NAV_MODE=gestural\nWATCHDOG=0\n' > "$T/data/config"
mkdir -p "$T/global"; echo "x (1)" > "$T/global/$PVUK"; hg_service_main "$T/svc"
if [ "$(gget $PVK)" = "$PVWANT" ] && [ "$(pvputs $PVK)" = 1 ] && [ ! -e "$T/global/$PVUK" ] \
   && has "^settings delete global $PVUK$" \
   && grep -q "service: module version 9.9-test (99)$" "$T/data/log.txt"; then
  ok service_publishes_the_version_once_a_boot_and_drops_the_update_note
else bad service_publishes_the_version_once_a_boot_and_drops_the_update_note; fi
dormant; pvprop "$T/svc"; mkdir -p "$T/global"; echo "x (1)" > "$T/global/$PVUK"; boot 2
hg_service_main "$T/svc"
if [ "$(gget $PVK)" = "9.9-test (99) boot=boot-2" ] && [ "$(pvputs $PVK)" = 1 ] \
   && [ ! -e "$T/global/$PVUK" ] && grep -q "dormant boot: HOME is" "$T/data/log.txt"; then
  ok service_publishes_the_version_on_a_dormant_boot
else bad "service_publishes_the_version_on_a_dormant_boot ($(gget $PVK))"; fi
# Not written: the update note stays (the app compares it with the version the log names).
pvsetup; printf 'NAV_MODE=gestural\nWATCHDOG=0\n' > "$T/data/config"
mkdir -p "$T/global"; echo "x (1)" > "$T/global/$PVUK"; : > "$T/global.fail"
hg_service_main "$T/svc"
if [ -z "$(gget $PVK)" ] && [ "$(gget $PVUK)" = "x (1)" ] && ! has "^settings delete global $PVUK"
then ok service_keeps_the_update_note_when_the_version_is_not_written
else bad service_keeps_the_update_note_when_the_version_is_not_written; fi
# The app PackageManager runs against the module's versionCode (hg_app_version_check): a metamodule
# whose size+mtime sync kept the previous APK leaves the old app running after an update.
OLDDATE=1767225600 # 2026-01-01 00:00 UTC, the date every entry of the build's zip carries
avsetup() { # avsetup MODULE_VC PM_LINES: an active boot of $T/svc (module.prop says MODULE_VC) whose
  # files carry the zip's date, a stamp from the last boot, and pm answering --show-versioncode
  setup; mkmod; printf 'NAV_MODE=gestural\nWATCHDOG=0\n' > "$T/data/config"; echo stamp > "$T/data/apk-stamp"
  printf 'id=hypergestures\nversion=0.3.9.31-alpha\nversionCode=%s\r\n' "$1" > "$T/svc/module.prop"
  find "$T/svc" -exec touch -d "@$OLDDATE" {} +; touch -d "@$(( OLDDATE + 1 ))" "$T/ref"
  printf '%b' "$2" > "$T/pmvc"
  stub pm "case \"\$*\" in \"list packages --show-versioncode\"*) cat \"$T/pmvc\";; esac"
  # the module's APK (7 bytes) and the one the metamodule mounted (11 bytes)
  mkdir -p "$T/svc/system/priv-app/HyperGestures" "$T/root/system/priv-app/HyperGestures"
  printf 'apk-43\n' > "$T/svc/system/priv-app/HyperGestures/HyperGestures.apk"
  printf 'apk-42-old\n' > "$T/root/system/priv-app/HyperGestures/HyperGestures.apk"
  find "$T/svc" -exec touch -d "@$OLDDATE" {} +
}
nextboot() { # the next boot of the same phone: the zip's dates again (the metamodule kept it all)
  rm -f "$T/calls"; echo stamp > "$T/data/apk-stamp"; find "$T/svc" -exec touch -d "@$OLDDATE" {} +; boot "$1"
}
APKP=/system/priv-app/HyperGestures/HyperGestures.apk
redated() { [ -z "$(find "$T/svc" ! -newer "$T/ref")" ]; }   # every file and dir dated after the zip's
undated() { [ -z "$(find "$T/svc" -newer "$T/ref")" ]; }     # none touched
upd_notices() { notified | grep -c "update not applied yet"; }
avsetup 43 'package:com.hypergestures.x versionCode:7\npackage:com.hypergestures versionCode:42\r\n'
hg_service_main "$T/svc"
if [ ! -e "$T/data/apk-stamp" ] && redated && [ "$(upd_notices)" = 1 ] \
   && notified | grep -q "HyperGestures update not applied yet (app 42, module 43): reboot once more\.$" \
   && grep -q "service: app is 42 but the module is 43: the system kept the previous APK (module files re-dated" "$T/data/log.txt" \
   && grep -q "service: stale APK (boot 1 in a row): mounted $APKP 11 bytes, mtime .*; the module's 7 bytes, mtime " "$T/data/log.txt" \
   && notified | grep -q "^notify -S bigtext -t HyperGestures hypergestures-update HyperGestures update not applied yet" \
   && has "--es tag hypergestures-update" && [ "$(cat "$T/data/stale-boots")" = 1 ] \
   && [ "$(grep -c "list packages --show-versioncode com.hypergestures" "$T/calls")" = 1 ] && ! has "^reboot" \
   && has "enable-exclusive"; then # and the boot sets up as usual
  ok service_redates_and_notifies_once_when_the_app_is_older_than_the_module
else bad "service_redates_and_notifies_once_when_the_app_is_older_than_the_module ($(upd_notices))"; fi
avsetup 43 'package:com.hypergestures versionCode:43\n'; hg_service_main "$T/svc"
if [ -f "$T/data/apk-stamp" ] && undated && [ "$(upd_notices)" = 0 ] \
   && grep -q "service: app version matches the module (43)" "$T/data/log.txt"; then
  ok service_leaves_a_matching_app_alone
else bad "service_leaves_a_matching_app_alone ($(upd_notices))"; fi
# Compared as numbers ("100" sorts before "43" as text): a newer app (sideloaded over ours) is
# only logged, since no reboot changes it; an older one with fewer digits still counts as stale.
avsetup 43 'package:com.hypergestures versionCode:100\n'; hg_service_main "$T/svc"
if [ -f "$T/data/apk-stamp" ] && undated && [ "$(upd_notices)" = 0 ] \
   && [ "$(grep -c "service: app 100 is newer than the module 43: left alone" "$T/data/log.txt")" = 1 ]; then
  ok service_only_logs_an_app_newer_than_the_module
else bad "service_only_logs_an_app_newer_than_the_module ($(upd_notices))"; fi
avsetup 10 'package:com.hypergestures versionCode:9\n'; rm -f "$T/root$APKP"; hg_service_main "$T/svc"
if [ ! -e "$T/data/apk-stamp" ] && redated && [ "$(upd_notices)" = 1 ] \
   && grep -q "service: stale APK (boot 1 in a row): mounted $APKP unreadable; the module's 7 bytes" "$T/data/log.txt" \
   && notified | grep -q "HyperGestures update not applied yet (app 9, module 10): reboot once more\.$"; then
  ok service_compares_the_versions_as_numbers
else bad "service_compares_the_versions_as_numbers ($(upd_notices))"; fi
# A second stale boot in a row: a reboot did not help (the size+mtime theory may be wrong), so
# still re-date and drop the stamp, but tell the owner what else to do; a match or a newer app
# ends the run.
avsetup 43 'package:com.hypergestures versionCode:42\n'; hg_service_main "$T/svc"; nextboot 2; hg_service_main "$T/svc"
if [ ! -e "$T/data/apk-stamp" ] && redated && [ "$(upd_notices)" = 0 ] && [ "$(cat "$T/data/stale-boots")" = 2 ] \
   && [ "$(notified | grep -c "still not applied")" = 1 ] \
   && notified | grep -q "^notify -S bigtext -t HyperGestures hypergestures-update HyperGestures update still not applied after a reboot (app 42, module 43)\. Reinstall the module in KernelSU, or set MOUNT=self (setconf\.sh) and reboot\.$" \
   && grep -q "service: stale APK (boot 2 in a row): mounted $APKP 11 bytes" "$T/data/log.txt" \
   && grep -q "the system kept the previous APK again, after a reboot (module files re-dated, apk-stamp deleted)" "$T/data/log.txt" \
   && ! has "^reboot"; then
  ok service_says_a_reboot_did_not_help_on_the_second_stale_boot
else bad "service_says_a_reboot_did_not_help_on_the_second_stale_boot ($(cat "$T/data/stale-boots" 2>/dev/null))"; fi
printf 'package:com.hypergestures versionCode:43\n' > "$T/pmvc"; nextboot 3; hg_service_main "$T/svc"
a=$([ -e "$T/data/stale-boots" ] && echo kept)
avsetup 43 'package:com.hypergestures versionCode:44\n'; echo 3 > "$T/data/stale-boots"; hg_service_main "$T/svc"
b=$([ -e "$T/data/stale-boots" ] && echo kept)
avsetup 43 'package:com.hypergestures versionCode:42\n'; echo 1 > "$T/data/stale-boots"; hg_service_main "$T/svc"
if [ -z "$a$b" ] && [ "$(cat "$T/data/stale-boots")" = 2 ] && [ "$(notified | grep -c "still not applied")" = 1 ]; then
  ok service_stale_boot_count_ends_with_a_match_or_a_newer_app
else bad "service_stale_boot_count_ends_with_a_match_or_a_newer_app ($a $b)"; fi
# Other notices keep the shared tag (each replaces the last); the update notice has its own.
setup; hg_notify "plain"; hg_notify "own" hypergestures-update
if has "--es text plain --es tag hypergestures$" && has "--es text own --es tag hypergestures-update$" \
   && notified | grep -q "^notify -S bigtext -t HyperGestures hypergestures plain$" \
   && notified | grep -q "^notify -S bigtext -t HyperGestures hypergestures-update own$"; then
  ok notify_posts_under_the_given_tag_or_the_shared_one
else bad "notify_posts_under_the_given_tag_or_the_shared_one ($(cat "$T/calls"))"; fi
# find without `-exec {} +` (an older busybox): one touch per file instead.
setup; rm -rf "$T/fb"; mkdir -p "$T/fb/a"; echo x > "$T/fb/a/f"
find "$T/fb" -exec touch -d "@$OLDDATE" {} +; touch -d "@$(( OLDDATE + 1 ))" "$T/ref"
find() { case "$*" in *"{} +"*) echo "find plus" >> "$T/calls"; return 1 ;; esac; echo "find each" >> "$T/calls"; command find "$@"; }
hg_redate_files "$T/fb"; rc=$?; unset -f find
if [ "$rc" = 0 ] && has "find plus" && has "find each" && [ -z "$(find "$T/fb" ! -newer "$T/ref")" ]; then
  ok redate_falls_back_to_one_touch_per_file
else bad "redate_falls_back_to_one_touch_per_file ($rc)"; fi
# Unreadable: pm prints nothing (app not installed, pm failing), only another package, no versionCode.
for pmout in "none:" "other:package:com.hypergestures.x versionCode:7\n" "nocode:package:com.hypergestures\n"; do
  avsetup 43 "${pmout#*:}"; hg_service_main "$T/svc"
  if [ -f "$T/data/apk-stamp" ] && undated && [ "$(upd_notices)" = 0 ] \
     && [ "$(grep -c "versionCode could not be read from PackageManager; not compared with the module's (43)" "$T/data/log.txt")" = 1 ]; then
    ok "service_only_logs_an_unreadable_app_version (${pmout%%:*})"
  else bad "service_only_logs_an_unreadable_app_version (${pmout%%:*})"; fi
done
avsetup 43 'package:com.hypergestures versionCode:42\n'; mkdir -p "$T/data"; : > "$T/data/dormant"; roles "$MIUI"
hg_service_main "$T/svc"
if [ -f "$T/data/apk-stamp" ] && undated && ! has "show-versioncode"; then ok service_checks_the_app_version_only_on_active_boots
else bad service_checks_the_app_version_only_on_active_boots; fi

# --- crash watch: the dropbox directory -------------------------------------------------------
# A crash loop is 2 new entries (TAG@MS.txt / .txt.gz / .dat; .lost and header-less ones are
# skipped) of SystemUI, or 2 of ours, whose name timestamps are no more than 10 minutes apart.
cscan() { hg_crash_scan "${1:-within 10 minutes}"; }
setup; hg_crash_reset; dbe system_app_crash "$SUI"; advance 120; dbe system_app_crash "$SUI"; cscan; a=$?
if [ "$a" = 0 ] && [ "$HG_CRASH_WHY" = "com.android.systemui crashed 2 times within 10 minutes" ] && [ ! -f "$T/calls" ] \
   && [ "$_hg_cr_mode" = dir ]; then
  ok crash_scan_two_new_systemui_crashes_within_10_minutes_are_a_loop; else bad "crash_scan_two_new_systemui_crashes_within_10_minutes_are_a_loop ($a $HG_CRASH_WHY)"; fi
setup; hg_crash_reset; dbe system_app_crash "$SUI"; cscan; a=$?; advance 700; dbe system_app_crash "$SUI"; cscan; b=$?
advance 120; dbe system_app_crash "$SUI"; cscan; c=$?
if [ "$a$b$c" = 110 ] && [ "$HG_CRASH_WHY" = "com.android.systemui crashed 2 times within 10 minutes" ]; then
  ok crash_scan_needs_the_2_crashes_within_10_minutes_of_each_other; else bad "crash_scan_needs_the_2_crashes_within_10_minutes_of_each_other ($a$b$c $HG_CRASH_WHY)"; fi
setup; dbe system_app_crash "$SUI"; dbe system_app_crash "$SUI"; dbe data_app_crash "$HG_PKG"; hg_crash_reset; cscan; a=$?
dbe system_app_crash "$SUI"; cscan; b=$?
if [ "$a$b" = 11 ]; then ok crash_scan_never_counts_entries_from_before_the_reference; else bad "crash_scan_never_counts_entries_from_before_the_reference ($a$b)"; fi
setup; dbe system_app_native_crash "$SUI" "" gz; hg_crash_reset; cscan; a=$?; z1=$(grep -c '^zcat' "$T/calls" 2>/dev/null)
dbe system_app_native_crash "$SUI" "" gz; dbe system_app_crash "$SUI"; cscan; b=$?; cscan; c=$?
if [ "$a$b$c" = 101 ] && [ "${z1:-0}" = 0 ] && [ "$(grep -c '^zcat' "$T/calls")" = 1 ]; then
  ok crash_scan_reads_gz_entries_opening_only_new_ones; else bad "crash_scan_reads_gz_entries_opening_only_new_ones ($a$b$c z=${z1:-0})"; fi
setup; hg_crash_reset
dbe SYSTEM_TOMBSTONE "$SUI"; dbe system_app_anr "$SUI"; dbe system_app_wtf "$SUI"            # not crash tags
dbe data_app_crash com.hypergestures.other; dbe system_app_crash com.android.systemuix   # a prefix is not us
dbe system_app_crash "$SUI" "" lost; dbe system_app_crash "$SUI" "" nohdr                  # nothing to read
dbe system_app_crash "$SUI"; cscan; a=$?; dbe data_app_crash com.example; cscan; b=$?
dbe system_app_crash "$SUI"; cscan; c=$?
if [ "$a$b$c" = 110 ]; then ok crash_scan_ignores_other_tags_other_processes_and_entries_without_a_header
else bad "crash_scan_ignores_other_tags_other_processes_and_entries_without_a_header ($a$b$c)"; fi
setup; hg_crash_reset; dbe data_app_native_crash com.hypergestures:remote; dbe data_app_crash "$HG_PKG" "" crlf; cscan "since setup"; a=$?
if [ "$a" = 0 ] && [ "$HG_CRASH_WHY" = "com.hypergestures crashed 2 times since setup" ]; then
  ok crash_scan_counts_our_subprocess_and_crlf_headers_across_tags; else bad "crash_scan_counts_our_subprocess_and_crlf_headers_across_tags ($a $HG_CRASH_WHY)"; fi
# The settings page's own process (":settings") never counts; ":remote" and the main one still do.
setup; hg_crash_reset; dbe data_app_crash com.hypergestures:settings; dbe data_app_crash com.hypergestures:settings "" gz
dbe data_app_native_crash com.hypergestures:settings; cscan; a=$?
dbe data_app_crash com.hypergestures:settings; dbe data_app_crash com.hypergestures:remote; dbe data_app_crash "$HG_PKG"; cscan; b=$?
if [ "$a$b" = 10 ] && [ "$HG_CRASH_WHY" = "com.hypergestures crashed 2 times within 10 minutes" ]; then
  ok crash_scan_ignores_the_settings_process_and_still_counts_remote
else bad "crash_scan_ignores_the_settings_process_and_still_counts_remote ($a$b $HG_CRASH_WHY)"; fi
setup; hg_crash_reset; dbe system_app_crash "$SUI" "" dat; dbe system_app_crash "$SUI"; cscan; a=$?
if [ "$a" = 0 ]; then ok crash_scan_reads_dat_entries_too; else bad crash_scan_reads_dat_entries_too; fi
m=$(( T0 + 2000000 ))
setup; hg_crash_reset; dbe system_app_crash "$SUI" $(( m + 800000 )); dbe system_app_native_crash "$SUI" $(( m + 300000 )); cscan; a=$?
setup; hg_crash_reset; dbe system_app_crash "$SUI" $(( m + 800000 )); dbe system_app_native_crash "$SUI" $(( m + 100000 )); cscan; b=$?
if [ "$a$b" = 01 ]; then ok crash_scan_pairs_entries_by_their_timestamps_whatever_the_tag_order
else bad "crash_scan_pairs_entries_by_their_timestamps_whatever_the_tag_order ($a$b)"; fi
# No baseline, so a prune of old entries hides nothing (0.3.8 re-baselined on a lower count).
setup; dbe system_app_crash "$SUI"; dbe system_app_crash "$SUI"; dbe system_app_crash "$SUI"; hg_crash_reset
rm -f "$T"/dropbox/system_app_crash@*; dbe system_app_crash "$SUI"; cscan; a=$?; dbe system_app_crash "$SUI"; cscan; b=$?
if [ "$a$b" = 10 ]; then ok crash_scan_is_not_fooled_by_the_dropbox_pruning_old_entries
else bad "crash_scan_is_not_fooled_by_the_dropbox_pruning_old_entries ($a$b)"; fi
setup; hg_crash_reset; dbe system_app_crash "$SUI"; cscan; a=$?; advance 10800; dbe system_app_crash "$SUI"; cscan; b=$?
if [ "$a$b" = 11 ]; then ok crash_scan_ignores_crashes_on_both_sides_of_a_long_suspend; else bad "crash_scan_ignores_crashes_on_both_sides_of_a_long_suspend ($a$b)"; fi
setup; cscan; a=$?
if [ "$a" = 1 ] && [ "$_hg_cr_mode" = dir ]; then ok crash_scan_without_a_reference_sets_one_and_passes; else bad crash_scan_without_a_reference_sets_one_and_passes; fi

# --- crash watch: the dumpsys fallback, and the gesture monitor -----------------------------
# When the dropbox directory can't be listed (missing, or no entry at all), crashes are counted
# from `dumpsys dropbox --print` against a reference sample: "SYSUI OURS", the number of entries
# of SystemUI and of com.hypergestures over the crash tags.
box() { if [ -f "$T/$2" ]; then cp "$T/$2" "$T/dropbox.$1"; else cp "$FIX/$2" "$T/dropbox.$1"; fi; } # box TAG FILE: what `dumpsys dropbox --print TAG` prints
boxseq() { t="$1"; shift; printf '%s\n' "$@" > "$T/boxseq.$t"; rm -f "$T/boxcount.$t"; } # successive answers
cat "$FIX/dropbox_later_loop.txt" "$FIX/dropbox_later_loop.txt" > "$T/four.txt" # 4 SystemUI crashes
setup; box system_app_crash dropbox_sysui_crashes.txt; a=$(hg_crash_sample)
box data_app_crash dropbox_ours.txt; b=$(hg_crash_sample)
if [ "$a|$b" = "3 0|3 2" ] && [ ! -e "$T/data/log.txt" ]; then
  ok crash_sample_counts_entries_per_package_over_both_tags; else bad "crash_sample_counts_entries_per_package_over_both_tags ($a|$b)"; fi
setup; box system_app_crash dropbox_unrelated.txt; box data_app_crash dropbox_ours.txt; a=$(hg_crash_sample)
if [ "$a" = "0 2" ]; then ok crash_sample_matches_the_process_and_its_subprocesses_not_a_prefix
else bad "crash_sample_matches_the_process_and_its_subprocesses_not_a_prefix ($a)"; fi
setup; { printf 'Drop box contents: 3 entries\n\n'
  for p in com.hypergestures:settings com.hypergestures:remote com.hypergestures:settings; do
    printf '========================================\n2026-10-10 10:00:00 data_app_crash (text, 99 bytes)\nProcess: %s\nPID: 4242\n\n' "$p"
  done; } > "$T/settings_box.txt"
box data_app_crash settings_box.txt; a=$(hg_crash_sample)
if [ "$a" = "0 1" ]; then ok crash_sample_ignores_the_settings_process_and_still_counts_remote
else bad "crash_sample_ignores_the_settings_process_and_still_counts_remote ($a)"; fi
setup; box system_app_crash dropbox_unparsable.txt; a=$(hg_crash_sample); b=$(hg_crash_sample)
if [ "$a/$b" = "?/?" ] && [ "$(grep -c "dropbox --print system_app_crash unreadable or unparsable" "$T/data/log.txt")" = 1 ]; then
  ok crash_sample_of_an_unparsable_dropbox_is_unknown_and_logged_once; else bad "crash_sample_of_an_unparsable_dropbox_is_unknown_and_logged_once ($a)"; fi
setup; : > "$T/dropbox.data_app_crash"; a=$(hg_crash_sample)
if [ "$a" = "?" ]; then ok crash_sample_of_an_empty_dump_is_unknown; else bad "crash_sample_of_an_empty_dump_is_unknown ($a)"; fi
# A dump that fails or is cut short is unknown ("?"), never a lower count: `timeout` kills a slow
# dump after 10 s and a pipeline would report awk's exit status, so partial output used to parse.
setup; box system_app_crash dropbox_partial.txt; echo 124 > "$T/boxrc.system_app_crash"; a=$(hg_crash_sample); b=$(hg_crash_sample)
if [ "$a/$b" = "?/?" ] && [ "$(grep -c "dropbox --print system_app_crash unreadable or unparsable" "$T/data/log.txt")" = 1 ]; then
  ok crash_sample_of_a_dump_that_exits_nonzero_with_partial_output_is_unknown; else bad "crash_sample_of_a_dump_that_exits_nonzero_with_partial_output_is_unknown ($a/$b)"; fi
setup; box system_app_crash dropbox_timeout.txt; a=$(hg_crash_sample)
if [ "$a" = "?" ]; then ok crash_sample_of_a_dump_containing_dump_timeout_is_unknown; else bad "crash_sample_of_a_dump_containing_dump_timeout_is_unknown ($a)"; fi
setup; box data_app_crash dropbox_ours.txt; echo 1 > "$T/boxrc.data_app_native_crash"; a=$(hg_crash_sample)
if [ "$a" = "?" ]; then ok crash_sample_is_unknown_when_any_one_tag_fails; else bad "crash_sample_is_unknown_when_any_one_tag_fails ($a)"; fi
setup; rm -rf "$T/dropbox"; hg_crash_reset; m1=$_hg_cr_mode; box system_app_crash dropbox_later_loop.txt; cscan; a=$?
if [ "$m1" = dumpsys ] && [ "$a" = 0 ] && [ "$HG_CRASH_WHY" = "com.android.systemui crashed 2 times within 10 minutes" ] \
   && has "dumpsys dropbox --print system_app_crash" && [ "$(grep -c "can't be listed" "$T/data/log.txt")" = 1 ]; then
  ok crash_watch_falls_back_to_dumpsys_when_the_dropbox_cannot_be_listed; else bad "crash_watch_falls_back_to_dumpsys_when_the_dropbox_cannot_be_listed ($m1 $a)"; fi
setup; rm -f "$T"/dropbox/*; hg_crash_reset
if [ "$_hg_cr_mode" = dumpsys ] && grep -q "can't be listed" "$T/data/log.txt"; then ok crash_watch_treats_an_empty_dropbox_as_unreadable
else bad crash_watch_treats_an_empty_dropbox_as_unreadable; fi
# "?" never rolls back and never re-baselines over a known reference; a truncated first sample
# leaves an unknown reference, which a later full dump replaces without a rollback.
setup; rm -rf "$T/dropbox"; hg_crash_reset; box system_app_crash dropbox_partial.txt; echo 124 > "$T/boxrc.system_app_crash"; _hg_cr_ref="3 0"
cscan; a=$?
if [ "$a" = 1 ] && [ "$_hg_cr_ref" = "3 0" ]; then
  ok crash_fallback_on_a_failed_dump_neither_rolls_back_nor_re_baselines; else bad "crash_fallback_on_a_failed_dump_neither_rolls_back_nor_re_baselines ($a $_hg_cr_ref)"; fi
setup; rm -rf "$T/dropbox"; box system_app_crash dropbox_partial.txt; echo 124 > "$T/boxrc.system_app_crash"; hg_crash_reset; r0=$_hg_cr_ref
rm -f "$T/boxrc.system_app_crash"; box system_app_crash dropbox_sysui_crashes.txt; cscan; a=$?
if [ "$r0" = "?" ] && [ "$a" = 1 ] && [ "$_hg_cr_ref" = "3 0" ]; then
  ok crash_fallback_after_a_truncated_reference_re_baselines_without_rollback; else bad "crash_fallback_after_a_truncated_reference_re_baselines_without_rollback ($r0 $a $_hg_cr_ref)"; fi
setup; rm -rf "$T/dropbox"; hg_crash_reset # reference "0 0"
box system_app_crash dropbox_sysui_one.txt; cscan; a=$?; _hg_cr_age=600; cscan; b=$?; r1=$_hg_cr_ref # renewed after 10 minutes
box system_app_crash dropbox_later_loop.txt; cscan; c=$?; box system_app_crash four.txt; cscan; d=$?
if [ "$a$b$c$d" = 1110 ] && [ "$r1" = "1 0" ] && [ "$HG_CRASH_WHY" = "com.android.systemui crashed 3 times within 10 minutes" ]; then
  ok crash_fallback_renews_its_reference_every_10_minutes_of_watchdog_time; else bad "crash_fallback_renews_its_reference_every_10_minutes_of_watchdog_time ($a$b$c$d $r1)"; fi
setup; rm -rf "$T/dropbox"; box system_app_crash dropbox_sysui_crashes.txt; hg_crash_reset # reference "3 0"
box system_app_crash dropbox_later_loop.txt; cscan; a=$?; box system_app_crash four.txt; cscan; b=$?
if [ "$a/$b" = 1/0 ] && grep -q "crash count: the reference was unknown or above the dropbox count" "$T/data/log.txt"; then
  ok crash_fallback_re_baselines_after_a_dropbox_eviction; else bad "crash_fallback_re_baselines_after_a_dropbox_eviction ($a/$b)"; fi
# native crashes count like Java ones (same "Process:" header), a tag with no entries counts 0
setup; box system_app_native_crash dropbox_native_sysui.txt; a=$(hg_crash_sample)
box system_app_crash dropbox_sysui_crashes.txt; box data_app_native_crash dropbox_ours.txt; b=$(hg_crash_sample)
if [ "$a|$b" = "1 0|4 2" ]; then ok crash_sample_counts_native_crashes_of_systemui_and_ours; else bad "crash_sample_counts_native_crashes_of_systemui_and_ours ($a|$b)"; fi
r=""; for p in "2 0|0 0" "1 1|0 0" "1 3|1 1" "4 0|3 0" "?|0 0" "1 0|?" "1 0|" "2 0|3 0" "5 5|5 6"; do
  hg_crash_compare "${p%|*}" "${p#*|}" "x" > /dev/null; r="$r$?"; done
if [ "$r" = 010113333 ]; then ok crash_compare_rise_unknown_and_drop; else bad "crash_compare_rise_unknown_and_drop ($r)"; fi
setup; hg_monitor_registered; a=$?; cp "$FIX/input_monitor_missing.txt" "$T/input"; hg_monitor_registered; b=$?
cp "$FIX/input_monitor_ours.txt" "$T/input"; stub pidof 'echo 9999'; hg_monitor_registered; c=$?
stub pidof ''; hg_monitor_registered; d=$?; stub pidof 'echo 7450'; : > "$T/input"; hg_monitor_registered; e=$?
printf '      1: name=[Gesture Monitor] swipe-up, id=420, displayId=0, ownerUid=10315\n' > "$T/input"; hg_monitor_registered; f=$?
grep -v 'swipe-up' "$FIX/input_monitor_ours.txt" > "$T/input"; hg_monitor_registered; g=$?
if [ "$a/$b/$c/$d/$e/$f/$g" = 0/1/1/2/2/2/1 ]; then ok monitor_check_needs_swipe_up_owned_by_our_pid_and_fails_open_when_it_cant_tell
else bad "monitor_check_needs_swipe_up_owned_by_our_pid_and_fails_open_when_it_cant_tell ($a/$b/$c/$d/$e/$f/$g)"; fi

# --- health check and rollback ----------------------------------------------------------------
# A bound boot on the build the module was made for, gestural through live nav state, no watchdog,
# a 45 s window (3 checks; $2 = another window, e.g. the real 120 s). The dropbox is read at boot (1st answer), at setup (2nd) and at
# every check (3rd ...).
hsetup() { # $1 = more config lines, $2 = health window (default 45 s)
  setup; live_nav; mkmod; HG_HEALTH_WINDOW="${2:-45}"
  printf 'NAV_MODE=gestural\nDISABLE_MIUIHOME=1\nWATCHDOG=0\n%b' "${1:-}" > "$T/data/config"
  printf '[x] %s\n[ ] %s\n' "$THREEBUTTON" "$GESTURAL" > "$T/overlays"; echo 0 > "$T/fsg"
}
HOME_BACK="role add-role-holder android.app.role.HOME com.miui.home"
HEALTH_FAIL="HyperGestures failed its health check ("
HEALTH_TAIL=") and restored stock navigation. It is disabled; re-enable it in KernelSU after fixing. See $T/data/log.txt. Reboot to finish returning to stock."
hstatus() { cat "$T/data/health-status" 2>/dev/null; }
rolled_back() { # $1 = module dir: the full rollback, with exactly one notification
  [ -f "$1/disable" ] && has "$HOME_BACK" && has "pm enable com.miui.home" \
    && has "appops set com.miui.home RUN_ANY_IN_BACKGROUND default" && [ -f "$T/data/restored" ] \
    && [ "$(notified | grep -c .)" = 1 ] && hstatus | grep -q '^FAILED'
}
healthy() { [ ! -e "$T/svc/disable" ] && ! has "add-role-holder" && ! has "^notify" && hstatus | grep -q '^ok'; }
H8="15 15 15 15 15 15 15 15 "; H3="15 15 15 "
hsetup '' 120; hg_service_main "$T/svc"
if healthy && on "$GESTURAL" && [ "$(sleeps)" = "$H8" ] && ! has "dumpsys dropbox" \
   && has "dumpsys input" && has "pidof com.hypergestures" && [ "$(grep -c '^dumpsys activity' "$T/calls")" = 10 ] \
   && hstatus | grep -q 'gesture monitor registered' && [ ! -e "$T/data/crash-setup" ]; then
  ok health_healthy_boot_is_not_rolled_back; else bad "health_healthy_boot_is_not_rolled_back ($(hstatus); sleeps $(sleeps))"; fi
hsetup; seq_dumpsys sysui_connected.txt sysui_connected.txt sysui_disconnected.txt; hg_service_main "$T/svc"
if rolled_back "$T/svc" && on "$THREEBUTTON" && ! on "$GESTURAL" && [ "$(cat "$T/fsg")" = 0 ] && [ "$(sleeps)" = "15 15 15 " ] \
   && notified | grep -qF "${HEALTH_FAIL}provider not bound at 2 checks in a row$HEALTH_TAIL" && before "pm enable" "$HOME_BACK"; then
  ok health_provider_unbinding_in_the_window_rolls_back; else bad "health_provider_unbinding_in_the_window_rolls_back ($(hstatus))"; fi
hsetup; seq_dumpsys sysui_connected.txt sysui_connected.txt sysui_disconnected.txt sysui_connected.txt; hg_service_main "$T/svc"
if healthy && [ "$(sleeps)" = "$H3" ]; then ok health_one_unbound_check_is_let_through; else bad "health_one_unbound_check_is_let_through ($(hstatus))"; fi
hsetup; crash_at 1 "$SUI"; hg_service_main "$T/svc"
if rolled_back "$T/svc" && notified | grep -qF "${HEALTH_FAIL}com.android.systemui crashed 2 times since setup$HEALTH_TAIL" \
   && [ "$(sleeps)" = "15 " ]; then
  ok health_two_systemui_crashes_roll_back; else bad "health_two_systemui_crashes_roll_back ($(hstatus))"; fi
hsetup; dbe system_app_crash "$SUI"; dbe system_app_crash "$SUI"; dbe system_app_crash "$SUI"
hg_service_main "$T/svc" # 3 crashes before boot, none after
if healthy && ! has "dumpsys dropbox"; then
  ok health_crashes_from_before_boot_are_never_counted; else bad "health_crashes_from_before_boot_are_never_counted ($(hstatus))"; fi
hsetup; crash_at 1 "$SUI"
cat > "$T/date" <<EOF
#!/bin/sh
n=\$(cat "$T/wall" 2>/dev/null || echo 90000); echo \$((n - 86400)) > "$T/wall"; echo \$((n - 86400))
EOF
hg_service_main "$T/svc" # the wall clock jumps back a day at every read: nothing counts on it
if rolled_back "$T/svc" && notified | grep -qF "com.android.systemui crashed 2 times since setup"; then
  ok health_crash_counting_ignores_the_wall_clock; else bad "health_crash_counting_ignores_the_wall_clock ($(hstatus))"; fi
# The dropbox prunes old entries: nothing is counted against a baseline, so that hides nothing and
# fakes nothing.
prune_then() { # at the first sleep: delete the old SystemUI entries, then add $1 new ones
  PRUNE_N="$1"; tick() { advance "$1"; [ -f "$T/crashed" ] && return 0; : > "$T/crashed"; rm -f "$T"/dropbox/system_app_crash@*
    _i=0; while [ "$_i" -lt "$PRUNE_N" ]; do dbe system_app_crash "$SUI"; _i=$(( _i + 1 )); done; }; }
hsetup; dbe system_app_crash "$SUI"; dbe system_app_crash "$SUI"; dbe system_app_crash "$SUI"; prune_then 1
hg_service_main "$T/svc"; tick() { advance "$1"; crash_tick; }
if healthy && [ "$(sleeps)" = "$H3" ]; then
  ok health_a_dropbox_prune_and_one_new_crash_is_not_rolled_back; else bad "health_a_dropbox_prune_and_one_new_crash_is_not_rolled_back ($(hstatus))"; fi
hsetup; dbe system_app_crash "$SUI"; dbe system_app_crash "$SUI"; dbe system_app_crash "$SUI"; prune_then 2
hg_service_main "$T/svc"; tick() { advance "$1"; crash_tick; }
if rolled_back "$T/svc" && notified | grep -qF "com.android.systemui crashed 2 times since setup" && [ "$(sleeps)" = "15 " ]; then
  ok health_a_dropbox_prune_does_not_hide_2_new_crashes; else bad "health_a_dropbox_prune_does_not_hide_2_new_crashes ($(hstatus))"; fi
hsetup; crash_at 1 "$HG_PKG" data_app_crash; hg_service_main "$T/svc"
if rolled_back "$T/svc" && notified | grep -qF "${HEALTH_FAIL}com.hypergestures crashed 2 times since setup"; then
  ok health_our_crash_loop_rolls_back; else bad "health_our_crash_loop_rolls_back ($(hstatus))"; fi
hsetup; crash_at 1 "$SUI" system_app_native_crash 2 gz; hg_service_main "$T/svc" # 2 native SystemUI crashes
if rolled_back "$T/svc" && notified | grep -qF "${HEALTH_FAIL}com.android.systemui crashed 2 times since setup$HEALTH_TAIL"; then
  ok health_two_native_systemui_crashes_roll_back; else bad "health_two_native_systemui_crashes_roll_back ($(hstatus))"; fi
hsetup; crash_at 1 com.example data_app_crash; hg_service_main "$T/svc"
if healthy && [ "$(sleeps)" = "$H3" ]; then ok health_an_unrelated_app_crash_is_not_rolled_back; else bad "health_an_unrelated_app_crash_is_not_rolled_back ($(hstatus))"; fi
hsetup; crash_at 1 "$SUI" system_app_crash 1; hg_service_main "$T/svc"
if healthy; then ok health_one_systemui_crash_is_not_rolled_back; else bad "health_one_systemui_crash_is_not_rolled_back ($(hstatus))"; fi
hsetup; printf 'input_monitor_missing.txt\ninput_monitor_ours.txt\n' > "$T/inputseq"; hg_service_main "$T/svc"
if healthy && [ "$(sleeps)" = "${H3}15 " ] && grep -q "gesture monitor not registered.*checking once more in 15 s" "$T/data/log.txt"; then
  ok health_monitor_missing_then_present_on_the_retry_is_healthy; else bad "health_monitor_missing_then_present_on_the_retry_is_healthy ($(hstatus); $(sleeps))"; fi
hsetup; cp "$FIX/input_monitor_missing.txt" "$T/input"; hg_service_main "$T/svc"
if rolled_back "$T/svc" && [ "$(sleeps)" = "${H3}15 " ] \
   && notified | grep -qF "${HEALTH_FAIL}gesture monitor not registered (no [Gesture Monitor] swipe-up owned by com.hypergestures)$HEALTH_TAIL"; then
  ok health_monitor_missing_on_the_retry_too_rolls_back; else bad "health_monitor_missing_on_the_retry_too_rolls_back ($(hstatus))"; fi
hsetup; cp "$FIX/input_monitor_ours.txt" "$T/input"; stub pidof ''; hg_service_main "$T/svc"
if healthy && [ "$(sleeps)" = "$H3" ] && grep -q "can't tell whether the gesture monitor is ours" "$T/data/log.txt"; then
  ok health_monitor_check_passes_when_pidof_prints_nothing; else bad "health_monitor_check_passes_when_pidof_prints_nothing ($(hstatus))"; fi
hsetup 'NAV_MODE=threebutton\n'; cp "$FIX/input_monitor_missing.txt" "$T/input"; hg_service_main "$T/svc"
if healthy && ! has "dumpsys input" && hstatus | grep -q "no gesture monitor needed"; then
  ok health_monitor_not_checked_outside_gestural_mode; else bad "health_monitor_not_checked_outside_gestural_mode ($(hstatus))"; fi
# The dumpsys fallback (the dropbox directory can't be listed) in the health check.
hsetup; rm -rf "$T/dropbox"; box system_app_crash dropbox_unparsable.txt; hg_service_main "$T/svc"
if healthy && [ "$(grep -c "dropbox --print system_app_crash unreadable or unparsable" "$T/data/log.txt")" = 1 ]; then
  ok health_unparsable_dropbox_passes_that_check_and_is_logged; else bad "health_unparsable_dropbox_passes_that_check_and_is_logged ($(hstatus))"; fi
hsetup; rm -rf "$T/dropbox"; boxseq system_app_crash dropbox_empty.txt dropbox_empty.txt dropbox_later_loop.txt; hg_service_main "$T/svc"
if rolled_back "$T/svc" && notified | grep -qF "${HEALTH_FAIL}com.android.systemui crashed 2 times since setup$HEALTH_TAIL" \
   && [ "$(sleeps)" = "15 " ] && grep -q "can't be listed" "$T/data/log.txt"; then
  ok health_fallback_counts_dumpsys_when_the_dropbox_cannot_be_listed; else bad "health_fallback_counts_dumpsys_when_the_dropbox_cannot_be_listed ($(hstatus))"; fi
hsetup; echo "$OTA" > "$T/fingerprint"; hg_service_main "$T/svc"
if [ ! -e "$T/svc/disable" ] && has "am force-stop" && on "$GESTURAL" && hstatus | grep -q '^ok' && [ "$(sleeps)" = "$H3" ] \
   && notified | grep -qF "Firmware changed ($FP → $OTA). HyperGestures will run a health check and disable itself if anything is wrong." \
   && [ "$(notified | grep -c .)" = 1 ] && grep -q "firmware mismatch: built for $FP, this phone runs $OTA; setting up anyway" "$T/data/log.txt"; then
  ok health_firmware_mismatch_by_default_sets_up_with_the_health_check; else bad "health_firmware_mismatch_by_default_sets_up_with_the_health_check ($(hstatus))"; fi
# The soft check's notice comes once per firmware: the next boots on the same build only log it; a
# newer build notifies again.
OTA2='Xiaomi/nezha_eea/nezha:17/CP2A.270101.001/OS4.0.1.1.XPAEUXM:user/release-keys'
: > "$T/calls"; : > "$T/sleeps"; hg_service_main "$T/svc"; _again="$(notified | grep -c .)"
: > "$T/calls"; : > "$T/sleeps"; echo "$OTA2" > "$T/fingerprint"; hg_service_main "$T/svc"
if [ "$_again" = 0 ] && [ "$(notified | grep -c .)" = 1 ] && notified | grep -qF "Firmware changed ($FP → $OTA2)." \
   && [ "$(grep -c "this phone runs $OTA; setting up anyway" "$T/data/log.txt")" = 2 ] && hstatus | grep -q '^ok'; then
  ok health_firmware_notice_once_per_firmware; else bad "health_firmware_notice_once_per_firmware (again=$_again; $(hstatus))"; fi
# service.sh grants our app POST_NOTIFICATIONS once, before its first notice (that one goes through the app).
hsetup; echo "$OTA" > "$T/fingerprint"; am_says "Broadcast completed: result=1"; hg_service_main "$T/svc"
if [ "$(grep -c "^pm grant com.hypergestures android.permission.POST_NOTIFICATIONS$" "$T/calls")" = 1 ] \
   && before "^pm grant com.hypergestures" "^$AM_NOTIFY Firmware changed" && ! notified && hstatus | grep -q '^ok'; then
  ok service_grants_notifications_before_its_first_notice; else bad "service_grants_notifications_before_its_first_notice ($(hstatus))"; fi
hsetup; echo "$OTA" > "$T/fingerprint"; crash_at 1 "$SUI"; hg_service_main "$T/svc"
if [ -f "$T/svc/disable" ] && has "$HOME_BACK" && on "$THREEBUTTON" && [ "$(notified | grep -c .)" = 2 ] && notified | grep -qF "$HEALTH_FAIL"; then
  ok health_firmware_mismatch_that_breaks_things_rolls_back; else bad health_firmware_mismatch_that_breaks_things_rolls_back; fi
hsetup 'HEALTH_TEST=fail\n'; hg_service_main "$T/svc"
if rolled_back "$T/svc" && notified | grep -qF "${HEALTH_FAIL}forced by HEALTH_TEST$HEALTH_TAIL" && ! grep -q HEALTH_TEST "$T/data/config" \
   && grep -q '^NAV_MODE=gestural$' "$T/data/config" && [ ! -f "$T/sleeps" ]; then first=1; else first=0; fi
rm -f "$T/svc/disable" "$T/calls"; hg_service_main "$T/svc" # re-enabled: the next boot is checked for real
if [ "$first" = 1 ] && healthy && on "$GESTURAL"; then
  ok health_test_hook_fails_once_then_removes_itself; else bad "health_test_hook_fails_once_then_removes_itself (first=$first; $(hstatus))"; fi
# The failed APK stays mounted until reboot: an original gesture mode is restored, then moved to 3
# buttons for the rest of this boot; the recorded original stays, so the guard brings it back.
hsetup; printf '[ ] %s\n[x] %s\n' "$THREEBUTTON" "$GESTURAL" > "$T/overlays"; cp "$FIX/input_monitor_missing.txt" "$T/input"
hg_service_main "$T/svc"
if rolled_back "$T/svc" && on "$THREEBUTTON" && ! on "$GESTURAL" && grep -q "^NAVBAR=$GESTURAL\$" "$T/data/original-nav.txt" \
   && grep -q "rollback: 2/6 navigation for the rest of this boot: 3-button until reboot" "$T/data/log.txt" \
   && before "overlay enable-exclusive --category $GESTURAL" "overlay enable-exclusive --category $THREEBUTTON"; then first=1; else first=0; fi
HG_SYNC=1 HG_MODDIR="$T/svc" $SH "$HG_GUARD" # next boot: disabled; the guard puts gestural back
if [ "$first" = 1 ] && on "$GESTURAL" && ! on "$THREEBUTTON" && [ -f "$T/data/guard-restored" ]; then
  ok rollback_of_a_gesture_mode_uses_3_buttons_until_reboot; else bad "rollback_of_a_gesture_mode_uses_3_buttons_until_reboot (first=$first)"; fi

# The provider never binds, and SystemUI crash-loops meanwhile (e.g. the mounted APK breaks it on
# a new build): nothing was set up, but the module still rolls back and disables itself.
rm -rf "$T/rootm"; mkdir -p "$T/rootm/system/priv-app/HyperGestures"
setup; HG_ROOT="$T/rootm"; mkmod; printf 'NAV_MODE=gestural\nWATCHDOG=1\n' > "$T/data/config"; echo 0 > "$T/fsg"
stub dumpsys "cat \"$FIX/sysui_disconnected.txt\""; dbe system_app_crash "$SUI"; crash_at 1 "$SUI" system_app_crash 3
hg_service_main "$T/svc" # one crash before boot, 3 during the wait
if [ -f "$T/svc/disable" ] && has "$HOME_BACK" && has "pm enable com.miui.home" && ! has "force-stop" && [ "$(notified | grep -c .)" = 1 ] \
   && notified | grep -qF "${HEALTH_FAIL}com.android.systemui crashed 3 times since boot while the provider never bound)"; then
  ok service_unbound_with_a_systemui_crash_loop_rolls_back; else bad "service_unbound_with_a_systemui_crash_loop_rolls_back ($(hstatus))"; fi
# The same crash loop with nothing of ours mounted is not ours to roll back: logged, phone left alone.
rm -rf "$T/rootm/system/priv-app/HyperGestures"
setup; HG_ROOT="$T/rootm"; mkmod; printf 'NAV_MODE=gestural\nWATCHDOG=1\n' > "$T/data/config"; echo 0 > "$T/fsg"
stub dumpsys "cat \"$FIX/sysui_disconnected.txt\""; dbe system_app_crash "$SUI"; crash_at 1 "$SUI" system_app_crash 3
hg_service_main "$T/svc"
if [ ! -e "$T/svc/disable" ] && ! has "add-role-holder" && ! has "pm enable" && [ ! -e "$T/data/restored" ] \
   && ! notified | grep -q "health check" && hstatus | grep -q '^not run: provider not bound' \
   && grep -q "crash loop seen (com.android.systemui crashed 3 times since boot while the provider never bound) but .*/system/priv-app/HyperGestures is not mounted" "$T/data/log.txt"; then
  ok service_unbound_crash_loop_without_our_apk_mounted_is_not_rolled_back; else bad "service_unbound_crash_loop_without_our_apk_mounted_is_not_rolled_back ($(hstatus))"; fi
setup; mkmod; printf 'NAV_MODE=gestural\nWATCHDOG=1\n' > "$T/data/config"; echo 0 > "$T/fsg"
stub dumpsys "cat \"$FIX/sysui_disconnected.txt\""; dbe system_app_crash "$SUI"; dbe system_app_crash "$SUI"; dbe system_app_crash "$SUI"
hg_service_main "$T/svc" # 3 crashes, all from before boot
if [ ! -e "$T/svc/disable" ] && ! has "add-role-holder" && hstatus | grep -q '^not run: provider not bound'; then
  ok service_unbound_ignores_crashes_from_before_boot; else bad "service_unbound_ignores_crashes_from_before_boot ($(hstatus))"; fi

# Every rollback step runs even when an earlier one fails, and each is logged with its result.
setup; mkmod; stub pm 'exit 1'; stub role 'exit 1'; printf 'FSG=0\nNAVBAR=\n' > "$T/data/original-nav.txt"
hg_rollback "a test" "$T/svc"
if [ -f "$T/svc/disable" ] && [ -f "$T/data/restored" ] && has "overlay disable $GESTURAL" && has "$HOME_BACK" \
   && has "settings put global force_fsg_nav_bar 0" && [ "$(notified | grep -c .)" = 1 ] && notified | grep -q "Part of the restore FAILED" \
   && notified | grep -q "Reboot to finish returning to stock." \
   && grep -q "rollback: 1/6 MiuiHome and navigation restored: FAILED" "$T/data/log.txt" \
   && grep -q "rollback: 3/6 HOME back to com.miui.home: FAILED" "$T/data/log.txt" \
   && grep -q "rollback: 4/6 module disabled from the next boot .*: ok" "$T/data/log.txt" \
   && grep -q "rollback: 5/6 restored marker .*: ok" "$T/data/log.txt" && grep -q "rollback: 6/6 owner notified" "$T/data/log.txt"; then
  ok rollback_steps_continue_after_a_failure; else bad rollback_steps_continue_after_a_failure; fi
setup; mkmod; mkdir "$T/svc/disable"; hg_rollback "a test" "$T/svc"
if has "pm enable com.miui.home" && has "$HOME_BACK" && [ -f "$T/data/restored" ] && notified | grep -q "could NOT disable itself" \
   && grep -q "rollback: 4/6 .*: FAILED" "$T/data/log.txt"; then
  ok rollback_reports_a_failed_self_disable; else bad rollback_reports_a_failed_self_disable; fi
# A notification with an apostrophe reaches the shell-uid fallback intact (su really runs its -c).
setup; stub notify 'exit 1'; stub su '[ "$1" = 2000 ] && [ "$2" = -c ] && exec sh -c "$3"'
hg_notify "$(hg_bootloop_message 0) It's \$HOME \`x\`"
if [ "$(grep -c '^notify' "$T/calls")" = 2 ] \
   && grep '^notify' "$T/calls" | tail -n 1 | grep -qF "3 boots in a row did not finish starting up; restored stock and disabled itself. See $T/data/log.txt. It's \$HOME \`x\`"; then
  ok notify_shell_uid_fallback_keeps_apostrophes; else bad "notify_shell_uid_fallback_keeps_apostrophes ($(grep '^notify' "$T/calls" | tail -n 1))"; fi

# After the window the watchdog also watches crash rates: 2 crashes of SystemUI (or ours) no more
# than 10 minutes apart, by the dropbox's own timestamps, roll back.
wsetup; dbe system_app_crash "$SUI"; advance 60; dbe system_app_crash "$SUI"; wd
if [ "$HG_WD" = rollback ] && rolled_back "$T/mod" && ! has "^dumpsys" \
   && notified | grep -qF "${HEALTH_FAIL}com.android.systemui crashed 2 times within 10 minutes)"; then
  ok watchdog_rolls_back_on_2_crashes_within_10_minutes; else bad "watchdog_rolls_back_on_2_crashes_within_10_minutes ($HG_WD)"; fi
wsetup; dbe system_app_crash "$SUI"; wd; r1=$HG_WD; wtick 700; wd; r2=$HG_WD # one crash, then quiet
dbe system_app_crash "$SUI"; wtick 120; wd; r3=$HG_WD; wtick 120; wd; r4=$HG_WD # the 2nd, 820 s after the 1st
if [ "$r1$r2$r3$r4" = 0000 ] && [ ! -e "$T/mod/disable" ] && ! has "add-role-holder"; then
  ok watchdog_ignores_crashes_further_apart_than_10_minutes; else bad "watchdog_ignores_crashes_further_apart_than_10_minutes ($r1$r2$r3$r4)"; fi
dbe data_app_crash "$HG_PKG"; dbe data_app_crash "$HG_PKG"; wtick 120; wd # 2 of ours within 10 minutes
if [ "$HG_WD" = rollback ] && [ -f "$T/mod/disable" ] && notified | grep -qF "com.hypergestures crashed 2 times within 10 minutes"; then
  ok watchdog_catches_2_new_crashes_in_the_sliding_window; else bad "watchdog_catches_2_new_crashes_in_the_sliding_window ($HG_WD)"; fi
# Our process died and KernelSU reset its profile (a removed package update): the watchdog puts the
# profile back, restarts our package and does not count those crashes toward a rollback.
wsetup; kprof 10429 true; _hg_wd_opid=9999 # our pid of the last bound check, gone now
dbe data_app_crash "$HG_PKG"; dbe data_app_crash "$HG_PKG"; wd
if [ "$HG_WD" != rollback ] && [ ! -e "$T/mod/disable" ] && grep -q '"umount_modules": false' "$T/kset"    && has "force-stop com.hypergestures" && grep -q 'KernelSU profile of com.hypergestures had been reset' "$T/data/log.txt"; then
  ok watchdog_heals_a_reset_ksu_profile_before_counting_crashes; else bad "watchdog_heals_a_reset_ksu_profile_before_counting_crashes ($HG_WD)"; fi
# The profile is still right: a dead process is the crash watch's business, as before.
wsetup; kprof 10429 false; _hg_wd_opid=9999
dbe data_app_crash "$HG_PKG"; dbe data_app_crash "$HG_PKG"; wd
if [ "$HG_WD" = rollback ] && ! has "ksud profile set"; then
  ok watchdog_leaves_a_kept_ksu_profile_alone; else bad "watchdog_leaves_a_kept_ksu_profile_alone ($HG_WD)"; fi
# Our process is the one of the last bound check: no ksud call at all.
wsetup; kprof 10429 true; mkdir -p "$T/proc/7450"; printf 'com.hypergestures' > "$T/proc/7450/cmdline"; _hg_wd_opid=7450
hg_ksu_heal; r=$?
if [ "$r" = 1 ] && ! has "ksud profile"; then ok watchdog_ksu_heal_is_free_while_our_process_lives; else bad watchdog_ksu_heal_is_free_while_our_process_lives; fi
# At most 3 heals a boot: then it logs once and the crash watch acts (here: a rollback).
wsetup; r=""
for i in 1 2 3 4 5; do kprof 10429 true; _hg_wd_opid=9999; hg_ksu_heal; r="$r$?"; done
n=$(grep -c 'KernelSU profile of com.hypergestures had been reset' "$T/data/log.txt")
dbe data_app_crash "$HG_PKG"; dbe data_app_crash "$HG_PKG"; _hg_wd_opid=9999; wd
if [ "$r" = 00011 ] && [ "$n" = 3 ] && [ "$HG_WD" = rollback ]    && [ "$(grep -c 'restored 3 times this boot' "$T/data/log.txt")" = 1 ]; then
  ok watchdog_ksu_heal_stops_after_3_a_boot_and_leaves_crashes_to_the_crash_watch
else bad "watchdog_ksu_heal_stops_after_3_a_boot_and_leaves_crashes_to_the_crash_watch ($r $n $HG_WD)"; fi
wsetup; dbe system_app_crash "$SUI"; dbe system_app_crash "$SUI"; hg_crash_reset; wd # both were there at setup
if [ "$HG_WD" = 0 ] && [ ! -e "$T/mod/disable" ]; then ok watchdog_ignores_crashes_before_setup; else bad "watchdog_ignores_crashes_before_setup ($HG_WD)"; fi
wsetup; rm -rf "$T/dropbox"; hg_crash_reset; box system_app_crash four.txt; echo 124 > "$T/boxrc.system_app_crash"; wd # a cut-off dump
if [ "$HG_WD" = 0 ] && [ ! -e "$T/mod/disable" ] && [ "$_hg_cr_ref" = "0 0" ]; then
  ok watchdog_fallback_ignores_a_dump_that_failed_or_was_cut_short; else bad "watchdog_fallback_ignores_a_dump_that_failed_or_was_cut_short ($HG_WD)"; fi
wsetup; dbe system_app_crash "$SUI"; dbe system_app_crash "$SUI"; hg_watchdog_loop "$T/mod"
if [ ! -f "$T/sleeps" ] && [ -f "$T/mod/disable" ]; then ok watchdog_loop_ends_after_a_rollback; else bad watchdog_loop_ends_after_a_rollback; fi
hsetup 'WATCHDOG=1\n' 120 # end to end: healthy after the window, then SystemUI starts crashing
tick() { advance "$1"; read _u _r < "$T/uptime"; [ "${_u%%.*}" -ge 2711 ] && [ ! -f "$T/crashed" ] || return 0
  : > "$T/crashed"; dbe system_app_crash "$SUI"; dbe system_app_crash "$SUI"; }
hg_service_main "$T/svc"
tick() { advance "$1"; crash_tick; }
if rolled_back "$T/svc" && grep -q "health check passed" "$T/data/log.txt" && on "$THREEBUTTON" \
   && notified | grep -qF "com.android.systemui crashed 2 times within 10 minutes" && [ "$(wc -l < "$T/sleeps")" = 22 ] \
   && [ "$(grep -c '^dumpsys activity' "$T/calls")" = 11 ]; then
  ok watchdog_catches_a_crash_loop_that_starts_after_the_window; else bad "watchdog_catches_a_crash_loop_that_starts_after_the_window ($(hstatus); $(wc -l < "$T/sleeps") sleeps, $(grep -c '^dumpsys activity' "$T/calls") dumps)"; fi

# The deferred restore (guard, uninstall.sh) gives HOME back to MiuiHome too; a failure counts.
setup; printf 'FSG=1\nNAVBAR=\n' > "$T/data/original-nav.txt"; hg_restore_after_boot mark
if has "$HOME_BACK" && has "pm enable com.miui.home" && [ -f "$T/data/guard-restored" ]; then
  ok guard_restore_sets_home; else bad guard_restore_sets_home; fi
setup; stub role 'exit 1'; printf 'FSG=1\nNAVBAR=\n' > "$T/data/original-nav.txt"
if ! hg_restore_after_boot mark && [ "$(cat "$T/data/restore-attempts" 2>/dev/null)" = 1 ] && [ ! -f "$T/data/guard-restored" ]; then
  ok guard_restore_counts_a_failed_home_as_failed; else bad guard_restore_counts_a_failed_home_as_failed; fi
setup; hg_install_guard "$TPL"; mkdir -p "$T/mod"; touch "$T/mod/disable"; HG_SYNC=1 $SH "$HG_GUARD"
if has "$HOME_BACK" && [ -f "$T/data/guard-restored" ]; then ok guard_script_sets_home; else bad guard_script_sets_home; fi

# --- the app's own facts (hg_app_fact: hypergestures_provider_bound, _gesture_monitor) --------
# The app writes "<bootId> <pid> <uptimeMs>" when SystemUI binds it and when its swipe-up monitor
# is registered; the bind and monitor checks go by a valid value first, so a dump format that no
# longer matches can't roll the module back. Anything invalid: today's dump checks.
FB=hypergestures_provider_bound; FM=hypergestures_gesture_monitor
fact() { # fact KEY [VALUE]: the app's value (default: this boot's, our process 7450)
  mkdir -p "$T/global"; printf '%s\n' "${2-boot-1 7450 81234}" > "$T/global/$1"
}
sysdumps() { cat "$T/calls" 2>/dev/null | grep -c "^dumpsys activity"; }
setup; fact $FB; hg_app_fact $FB; r=$?
if [ "$r" = 0 ] && [ "$HG_FACT_PID" = 7450 ] && ! has "^dumpsys" \
   && has "^settings get global $FB$"; then
  ok app_fact_fresh_value_counts_without_a_dump
else bad "app_fact_fresh_value_counts_without_a_dump ($r $HG_FACT_PID)"; fi
# A valid value and a dump that matches nothing: bound. The dump is read once a process as a
# cross-check, and the disagreement logged once.
setup; stub dumpsys "cat \"$FIX/sysui_disconnected.txt\""; fact $FB
hg_provider_bound; a=$?; hg_provider_bound; b=$?
if [ "$a$b" = 00 ] && [ "$(sysdumps)" = 1 ] \
   && grep -q "bind check: the app's $FB (pid 7450, this boot) says SystemUI bound it; SystemUI's dump does not show it" \
     "$T/data/log.txt"; then
  ok provider_bound_by_the_apps_value_when_the_dump_matches_nothing
else bad "provider_bound_by_the_apps_value_when_the_dump_matches_nothing ($a$b $(sysdumps))"; fi
setup; fact $FB; hg_provider_bound; a=$?
if [ "$a" = 0 ] && grep -q "bind check: the app's $FB .*; SystemUI's dump agrees" "$T/data/log.txt"
then ok provider_bound_cross_check_logs_agreement
else bad provider_bound_cross_check_logs_agreement; fi
# Stale boot, a pid that is gone, another process's pid (SystemUI's), garbled or oversized
# values, unset: ignored, and the dump decides as before (here: not bound).
long="boot-1 7450 $(printf '%0250d' 1)"
r=""
for v in "boot-0 7450 1" "boot-1 9999 1" "boot-1 2311 1" "boot-1 07450 1" "boot-1 7450" \
         "boot-1 7450 1 x" "boot-1 abc 1" "boot-1 7450 1x" "boot-1  7450 1" " boot-1 7450 1" \
         "boot-1 7450 1 " "boot-1 74501234567 1" "null" "" "$long" "boot-1 7450 1
boot-1 7450 2" "*"; do
  setup; stub dumpsys "cat \"$FIX/sysui_disconnected.txt\""; fact $FB "$v"
  if hg_app_fact $FB; then r="${r}y"; else r="${r}n"; fi
  if hg_provider_bound; then r="${r}B"; elif [ "$(sysdumps)" = 1 ]; then r="${r}d"; else r="${r}?"; fi
done
setup; stub dumpsys "cat \"$FIX/sysui_disconnected.txt\""; rm -rf "$T/global" # never written
if hg_app_fact $FB; then r="${r}y"; else r="${r}n"; fi
hg_provider_bound && r="${r}B" || r="${r}d"
setup; fact $FB; rm -f "$T/bootid"; if hg_app_fact $FB; then r="${r}y"; else r="${r}n"; fi
if [ "$r" = "$(printf 'nd%.0s' $(seq 18))n" ] && [ "$HG_FACT_PID" = "" ]; then
  ok app_fact_ignores_stale_foreign_and_garbled_values
else bad "app_fact_ignores_stale_foreign_and_garbled_values ($r)"; fi
# The monitor: a valid value counts though `dumpsys input` lists no swipe-up monitor; without
# one, the dump's answer as before (0/1/2: the monitor test above).
setup; cp "$FIX/input_monitor_missing.txt" "$T/input"; fact $FM; hg_monitor_registered; a=$?
hg_monitor_registered; b=$?
fact $FM "boot-0 7450 1"; hg_monitor_registered; c=$?
if [ "$a/$b/$c" = 0/0/1 ] && [ "$(grep -c '^dumpsys input' "$T/calls")" = 2 ] \
   && grep -q "monitor check: the app's $FM (pid 7450, this boot) says .*; dumpsys input does not list it" \
     "$T/data/log.txt"; then
  ok monitor_registered_by_the_apps_value_when_the_dump_lists_none
else bad "monitor_registered_by_the_apps_value_when_the_dump_lists_none ($a/$b/$c)"; fi
# A boot whose dumps match nothing at all (a firmware that changed both formats) but whose app
# says bound and registered: healthy, no rollback.
hsetup; stub dumpsys "cat \"$FIX/sysui_disconnected.txt\""
cp "$FIX/input_monitor_missing.txt" "$T/input"; fact $FB; fact $FM; hg_service_main "$T/svc"
if healthy && hstatus | grep -q 'gesture monitor registered' && [ ! -e "$T/svc/disable" ]; then
  ok health_passes_on_the_apps_values_when_the_dumps_match_nothing
else bad "health_passes_on_the_apps_values_when_the_dumps_match_nothing ($(hstatus))"; fi
# The same boot with another boot's values: the dumps decide, as today (provider never bound).
hsetup; stub dumpsys "cat \"$FIX/sysui_disconnected.txt\""; fact $FB "boot-0 7450 1"
hg_service_main "$T/svc"
if hstatus | grep -q "^not run: provider not bound 60 s after boot" && [ ! -e "$T/svc/disable" ]
then ok health_with_stale_values_goes_by_the_dumps
else bad "health_with_stale_values_goes_by_the_dumps ($(hstatus))"; fi
# Unbinding in the window with the app's value gone stale (our process died): rolled back as today.
hsetup; seq_dumpsys sysui_connected.txt sysui_connected.txt sysui_disconnected.txt
fact $FB "boot-1 9999 1"; hg_service_main "$T/svc"
if rolled_back "$T/svc"; then ok health_with_a_dead_pid_value_rolls_back_as_before
else bad "health_with_a_dead_pid_value_rolls_back_as_before ($(hstatus))"; fi
# The watchdog: a valid value is a bound check, no miss, no 3-button fallback.
wsetup; stub dumpsys "cat \"$FIX/sysui_disconnected.txt\""; fact $FB; wd
if [ "$HG_WD" = 0 ] && [ "$_hg_wd_opid" = 7450 ]; then ok watchdog_bound_by_the_apps_value
else bad "watchdog_bound_by_the_apps_value ($HG_WD)"; fi
# The boot wait: the app's value ends it within a second, not at the next 5 s dump.
setup; stub dumpsys "cat \"$FIX/sysui_disconnected.txt\""
tick() { advance "$1"; [ "$(wc -l < "$T/sleeps")" = 2 ] && fact $FB; return 0; }
hg_wait_provider 60; r=$?; tick() { advance "$1"; crash_tick; }
if [ "$r" = 0 ] && [ "$(sleeps)" = "1 1 " ] && [ "$(sysdumps)" = 2 ]; then
  ok wait_provider_ends_at_the_apps_value
else bad "wait_provider_ends_at_the_apps_value ($r $(sleeps) $(sysdumps))"; fi
# action.sh shows both values' verdicts.
setup; mkmod; fact $FB; fakebin; PATH="$T/fakebin:$PATH" $SH "$T/svc/action.sh" < /dev/null \
  > "$T/out" 2>&1
if grep -q "^$FB: yes (pid 7450, this boot)$" "$T/out" \
   && grep -q "^$FM: no valid value for this boot (the checks go by the dumps)$" "$T/out"; then
  ok action_reports_the_apps_facts
else bad "action_reports_the_apps_facts ($(grep hypergestures_ "$T/out"))"; fi

# Hostile values (the app's to write): shell metacharacters are data, never run, and fail the
# checks.
r=""
for v in "\$(touch $T/pwned) 7450 1" "boot-1 \$(touch $T/pwned) 1" "\`touch $T/pwned\` 7450 1" \
         "boot-1 7450 1;touch $T/pwned" "boot-1 7450 1|touch $T/pwned" "boot-1 '7450' 1" \
         "boot-1 \"7450\" 1" "boot-1 7450 \$((1+1))" "boot-1 7450 1&&touch $T/pwned" \
         "boot-1 7450 1>$T/pwned" "boot-1 * 1" "boot-1 7450 \${x:=1}"; do
  setup; stub dumpsys "cat \"$FIX/sysui_disconnected.txt\""; fact $FB "$v"
  if hg_app_fact $FB; then r="${r}y"; else r="${r}n"; fi
  hg_provider_bound && r="${r}B"
  [ -e "$T/pwned" ] && r="${r}X"
done
if [ "$r" = "nnnnnnnnnnnn" ]; then ok app_fact_hostile_values_are_inert_and_rejected
else bad "app_fact_hostile_values_are_inert_and_rejected ($r)"; fi
# The owner's switch hypergestures_health_facts=0: valid values count for nothing, the dumps
# decide; read once a process, logged once.
setup; stub dumpsys "cat \"$FIX/sysui_disconnected.txt\""; fact $FB
fact hypergestures_health_facts 0
hg_app_fact $FB; a=$?; hg_provider_bound; b=$?; hg_app_fact $FB; c=$?
if [ "$a$b$c" = 111 ] && [ "$(grep -c "get global hypergestures_health_facts" "$T/calls")" = 1 ] \
   && [ "$(grep -c "app facts: hypergestures_health_facts=0" "$T/data/log.txt")" = 1 ]; then
  ok app_fact_off_switch_leaves_the_dumps_alone
else bad "app_fact_off_switch_leaves_the_dumps_alone ($a$b$c)"; fi
setup; fact $FB; fact hypergestures_health_facts 1; hg_app_fact $FB; a=$?
if [ "$a" = 0 ]; then ok app_fact_switch_on_unless_0; else bad app_fact_switch_on_unless_0; fi
# A settings provider that does not answer (every get times out after 10 s): the boot wait still
# ends by its deadline plus one timeout, and stops reading the value every second.
setup; stub dumpsys "cat \"$FIX/sysui_disconnected.txt\""
cat > "$T/settings" <<EOF
#!/bin/sh
echo "settings \$*" >> "$T/calls"
case "\$1 \$2 \$3" in "get global hypergestures_"*)
  echo "\$(( \$(cut -d. -f1 "$T/uptime") + 10 )).42 3000.17" > "$T/uptime"; exit 124 ;; esac
EOF
chmod +x "$T/settings"
( hg_wait_provider 60 ); r=$?; u=$(cut -d. -f1 "$T/uptime")
g=$(grep -c "^settings get global $FB" "$T/calls")
if [ "$r" = 1 ] && [ "$u" -le 1070 ] && [ "$g" -le 6 ]; then
  ok wait_provider_ends_by_its_deadline_with_a_wedged_settings_provider
else bad "wait_provider_ends_by_its_deadline_with_a_wedged_settings_provider ($r $u $g)"; fi
# The undo (the guard, uninstall.sh): none of the exchanged values stays; the owner's switches do.
for m in cleanup mark; do
  setup; printf 'FSG=1\nNAVBAR=\n' > "$T/data/original-nav.txt"
  for k in hypergestures_module_version hypergestures_module_update hypergestures_module_status \
           $FB $FM hypergestures_health_facts; do fact $k x; done
  hg_restore_after_boot $m
  if [ "$(ls "$T/global")" = hypergestures_health_facts ] \
     && has "^settings delete global hypergestures_module_status$"; then
    ok "restore_after_boot_${m}_clears_the_exchanged_values"
  else bad "restore_after_boot_${m}_clears_the_exchanged_values ($(ls "$T/global"))"; fi
done
setup; hg_install_guard "$TPL"; printf 'FSG=0\nNAVBAR=\n' > "$T/data/original-nav.txt"
fact hypergestures_module_version x; fact $FB x; HG_SYNC=1 $SH "$TPL/uninstall.sh"
if [ -z "$(ls "$T/global")" ] && [ ! -e "$T/data" ]; then
  ok uninstall_clears_the_exchanged_values
else bad "uninstall_clears_the_exchanged_values ($(ls "$T/global"))"; fi

# --- boot-loop protection ------------------------------------------------------------------
setup; mountfix; mkdir -p "$T/mod"
hg_post_fs_data_main "$T/mod"; hg_post_fs_data_main "$T/mod"; hg_post_fs_data_main "$T/mod"
if [ "$(cat "$T/data/boot-attempts")" = 3 ] && [ ! -e "$T/mod/disable" ] && [ ! -e "$T/mod/skip_mount" ]    && [ "$(mstatus)" = "metamodule + overlay ok" ]; then
  ok post_fs_data_counts_unfinished_boots; else bad "post_fs_data_counts_unfinished_boots ($(mstatus))"; fi
rm -f "$T/calls"; hg_post_fs_data_main "$T/mod" # the 4th: 3 boots in a row never reached boot_completed
if [ -f "$T/mod/disable" ] && [ -f "$T/mod/skip_mount" ] && [ -f "$T/data/bootloop" ] && [ ! -e "$T/data/boot-attempts" ] && nomount \
   && mstatus | grep -q '^skipped: boot-loop protection' && grep -q "3 boots in a row did not reach boot_completed" "$T/data/log.txt"; then
  ok post_fs_data_trips_after_3_unfinished_boots_and_keeps_skip_mount; else bad "post_fs_data_trips_after_3_unfinished_boots_and_keeps_skip_mount ($(mstatus))"; fi
setup; mountfix; selfcfg; echo "$FP" > "$T/mod/expected-fingerprint"; echo 3 > "$T/data/boot-attempts"; hg_post_fs_data_main "$T/mod"
if nomount && [ -f "$T/mod/disable" ] && [ -f "$T/mod/skip_mount" ]; then
  ok post_fs_data_trip_self_mounts_nothing; else bad post_fs_data_trip_self_mounts_nothing; fi
setup; mountfix; mkdir -p "$T/mod"; echo 3 > "$T/data/bootloop"; echo junk > "$T/data/boot-attempts"; hg_post_fs_data_main "$T/mod"
if [ ! -e "$T/data/bootloop" ] && [ "$(cat "$T/data/boot-attempts")" = 1 ]; then
  ok post_fs_data_drops_a_stale_boot_loop_marker; else bad post_fs_data_drops_a_stale_boot_loop_marker; fi
# The boot-loop rollback on a boot whose guard can't be installed leaves the restored gesture mode
# alone (nothing would bring it back from 3 buttons at the next boot).
setup; live_nav; mkmod; printf 'NAV_MODE=gestural\nWATCHDOG=1\n' > "$T/data/config"; : > "$T/notadir"
printf 'FSG=0\nNAVBAR=%s\n' "$GESTURAL" > "$T/data/original-nav.txt"; printf '[ ] %s\n[x] %s\n' "$THREEBUTTON" "$GESTURAL" > "$T/overlays"
echo 3 > "$T/data/bootloop"; HG_GUARD="$T/notadir/hypergestures-guard.sh"; hg_service_main "$T/svc" 2>/dev/null
if [ -f "$T/svc/disable" ] && has "$HOME_BACK" && on "$GESTURAL" && ! has "enable-exclusive --category $THREEBUTTON" \
   && grep -q "rollback: 2/6 navigation for the rest of this boot: left as restored (no undo guard" "$T/data/log.txt" \
   && [ "$(notified | grep -c .)" = 1 ] && notified | grep -qF "3 boots in a row did not finish starting up; restored stock and disabled itself"; then
  ok boot_loop_rollback_without_guard_keeps_the_restored_gesture_mode; else bad boot_loop_rollback_without_guard_keeps_the_restored_gesture_mode; fi
# Reaching boot_completed is what counts (not the health check): the count is reset first thing,
# even when setup is then aborted or the health check fails.
setup; mkmod; printf 'NAV_MODE=gestural\nWATCHDOG=0\n' > "$T/data/config"; : > "$T/notadir"
HG_GUARD="$T/notadir/hypergestures-guard.sh"; echo 2 > "$T/data/boot-attempts"; hg_service_main "$T/svc" 2>/dev/null
if [ ! -e "$T/data/boot-attempts" ] && nochange && grep -q "boot finished (boot_completed reached)" "$T/data/log.txt"; then
  ok service_resets_the_boot_count_at_boot_completed; else bad service_resets_the_boot_count_at_boot_completed; fi
setup; mkmod; printf 'NAV_MODE=gestural\nWATCHDOG=1\n' > "$T/data/config"; echo 0 > "$T/fsg"
stub dumpsys "cat \"$FIX/sysui_disconnected.txt\""; echo 2 > "$T/data/boot-attempts"; hg_service_main "$T/svc"
if [ ! -e "$T/data/boot-attempts" ] && hstatus | grep -q '^not run: provider not bound'; then
  ok service_not_set_up_counts_as_a_finished_boot; else bad service_not_set_up_counts_as_a_finished_boot; fi
hsetup; seq_dumpsys sysui_connected.txt sysui_disconnected.txt; echo 1 > "$T/data/boot-attempts"; hg_service_main "$T/svc"
if [ ! -e "$T/data/boot-attempts" ] && [ -f "$T/svc/disable" ]; then
  ok service_rollback_resets_the_boot_count; else bad service_rollback_resets_the_boot_count; fi
# The tripping boot reaches boot_completed: the module counts as disabled, so the guard restores,
# sets HOME and tells the owner why, once; skip_mount goes back to what MOUNT wants.
BOOTLOOP_MSG="HyperGestures stopped: 3 boots in a row did not finish starting up; restored stock and disabled itself"
setup; live_nav; hg_install_guard "$TPL"; mkdir -p "$T/mod"
printf 'FSG=0\nNAVBAR=%s\n' "$THREEBUTTON" > "$T/data/original-nav.txt"; printf '[ ] %s\n[x] %s\n' "$THREEBUTTON" "$GESTURAL" > "$T/overlays"
echo 3 > "$T/data/boot-attempts"; : > "$T/data/guard-restored" # restored after an earlier disable
hg_post_fs_data_main "$T/mod"; HG_SYNC=1 $SH "$HG_GUARD"
if has "pm enable com.miui.home" && has "$HOME_BACK" && on "$THREEBUTTON" && ! on "$GESTURAL" && [ -f "$T/data/guard-restored" ] \
   && [ ! -e "$T/data/bootloop" ] && [ "$(notified | grep -c .)" = 1 ] && notified | grep -qF "$BOOTLOOP_MSG" \
   && [ -f "$T/mod/disable" ] && [ ! -e "$T/mod/skip_mount" ] && hstatus | grep -q '^FAILED: 3 boots in a row'; then
  ok guard_after_boot_loop_trip_restores_sets_home_and_notifies; else bad "guard_after_boot_loop_trip_restores_sets_home_and_notifies ($(hstatus))"; fi
rm -f "$T/calls"; HG_SYNC=1 $SH "$HG_GUARD"
if [ ! -f "$T/calls" ]; then ok guard_after_boot_loop_trip_does_not_repeat; else bad guard_after_boot_loop_trip_does_not_repeat; fi
# If KernelSU still runs service.sh on the tripping boot, it rolls back instead of setting up, and
# the guard running the same boot does not notify a second time.
setup; live_nav; mkmod; printf 'NAV_MODE=gestural\nWATCHDOG=1\n' > "$T/data/config"
printf 'FSG=0\nNAVBAR=%s\n' "$THREEBUTTON" > "$T/data/original-nav.txt"; printf '[ ] %s\n[x] %s\n' "$THREEBUTTON" "$GESTURAL" > "$T/overlays"
echo 3 > "$T/data/boot-attempts"; hg_post_fs_data_main "$T/svc"; hg_service_main "$T/svc"
HG_SYNC=1 HG_MODDIR="$T/svc" $SH "$HG_GUARD"
if ! has "force-stop" && ! has "enable-exclusive --category $GESTURAL" && has "$HOME_BACK" && on "$THREEBUTTON" && [ -f "$T/svc/disable" ] \
   && [ "$(notified | grep -c .)" = 1 ] && notified | grep -qF "$BOOTLOOP_MSG" && [ ! -e "$T/data/bootloop" ] && [ ! -f "$T/sleeps" ]; then
  ok service_after_boot_loop_trip_rolls_back_and_notifies_once; else bad "service_after_boot_loop_trip_rolls_back_and_notifies_once ($(notified | grep -c .) notifications)"; fi

# --- health status: never the previous boot's result -----------------------------------------
setup; mkmod; printf 'NAV_MODE=gestural\nWATCHDOG=0\n' > "$T/data/config"; rm -f "$T/hs-seen"
echo "ok: provider bound, no crash loop, gesture monitor registered (2026-09-29 14:32:00)" > "$T/data/health-status"
stub dumpsys "cat \"$T/data/health-status\" >> \"$T/hs-seen\"; cat \"$FIX/sysui_disconnected.txt\""; hg_service_main "$T/svc"
if grep -q "^pending: waiting for the provider (this boot's setup has not finished) (" "$T/hs-seen" && ! grep -q '^ok' "$T/hs-seen" \
   && hstatus | grep -q '^not run: provider not bound'; then
  ok health_status_is_pending_while_this_boot_waits_for_the_provider; else bad "health_status_is_pending_while_this_boot_waits_for_the_provider ($(sort -u "$T/hs-seen" 2>/dev/null))"; fi
setup; mkmod; printf 'NAV_MODE=gestural\nWATCHDOG=0\n' > "$T/data/config"; : > "$T/notadir"
echo "ok: provider bound, no crash loop, gesture monitor registered (2026-09-29 14:32:00)" > "$T/data/health-status"
HG_GUARD="$T/notadir/hypergestures-guard.sh"; hg_service_main "$T/svc" 2>/dev/null
if hstatus | grep -q '^not run: the undo guard could not be installed, nothing changed ('; then
  ok health_status_records_an_aborted_setup; else bad "health_status_records_an_aborted_setup ($(hstatus))"; fi

# --- customize.sh: the mount decision at install time -----------------------------------------
# KernelSU's installer sources customize.sh with $MODPATH = the new module dir (an update lands in
# modules_update/ without the old skip_mount); it must not exit and prints through ui_print. It
# sources $MODPATH/common/functions.sh and makes post-fs-data's mount decision (hg_mount_resolve).
cz() { # $1 = blocked (a directory where skip_mount goes) or no-functions (a module dir without it)
  rm -rf "$T/upd"; mkdir -p "$T/upd/common"; [ "${1:-}" = no-functions ] || cp "$TPL/common/functions.sh" "$T/upd/common/"
  [ "${1:-}" = blocked ] && mkdir "$T/upd/skip_mount"
  MODPATH="$T/upd" $SH -c 'ui_print() { echo "ui: $1"; }; . "$1"; echo "after: done"' sh "$TPL/customize.sh" > "$T/out" 2>&1; }
setup; printf 'NAV_MODE=gestural\nMOUNT=self\r\n' > "$T/data/config"; echo stamp > "$T/data/apk-stamp"; cz
if [ -f "$T/upd/skip_mount" ] && grep -q "^ui: - mount: self (MOUNT=self); skip_mount set" "$T/out" && grep -q "^after: done$" "$T/out" \
   && [ ! -e "$T/data/apk-stamp" ]; then
  ok customize_sets_skip_mount_for_mount_self_and_drops_the_apk_stamp; else bad "customize_sets_skip_mount_for_mount_self_and_drops_the_apk_stamp ($(cat "$T/out"))"; fi
setup; printf 'MOUNT=self\nMOUNT=metamodule\n' > "$T/data/config"; cz
if [ ! -e "$T/upd/skip_mount" ] && grep -q "^ui: - mount: metamodule → mixed (hybrid_mount); the metamodule mounts system/" "$T/out" \
   && grep -q "^after: done$" "$T/out"; then
  ok customize_leaves_skip_mount_off_for_metamodule; else bad "customize_leaves_skip_mount_off_for_metamodule ($(cat "$T/out"))"; fi
setup; rm -f "$T/data/config"; cz
if [ ! -e "$T/upd/skip_mount" ] && grep -q "^ui: - mount: auto → mixed (hybrid_mount); " "$T/out"; then
  ok customize_auto_with_a_metamodule_leaves_skip_mount_off; else bad "customize_auto_with_a_metamodule_leaves_skip_mount_off ($(cat "$T/out"))"; fi
setup; rm -f "$T/data/config"; rm -rf "$T/modules/hybrid_mount"; cz
if [ -f "$T/upd/skip_mount" ] && grep -q "^ui: - mount: auto → self (no active metamodule); skip_mount set" "$T/out"; then
  ok customize_auto_without_a_metamodule_sets_skip_mount; else bad "customize_auto_without_a_metamodule_sets_skip_mount ($(cat "$T/out"))"; fi
setup; printf 'MOUNT=self\n' > "$T/data/config"; cz blocked
if grep -q "^ui: ! mount: self (MOUNT=self), but .*skip_mount could not be created" "$T/out" && grep -q "^after: done$" "$T/out"; then
  ok customize_reports_an_unwritable_skip_mount_and_does_not_exit; else bad "customize_reports_an_unwritable_skip_mount_and_does_not_exit ($(cat "$T/out"))"; fi
setup; printf 'MOUNT=self\n' > "$T/data/config"; cz no-functions
if [ ! -e "$T/upd/skip_mount" ] && grep -q "^ui: ! .*common/functions.sh is missing" "$T/out" && grep -q "^after: done$" "$T/out"; then
  ok customize_without_functions_sh_leaves_skip_mount_to_post_fs_data; else bad "customize_without_functions_sh_leaves_skip_mount_to_post_fs_data ($(cat "$T/out"))"; fi
# Every extracted file, however deep, is dated to the install time: the zip's entries all carry
# the build's fixed date, and a metamodule syncing by size and mtime would keep an old APK of the
# same size. Symlinks are left alone (touch would date their target), modes are kept.
setup; rm -rf "$T/upd"; mkdir -p "$T/upd/common" "$T/upd/system/priv-app/HyperGestures" "$T/upd/overlay" "$T/outside" \
  "$T/upd/system/priv-app/HyperGesturesNotif"
echo 2 > "$T/data/stale-boots" # a reinstall starts the stale-boot count afresh
cp "$TPL/common/functions.sh" "$T/upd/common/"; echo apk > "$T/upd/system/priv-app/HyperGestures/HyperGestures.apk"
echo hooks > "$T/upd/system/priv-app/HyperGesturesNotif/HyperGesturesNotif.apk"
echo ovl > "$T/upd/overlay/HyperGesturesOverlay.apk"; printf 'id=hypergestures\nversionCode=43\n' > "$T/upd/module.prop"
chmod 0644 "$T/upd/system/priv-app/HyperGestures/HyperGestures.apk"; echo x > "$T/outside/target"
ln -s "$T/outside/target" "$T/upd/link" 2>/dev/null
find "$T/upd" "$T/outside" -exec touch -h -d "@$OLDDATE" {} + 2>/dev/null; touch -d "@$(( OLDDATE + 1 ))" "$T/ref"
MODPATH="$T/upd" $SH -c 'ui_print() { echo "ui: $1"; }; . "$1"; echo "after: done"' sh "$TPL/customize.sh" > "$T/out" 2>&1
m=$(ls -l "$T/upd/system/priv-app/HyperGestures/HyperGestures.apk" | cut -c1-10)
if [ -z "$(find "$T/upd" ! -type l ! -newer "$T/ref")" ] && [ -z "$(find "$T/outside" -newer "$T/ref")" ] \
   && [ "$m" = "-rw-r--r--" ] && [ ! -e "$T/data/stale-boots" ] && grep -q "^ui: - files dated to the install time" "$T/out" && grep -q "^after: done$" "$T/out"; then
  ok customize_dates_every_nested_file_to_the_install_time
else bad "customize_dates_every_nested_file_to_the_install_time ($m $(find "$T/upd" ! -newer "$T/ref") $(cat "$T/out"))"; fi
# The install notes the new module's version as hypergestures_module_update (the phone runs
# while KernelSU installs), whatever version the app is; the running module's value stays until
# the boot.
czprep() { # customize.sh's MODPATH $T/upd with a module.prop naming 9.9-test (99)
  setup; rm -rf "$T/upd"; mkdir -p "$T/upd/common"; cp "$TPL/common/functions.sh" "$T/upd/common/"
  pvprop "$T/upd"
}
czrun() { # $1 = sh options (-ec: an installer that sources it under set -e)
  MODPATH="$T/upd" $SH "${1:--c}" 'ui_print() { echo "ui: $1"; }; . "$1"; echo "after: done"' \
    sh "$TPL/customize.sh" > "$T/out" 2>&1
}
czprep; mkdir -p "$T/global"; echo "0.1-old (1) boot=boot-1" > "$T/global/$PVK"; czrun
if [ "$(gget $PVUK)" = "$PVUWANT" ] && [ "$(gget $PVK)" = "0.1-old (1) boot=boot-1" ] \
   && grep -q "^after: done" "$T/out" \
   && grep -q "^ui: - the app's About page names 9.9-test (99) as installed until the reboot" \
     "$T/out"; then
  ok customize_notes_the_installed_version_as_an_update
else bad "customize_notes_the_installed_version_as_an_update ($(cat "$T/out"))"; fi
# A failed write (no settings provider) must not stop an installer that runs under set -e.
czprep; : > "$T/global.fail"; czrun -ec
if grep -q "^after: done$" "$T/out" && [ -z "$(gget $PVUK)" ] \
   && grep -q "^ui: ! the app's About page can't name this update" "$T/out"; then
  ok customize_under_set_e_survives_a_failed_version_setting
else bad "customize_under_set_e_survives_a_failed_version_setting ($(cat "$T/out"))"; fi

# --- this boot's state for the app (hg_publish_status: hypergestures_module_status) ----------
# service.sh (and the guard) write hypergestures_module_status at each state change: key=value
# pairs on one line, separated by ';': this boot's id, the state the settings page decides by,
# and the facts action.sh shows.
PSK=hypergestures_module_status
pstatus() { gget $PSK; }
pkey() { pstatus | tr ';' '\n' | sed -n "s/^$1=//p"; }
pwrites() { pvputs $PSK; }
stsetup() { # stsetup [CONFIG LINES]: an active boot in self mode (no metamodule to check)
  pvsetup; printf 'NAV_MODE=gestural\nWATCHDOG=0\n%b' "${1:-}" > "$T/data/config"
  printf 'self self - -\nauto → self (no active metamodule)\n' > "$T/data/mount-mode"
  echo "ok" > "$T/data/mount-status"
}
stsetup; hg_service_main "$T/svc"
if [ "$(pkey bootId)" = boot-1 ] && [ "$(pkey state)" = active ] && [ "$(pkey boot)" = active ] \
   && pkey health | grep -q "^ok: provider bound, no crash loop" && [ -z "$(pkey reason)" ] \
   && [ "$(pkey mountMode)" = "auto → self (no active metamodule)" ] && [ "$(pkey mount)" = ok ] \
   && ! pstatus | tr ';' '\n' \
     | grep -qE "^(metamoduleMissed|staleApk|powerPending|restoreGaveUp)=" \
   && [ "$(pwrites)" = 3 ] && [ "$(pstatus | wc -l)" = 1 ]; then
  ok status_active_boot_is_published_at_each_state_change
else bad "status_active_boot_is_published_at_each_state_change ($(pwrites) $(pstatus))"; fi
# The health the app shows is the text action.sh shows, without its time.
fakebin; PATH="$T/fakebin:$PATH" $SH "$T/svc/action.sh" < /dev/null > "$T/out" 2>&1
h="$(sed -n 's/^health: //p' "$T/out")"
if [ -n "$(pkey health)" ] && [ "${h% (*}" = "$(pkey health)" ]; then
  ok status_health_is_the_text_action_sh_shows
else bad "status_health_is_the_text_action_sh_shows ($h | $(pkey health))"; fi
dormant; pvprop "$T/svc"; hg_service_main "$T/svc"
if [ "$(pkey state)" = dormant ] && [ "$(pkey boot)" = dormant ] \
   && [ "$(pkey reason)" = "dormant: HOME is com.miui.home (choose another launcher and reboot to use the module)" ] \
   && [ -n "$(pkey mountMode)" ] && [ "$(pwrites)" = 2 ]; then
  ok status_dormant_boot_says_dormant_with_the_reason
else bad "status_dormant_boot_says_dormant_with_the_reason ($(pwrites) $(pstatus))"; fi
stsetup; stub dumpsys "cat \"$FIX/sysui_disconnected.txt\""; hg_service_main "$T/svc"
if [ "$(pkey state)" = not-set-up ] && [ "$(pkey boot)" = active ] \
   && [ "$(pkey reason)" = "provider not bound 60 s after boot, nothing set up" ]; then
  ok status_provider_never_bound_is_not_set_up
else bad "status_provider_never_bound_is_not_set_up ($(pstatus))"; fi
stsetup 'HEALTH_TEST=fail\n'; hg_service_main "$T/svc"
if [ "$(pkey state)" = rolled-back ] \
   && pkey reason | grep -q "^forced by HEALTH_TEST, rolled back (" \
   && pkey health | grep -q "^FAILED: forced by HEALTH_TEST"; then
  ok status_rollback_is_rolled_back_with_the_reason
else bad "status_rollback_is_rolled_back_with_the_reason ($(pstatus))"; fi
stsetup 'STRICT_FIRMWARE=1\n'; echo "$OTA" > "$T/fingerprint"; hg_service_main "$T/svc"
if [ "$(pkey state)" = refused ] && [ "$(pkey reason)" = "firmware refused (STRICT_FIRMWARE=1)" ]
then ok status_firmware_refusal_is_refused
else bad "status_firmware_refusal_is_refused ($(pstatus))"; fi
# The facts that apply, each as its own pair.
pvsetup; echo "hybrid_mount 170" > "$T/data/metamodule-missed"; : > "$T/data/power-pending"
: > "$T/data/restore-gave-up"; HG_STALE_APK="app 42, module 43"; HG_STATE=active
HG_HEALTH="ok: x"; hg_publish_status
if [ "$(pkey metamoduleMissed)" = "hybrid_mount 170" ] \
   && [ "$(pkey staleApk)" = "app 42, module 43" ] && [ "$(pkey powerPending)" = 1 ] \
   && [ "$(pkey restoreGaveUp)" = 1 ] && [ "$(pkey health)" = "ok: x" ] \
   && [ "$(pkey mountMode)" = "" ] && pstatus | tr ';' '\n' | grep -q "^mount=$"; then
  ok status_lists_the_facts_that_apply
else bad "status_lists_the_facts_that_apply ($(pstatus))"; fi
# A value never breaks the list: ';' becomes ',', a newline or CR a space.
pvsetup; HG_STATE=rolled-back; HG_HEALTH="FAILED: a;b
c$(printf '\r')d"; hg_publish_status
if [ "$(pkey reason)" = "a,b c d" ] && [ "$(pkey health)" = "FAILED: a,b c d" ] \
   && [ "$(pstatus | wc -l)" = 1 ]; then
  ok status_flattens_its_values
else bad "status_flattens_its_values ($(pstatus))"; fi
# The update the system did not take, from service.sh's version check.
avsetup 43 'package:com.hypergestures versionCode:42\n'; pvprop "$T/svc"; hg_service_main "$T/svc"
if [ "$(pkey staleApk)" = "app 42, module 99" ] && [ "$(pkey state)" = active ]; then
  ok status_names_a_stale_apk
else bad "status_names_a_stale_apk ($(pstatus))"; fi
# power-pending: published on a change only, and never from a process without a state
# (restore.sh): it can't tell the boot's state.
pvsetup; hg_power_mark 1
if [ -f "$T/data/power-pending" ] && [ -z "$(pstatus)" ] && ! has "^settings put global $PSK"
then ok status_never_written_without_a_state
else bad status_never_written_without_a_state; fi
HG_STATE=active; hg_power_mark 1; a=$(pwrites); hg_power_mark 0; b=$(pwrites); hg_power_mark 0
if [ "$a$b$(pwrites)" = 011 ] && [ -z "$(pkey powerPending)" ] && [ "$(pkey state)" = active ]
then ok status_power_mark_publishes_changes_only
else bad "status_power_mark_publishes_changes_only ($a $b $(pwrites))"; fi
# A failed write is logged once a boot (the page then goes by HOME).
pvsetup; : > "$T/global.fail"; HG_STATE=active; hg_publish_status; hg_publish_status
if [ -z "$(pstatus)" ] \
   && [ "$(grep -c "status setting: $PSK not written" "$T/data/log.txt")" = 1 ]; then
  ok status_failed_write_logs_once
else bad status_failed_write_logs_once; fi
# post-fs-data never publishes (no settings provider yet): its choices wait for service.sh.
pvsetup; mountfix; hg_post_fs_data_main "$T/mod"
if [ -f "$T/data/mount-mode" ] && ! has "^settings put global hypergestures_"; then
  ok status_post_fs_data_publishes_nothing
else bad status_post_fs_data_publishes_nothing; fi

# --- skip_mount failures are loud (setconf) --------------------------------------------------
setup; mkmod; rm -f "$T/data/config"; mkdir "$T/svc/skip_mount" # in the way: can't be removed
if ! hg_setconf "$T/svc" MOUNT metamodule > "$T/out" 2>&1 && grep -q "skip_mount could not be removed" "$T/out" \
   && grep -q "skip_mount: could not remove" "$T/data/log.txt"; then
  ok setconf_mount_fails_loudly_when_skip_mount_stays; else bad "setconf_mount_fails_loudly_when_skip_mount_stays ($(cat "$T/out"))"; fi
setup; mkmod; rm -f "$T/data/config"; mkdir "$T/svc/skip_mount" # a directory: no flag file
if ! hg_setconf "$T/svc" MOUNT self > "$T/out" 2>&1 && grep -q "skip_mount could not be created" "$T/out" \
   && grep -q "skip_mount: could not create" "$T/data/log.txt"; then
  ok setconf_mount_fails_loudly_when_skip_mount_cannot_be_written; else bad "setconf_mount_fails_loudly_when_skip_mount_cannot_be_written ($(cat "$T/out"))"; fi

# --- setconf: STRICT_FIRMWARE and HEALTH_TEST -------------------------------------------------
setup; mkmod; rm -f "$T/data/config"
if hg_setconf "$T/svc" STRICT_FIRMWARE 1 > "$T/out" 2>&1 && [ "$(cfg STRICT_FIRMWARE)" = 1 ] \
   && hg_setconf "$T/svc" HEALTH_TEST fail >> "$T/out" 2>&1 && [ "$(cfg HEALTH_TEST)" = fail ] && grep -q "fails once" "$T/out" \
   && hg_setconf "$T/svc" HEALTH_TEST off >> "$T/out" 2>&1 && [ "$(cfg HEALTH_TEST)" = off ]; then
  ok setconf_accepts_strict_firmware_and_health_test; else bad "setconf_accepts_strict_firmware_and_health_test ($(cat "$T/out"))"; fi
r=0
hg_setconf "$T/svc" STRICT_FIRMWARE yes > "$T/out" 2>&1 && r=1
hg_setconf "$T/svc" STRICT_FIRMWARE 2 >> "$T/out" 2>&1 && r=1
hg_setconf "$T/svc" HEALTH_TEST on >> "$T/out" 2>&1 && r=1
if [ "$r" = 0 ] && [ "$(cfg STRICT_FIRMWARE)" = 1 ] && [ "$(cfg HEALTH_TEST)" = off ] && grep -q "STRICT_FIRMWARE must be 0 or 1" "$T/out" \
   && grep -q "HEALTH_TEST must be fail or off" "$T/out"; then
  ok setconf_rejects_bad_strict_firmware_and_health_test_values; else bad "setconf_rejects_bad_strict_firmware_and_health_test_values ($(cat "$T/out"))"; fi
if grep -q '^STRICT_FIRMWARE=0$' "$TPL/config.default" && ! grep -q '^HEALTH_TEST=' "$TPL/config.default"; then
  ok config_default_health_checks_by_default; else bad config_default_health_checks_by_default; fi

# --- action.sh: status report ---------------------------------------------------------------
action() { fakebin; PATH="$T/fakebin:$PATH" $SH "$T/svc/action.sh" < /dev/null > "$T/out" 2>&1; }
setup; mkmod; action
if grep -q '^match: yes$' "$T/out" && grep -q '^bound: yes' "$T/out" && grep -q '^module: enabled' "$T/out"; then
  ok action_reports_firmware_match; else bad action_reports_firmware_match; fi
setup; mkmod "$OTA"; action
if grep -q '^match: NO' "$T/out" && ! grep -q 'ignored' "$T/out"; then
  ok action_reports_firmware_mismatch; else bad action_reports_firmware_mismatch; fi
setup; mkmod "$OTA"; printf 'STRICT_FIRMWARE=1\nIGNORE_FINGERPRINT=1\n' > "$T/data/config"; action
if grep -q '^match: NO (ignored' "$T/out" && ! grep -q '^match: yes' "$T/out"; then
  ok action_reports_ignored_mismatch_as_no; else bad action_reports_ignored_mismatch_as_no; fi
setup; mkmod "$OTA"; touch "$T/svc/disable"; action
if grep -q '^module: DISABLED' "$T/out"; then ok action_reports_disabled_module; else bad action_reports_disabled_module; fi

setup; mkmod; echo "ok: provider bound, no crash loop, gesture monitor registered (2026-09-29 14:32:00)" > "$T/data/health-status"; action
if grep -q '^== health$' "$T/out" && grep -q '^health: ok: provider bound' "$T/out" \
   && grep -q '^policy: health check (STRICT_FIRMWARE=0)' "$T/out" && grep -q '^unfinished boots in a row: 0' "$T/out"; then
  ok action_reports_health_and_firmware_policy; else bad "action_reports_health_and_firmware_policy ($(cat "$T/out"))"; fi
setup; mkmod "$OTA"; action
if grep -q '^match: NO (service.sh sets up anyway and the health check rolls back' "$T/out" && grep -q '^health: no record' "$T/out"; then
  ok action_reports_mismatch_under_the_health_check_policy; else bad action_reports_mismatch_under_the_health_check_policy; fi
setup; mkmod "$OTA"; printf 'STRICT_FIRMWARE=1\n' > "$T/data/config"; action
if grep -q '^policy: strict (STRICT_FIRMWARE=1)' "$T/out" && grep -q '^match: NO (STRICT_FIRMWARE=1: service.sh disables' "$T/out"; then
  ok action_reports_strict_policy; else bad action_reports_strict_policy; fi
# mounted: yes needs the APK and the recents overlay; otherwise it says which one is missing.
setup; mkmod; mkdir -p "$T/root/system/priv-app/HyperGestures" "$T/root/product/overlay"; : > "$T/root/product/overlay/HyperGesturesOverlay.apk"
echo "metamodule + overlay ok" > "$T/data/mount-status"; action; cp "$T/out" "$T/out1"
rm -f "$T/root/product/overlay/HyperGesturesOverlay.apk"; action; cp "$T/out" "$T/out2"
rm -rf "$T/root/system/priv-app/HyperGestures"; : > "$T/root/product/overlay/HyperGesturesOverlay.apk"; action
if grep -q '^mount: metamodule + overlay ok$' "$T/out1" && grep -q '^mounted: yes (APK and overlay)$' "$T/out1" \
   && grep -q '^mounted: NO (APK /system/priv-app/HyperGestures: yes, overlay /product/overlay/HyperGesturesOverlay.apk: missing)$' "$T/out2" \
   && grep -q '^mounted: NO (APK /system/priv-app/HyperGestures: missing, overlay /product/overlay/HyperGesturesOverlay.apk: yes)$' "$T/out"; then
  ok action_reports_whether_the_apk_and_the_overlay_are_mounted; else bad "action_reports_whether_the_apk_and_the_overlay_are_mounted ($(grep '^mounted' "$T/out1" "$T/out2" "$T/out"))"; fi
setup; mkmod; mkdir -p "$T/root/system/priv-app/HyperGestures"; action; cp "$T/out" "$T/out1"
mkdir -p "$T/root/system/priv-app/HyperGesturesNotif"; action
NL='^notification hooks: /system/priv-app/HyperGesturesNotif:'; NM='(LSPosed module com.hypergestures.notif)$'
if grep -q "$NL missing $NM" "$T/out1" && grep -q "$NL yes $NM" "$T/out"; then
  ok action_reports_whether_the_notification_hooks_apk_is_there
else bad "action_reports_whether_the_notification_hooks_apk_is_there ($(grep '^notification' "$T/out1" "$T/out"))"; fi
setup; mkmod; printf 'android\n[x] android.miui.home.launcher.res\n[x] com.hypergestures.overlay\n' > "$T/overlays"; action; cp "$T/out" "$T/out1"
printf 'android\n[x] android.miui.home.launcher.res\n' > "$T/overlays"; action
if grep -A1 '^== overlay$' "$T/out1" | grep -q '^\[x\] com.hypergestures.overlay$' && grep -q '^lookup: ' "$T/out1" \
   && grep -A1 '^== overlay$' "$T/out" | grep -q '^com.hypergestures.overlay: not listed$' && has "overlay lookup android android:string/config_recentsComponentName" \
   && ! grep -qi "overlay guard\|fabricat\|com.android.shell:HyperGestures" "$T/out"; then
  ok action_reports_the_static_overlay; else bad "action_reports_the_static_overlay ($(grep -A2 '== overlay' "$T/out1"))"; fi
setup; mkmod; printf 'auto metamodule hybrid_mount 170\nauto → mixed (hybrid_mount)\n' > "$T/data/mount-mode"; action
if grep -q '^mount mode: auto → mixed (hybrid_mount)$' "$T/out" && ! grep -q '^next boot:' "$T/out" && ! grep -q '^metamodule-missed' "$T/out" \
   && grep -q '^sysui dump: [0-9][0-9]* bytes via the LauncherProxyService dumpable (the section alone)$' "$T/out"; then
  ok action_reports_the_mount_mode_and_the_sysui_dump_size; else bad "action_reports_the_mount_mode_and_the_sysui_dump_size ($(cat "$T/out"))"; fi
setup; mkmod; printf 'auto metamodule hybrid_mount 170\nauto → mixed (hybrid_mount)\n' > "$T/data/mount-mode"
rm -rf "$T/modules/hybrid_mount"; echo "hybrid_mount 170" > "$T/data/metamodule-missed"; action
if grep -q '^next boot: auto → self (no active metamodule)$' "$T/out" && grep -q '^metamodule-missed: hybrid_mount 170 ' "$T/out"; then
  ok action_reports_what_the_next_boot_mounts_when_it_differs; else bad "action_reports_what_the_next_boot_mounts_when_it_differs ($(grep -A4 '== mount' "$T/out"))"; fi
setup; mkmod; action; cp "$T/out" "$T/out1"; roles com.miui.home; echo com.miui.home > "$T/data/dormant"; action; cp "$T/out" "$T/out2"
roles com.teslacoilsw.launcher; action; cp "$T/out" "$T/out3"; rm -f "$T/data/dormant"; roles com.miui.home; action; cp "$T/out" "$T/out4"; roles ""; stub role echo; action
if grep -q '^home: com.teslacoilsw.launcher → active$' "$T/out1" && grep -q '^home: com.miui.home → dormant (reboot after choosing another launcher)$' "$T/out2" \
   && grep -q '^home: com.teslacoilsw.launcher → active from the next boot (this boot is dormant: reboot)$' "$T/out3" \
   && grep -q '^home: com.miui.home → dormant from the next boot (this boot is active' "$T/out4" && grep -q '^home: unknown .* → active$' "$T/out"; then
  ok action_reports_home_and_whether_this_boot_is_dormant; else bad "action_reports_home_and_whether_this_boot_is_dormant ($(grep -h '^home:' "$T/out1" "$T/out2" "$T/out3" "$T/out4" "$T/out" | tr '\n' '|'))"; fi

# --- review fixes: config parsing, nav-mode failures, disable during boot, crash ticks ----------
setup; printf 'NAV_MODE=fsg\n' > "$T/data/config"; hg_load_config; r=$?
if [ "$r" = 0 ] && [ "$NAV_MODE" = fsg ]; then ok load_config_sources_a_valid_config; else bad load_config_sources_a_valid_config; fi
setup; printf 'NAV_MODE=fsg\nif then fi (\n' > "$T/data/config"; ( hg_load_config; echo "rc=$? nav=${NAV_MODE:-}" > "$T/out" )
if grep -qx "rc=1 nav=" "$T/out" && grep -q "config: .* does not parse as shell" "$T/data/log.txt"; then
  ok load_config_ignores_a_config_that_does_not_parse; else bad "load_config_ignores_a_config_that_does_not_parse ($(cat "$T/out"))"; fi
# A config with a syntax error must not stop post-fs-data before it counts the boot.
setup; mkdir -p "$T/mod"; printf 'MOUNT=auto\nif then fi (\n' > "$T/data/config"; ( hg_post_fs_data_main "$T/mod" ) >/dev/null 2>&1
if [ "$(cat "$T/data/boot-attempts" 2>/dev/null)" = 1 ] && [ -f "$T/data/mount-mode" ]; then
  ok post_fs_data_survives_a_config_that_does_not_parse; else bad post_fs_data_survives_a_config_that_does_not_parse; fi
setup; mkmod; fakebin; printf 'if then fi (\n' > "$T/data/config"
PATH="$T/fakebin:$PATH" $SH "$T/svc/action.sh" < /dev/null > "$T/out" 2>&1
if grep -q "^config: .* does not parse as shell" "$T/out" && grep -q '^== module' "$T/out"; then
  ok action_reports_a_config_that_does_not_parse; else bad action_reports_a_config_that_does_not_parse; fi

setup; stub overlay "case \"\$1\" in list) cat \"$T/overlays\";; enable-exclusive) exit 1;; esac"; hg_apply_nav_mode gestural; r=$?
if [ "$r" = 1 ] && grep -q "nav mode: gestural FAILED" "$T/data/log.txt"; then ok nav_mode_reports_a_failed_command
else bad "nav_mode_reports_a_failed_command (rc=$r)"; fi
setup; hg_apply_nav_mode threebutton; r=$?; hg_apply_nav_mode keep; k=$?
if [ "$r$k" = 00 ] && grep -q "nav mode: threebutton$" "$T/data/log.txt"; then ok nav_mode_returns_0_when_it_worked
else bad "nav_mode_returns_0_when_it_worked ($r$k)"; fi
setup; printf '[x] %s\n[ ] %s\n' "$GESTURAL" "$THREEBUTTON" > "$T/overlays"
stub overlay "case \"\$1\" in list) cat \"$T/overlays\";; enable-exclusive) exit 1;; esac"; mkdir -p "$T/mod"
hg_rollback "a test" "$T/mod" >/dev/null 2>&1
if grep -q "rollback: 2/6 navigation for the rest of this boot: FAILED" "$T/data/log.txt"; then
  ok rollback_reports_a_failed_threebutton_switch; else bad rollback_reports_a_failed_threebutton_switch; fi

# Disabled in KernelSU while the boot waited for the provider: nothing is set up.
setup; mkmod; printf 'NAV_MODE=gestural\nDISABLE_MIUIHOME=1\nWATCHDOG=0\n' > "$T/data/config"; : > "$T/svc/disable"
hg_service_main "$T/svc"
if nochange && [ ! -f "$T/data/original-nav.txt" ] && grep -q "setup skipped: the module was disabled" "$T/data/log.txt" \
   && grep -q "^not run: the module was disabled or marked for removal during boot" "$T/data/health-status"; then
  ok service_skips_setup_when_disabled_during_boot; else bad service_skips_setup_when_disabled_during_boot; fi
setup; mkmod; printf 'NAV_MODE=gestural\nWATCHDOG=0\n' > "$T/data/config"; : > "$T/svc/remove"; hg_service_main "$T/svc"
if nochange && grep -q "setup skipped" "$T/data/log.txt"; then ok service_skips_setup_when_marked_for_removal_during_boot
else bad service_skips_setup_when_marked_for_removal_during_boot; fi

# The 3-button fallback of a boot that did not set up is put back by a later dormant boot.
setup; : > "$T/data/dormant-restored"; printf '[x] %s\n[ ] %s\n' "$GESTURAL" "$THREEBUTTON" > "$T/overlays"
stub dumpsys "cat \"$FIX/sysui_disconnected.txt\""; hg_not_set_up "a test"
if has "overlay enable-exclusive --category $THREEBUTTON" && [ ! -e "$T/data/dormant-restored" ]; then
  ok not_set_up_fallback_lets_a_dormant_boot_restore_navigation; else bad not_set_up_fallback_lets_a_dormant_boot_restore_navigation; fi

# After one crash of SystemUI the watchdog ticks every 15 s for 10 minutes, then every 120 s again.
setup; hg_crash_reset; seq_dumpsys sysui_connected.txt; mkdir -p "$T/mod"; crash_at 1 "$SUI" system_app_crash 1
tick() { advance "$1"; crash_tick; [ "$(wc -l < "$T/sleeps")" -ge 43 ] && : > "$T/mod/disable"; return 0; }
hg_watchdog_loop "$T/mod"; tick() { advance "$1"; crash_tick; }
want="120 "; i=0; while [ "$i" -lt 40 ]; do want="${want}15 "; i=$((i+1)); done; want="${want}120 120 "
if [ "$(sleeps)" = "$want" ] && ! grep -q "rollback" "$T/data/log.txt"; then
  ok watchdog_ticks_every_15s_for_10_minutes_after_a_crash; else bad "watchdog_ticks_every_15s_for_10_minutes_after_a_crash ($(sleeps))"; fi
# A second crash inside those 10 minutes is caught at the next 15 s tick.
setup; hg_crash_reset; seq_dumpsys sysui_connected.txt; mkdir -p "$T/mod"; crash_at 1 "$SUI" system_app_crash 1
tick() { advance "$1"; crash_tick; [ "$(wc -l < "$T/sleeps")" = 3 ] && dbe system_app_crash "$SUI"; return 0; }
hg_watchdog_loop "$T/mod" >/dev/null 2>&1; tick() { advance "$1"; crash_tick; }
if [ "$(sleeps)" = "120 15 15 " ] && grep -q "rollback: com.android.systemui crashed 2 times" "$T/data/log.txt"; then
  ok watchdog_rolls_back_on_a_second_crash_within_15s; else bad "watchdog_rolls_back_on_a_second_crash_within_15s ($(sleeps))"; fi

# A miss after a crash keeps the 30 s retry: a slow rebind still gets about 60 s before 3-button.
setup; hg_crash_reset; seq_dumpsys sysui_connected.txt sysui_disconnected.txt; mkdir -p "$T/mod"; crash_at 1 "$SUI" system_app_crash 1
tick() { advance "$1"; crash_tick; [ "$(wc -l < "$T/sleeps")" = 1 ] && rm -rf "$T/proc/2311"; return 0; } # SystemUI restarts in the 1st sleep
hg_watchdog_loop "$T/mod" >/dev/null 2>&1; tick() { advance "$1"; crash_tick; }
if [ "$(sleeps)" = "120 30 30 " ] && has "navbar.threebutton"; then
  ok watchdog_keeps_30s_retries_after_a_crash; else bad "watchdog_keeps_30s_retries_after_a_crash ($(sleeps))"; fi

# --- one automatic reboot after a rollback ----------------------------------------------------
rwsetup() { setup; HG_ROLLBACK_WATCH=600; }
rwsetup; mkdir -p "$T/mod"; : > "$T/mod/disable"; hg_crash_reset; crash_at 2 "$SUI" system_app_crash 1
hg_after_rollback "$T/mod"
if [ "$(sleeps)" = "15 15 " ] && has "^reboot" && grep -q "com.android.systemui crashed again" "$T/data/rollback-reboot"; then
  ok after_rollback_reboots_once_on_a_further_systemui_crash; else bad "after_rollback_reboots_once_on_a_further_systemui_crash ($(sleeps))"; fi
rwsetup; mkdir -p "$T/mod"; : > "$T/mod/disable"; hg_crash_reset; crash_at 1 "$HG_PKG" data_app_crash 1
hg_after_rollback "$T/mod"
if has "^reboot" && grep -q "com.hypergestures crashed again" "$T/data/rollback-reboot"; then
  ok after_rollback_reboots_on_a_further_crash_of_ours; else bad after_rollback_reboots_on_a_further_crash_of_ours; fi
rwsetup; mkdir -p "$T/mod"; : > "$T/mod/disable"; hg_crash_reset; crash_at 1 com.example data_app_crash 3
hg_after_rollback "$T/mod"
if ! has "^reboot" && [ "$(wc -l < "$T/sleeps")" = 40 ] && grep -q "after rollback: no further crash" "$T/data/log.txt"; then
  ok after_rollback_ignores_other_apps_and_ends_after_10_minutes; else bad "after_rollback_ignores_other_apps_and_ends_after_10_minutes ($(wc -l < "$T/sleeps"))"; fi
rwsetup; mkdir -p "$T/mod"; hg_crash_reset; crash_at 1 "$SUI"; hg_after_rollback "$T/mod"
if ! has "^reboot" && [ ! -f "$T/sleeps" ] && grep -q "not disabled, so no automatic reboot" "$T/data/log.txt"; then
  ok after_rollback_never_reboots_without_the_disable_file; else bad after_rollback_never_reboots_without_the_disable_file; fi
rwsetup; mkdir -p "$T/mod"; : > "$T/mod/disable"; echo earlier > "$T/data/rollback-reboot"; hg_crash_reset; crash_at 1 "$SUI"
hg_after_rollback "$T/mod"
if ! has "^reboot" && [ ! -f "$T/sleeps" ]; then ok after_rollback_reboots_only_once
else bad after_rollback_reboots_only_once; fi
rwsetup; mkdir -p "$T/mod"; : > "$T/mod/disable"; hg_crash_reset
tick() { advance "$1"; [ "$(wc -l < "$T/sleeps")" = 2 ] && rm -f "$T/mod/disable"; crash_tick; }; crash_at 3 "$SUI"
hg_after_rollback "$T/mod"; tick() { advance "$1"; crash_tick; }
if ! has "^reboot" && [ "$(sleeps)" = "15 15 " ]; then ok after_rollback_stops_when_the_module_is_re_enabled
else bad "after_rollback_stops_when_the_module_is_re_enabled ($(sleeps))"; fi
rwsetup; mkdir -p "$T/mod"; : > "$T/mod/disable"; hg_crash_reset; crash_at 1 "$SUI"; stub reboot 'exit 1'
hg_after_rollback "$T/mod"
if [ "$(grep -c '^reboot' "$T/calls")" = 2 ]; then ok after_rollback_falls_back_when_the_reboot_command_fails
else bad after_rollback_falls_back_when_the_reboot_command_fails; fi
# The next boot (module disabled): the guard tells the owner once why the phone rebooted.
rwsetup; mkdir -p "$T/mod"; : > "$T/mod/disable"; echo "com.android.systemui crashed again after the rollback" > "$T/data/rollback-reboot"
hg_rollback_reboot_notice; hg_rollback_reboot_notice
if [ "$(notified | grep -c 'restarted the phone, because com.android.systemui crashed again')" = 1 ]; then
  ok guard_tells_the_owner_once_about_the_reboot; else bad guard_tells_the_owner_once_about_the_reboot; fi
# A healthy setup allows a later rollback to reboot again.
setup; mkmod; printf 'NAV_MODE=gestural\nDISABLE_MIUIHOME=1\nWATCHDOG=0\n' > "$T/data/config"
echo x > "$T/data/rollback-reboot"; : > "$T/data/rollback-reboot-told"; hg_service_main "$T/svc"
if grep -q "^ok" "$T/data/health-status" && [ ! -e "$T/data/rollback-reboot" ] && [ ! -e "$T/data/rollback-reboot-told" ]; then
  ok healthy_setup_clears_the_rollback_reboot_marker; else bad healthy_setup_clears_the_rollback_reboot_marker; fi
# A crash-loop rollback in the health check is followed by the watch (and its one reboot).
rwsetup; mkmod; printf 'NAV_MODE=gestural\nDISABLE_MIUIHOME=1\nWATCHDOG=1\n' > "$T/data/config"; crash_at 1 "$SUI" system_app_crash 3
tick() { advance "$1"; crash_tick; [ "$(wc -l < "$T/sleeps")" = 3 ] && dbe system_app_crash "$SUI"; return 0; }
hg_service_main "$T/svc" >/dev/null 2>&1; tick() { advance "$1"; crash_tick; }
if [ -f "$T/svc/disable" ] && has "^reboot" && grep -q "after rollback: watching" "$T/data/log.txt"; then
  ok service_watches_after_a_health_check_rollback; else bad service_watches_after_a_health_check_rollback; fi

# --- HyperOS battery setting (Greeze exemption) -----------------------------------------------
setup; hg_power_exempt; r=$?
if [ "$r" = 0 ] && [ "$(cat "$T/pk")" = noRestrict ] && [ "$(cat "$T/data/original-bgcontrol")" = miuiAuto ] \
   && has "^content update" && grep -q "battery: com.hypergestures set to No restrictions (was miuiAuto)" "$T/data/log.txt"; then
  ok power_exempt_sets_no_restrictions_and_records_the_original; else bad power_exempt_sets_no_restrictions_and_records_the_original; fi
setup; pk_stub noRestrict; hg_power_exempt
if ! has "^content update" && ! has "^content insert" && [ ! -e "$T/data/original-bgcontrol" ]; then
  ok power_exempt_leaves_an_existing_no_restrictions_alone; else bad power_exempt_leaves_an_existing_no_restrictions_alone; fi
setup; pk_stub ''; hg_power_exempt
if { has "^content insert" || has "^content update"; } && [ "$(cat "$T/pk")" = noRestrict ] \
   && [ "$(cat "$T/data/original-bgcontrol")" = none ]; then
  ok power_exempt_adds_a_row_when_powerkeeper_has_none; else bad power_exempt_adds_a_row_when_powerkeeper_has_none; fi
setup; pk_stub '' empty; hg_power_exempt; r=$?
if [ "$r" = 1 ] && ! has "^content insert" && [ ! -e "$T/data/original-bgcontrol" ] \
   && [ -f "$T/data/power-pending" ] && grep -q "can't be read yet; will retry" "$T/data/log.txt"; then
  ok power_exempt_changes_nothing_when_powerkeeper_cannot_be_read; else bad power_exempt_changes_nothing_when_powerkeeper_cannot_be_read; fi
# PowerKeeper starts after boot_completed: unread at setup, then a later retry sets it.
setup; pk_stub '' empty; hg_power_exempt; r=$?; : > "$T/calls"
pk_stub miuiAuto; hg_power_retry; r2=$?
if [ "$r" = 1 ] && [ "$r2" = 0 ] && [ "$(cat "$T/pk")" = noRestrict ] && [ ! -e "$T/data/power-pending" ] \
   && grep -q "set to No restrictions (was miuiAuto)" "$T/data/log.txt"; then
  ok power_retry_sets_no_restrictions_after_powerkeeper_starts; else bad power_retry_sets_no_restrictions_after_powerkeeper_starts; fi
# Already exempt: one query, no write.
setup; pk_stub noRestrict; : > "$T/calls"; hg_power_retry
if has "^content query" && ! has "^content update" && ! has "^content insert"; then
  ok power_retry_noop_when_already_no_restrictions; else bad power_retry_noop_when_already_no_restrictions; fi
# HyperOS can reset the row after unlock; retry must re-apply without a pending marker.
setup; : > "$T/calls"; hg_power_retry
if [ "$(cat "$T/pk")" = noRestrict ] && { has "^content update" || has "^content insert"; }; then
  ok power_retry_reapplies_after_hyperos_resets_the_row; else bad power_retry_reapplies_after_hyperos_resets_the_row; fi
setup; : > "$T/pkfail"; hg_power_exempt; r=$?; rm -f "$T/pkfail"
if [ "$r" = 1 ] && [ -f "$T/data/power-pending" ] && grep -q "No restrictions FAILED; will retry" "$T/data/log.txt"; then
  ok power_exempt_reports_a_failed_write; else bad power_exempt_reports_a_failed_write; fi
# The original is recorded once: a later boot that finds miuiAuto again (HyperOS reset it) keeps it.
setup; echo noBg > "$T/data/original-bgcontrol"; hg_power_exempt
if [ "$(cat "$T/data/original-bgcontrol")" = noBg ] && [ "$(cat "$T/pk")" = noRestrict ]; then
  ok power_exempt_records_the_original_only_once; else bad power_exempt_records_the_original_only_once; fi

setup; pk_stub noRestrict; echo miuiAuto > "$T/data/original-bgcontrol"; hg_power_restore
if [ "$(cat "$T/pk")" = miuiAuto ] && [ ! -e "$T/data/original-bgcontrol" ]; then ok power_restore_puts_the_original_back
else bad power_restore_puts_the_original_back; fi
setup; pk_stub noRestrict; echo none > "$T/data/original-bgcontrol"; hg_power_restore
if [ "$(cat "$T/pk")" = miuiAuto ]; then ok power_restore_of_none_is_powerkeepers_default; else bad power_restore_of_none_is_powerkeepers_default; fi
setup; hg_power_restore
if ! has "^content"; then ok power_restore_without_a_record_does_nothing; else bad power_restore_without_a_record_does_nothing; fi
# A failing PowerKeeper never fails the restore of MiuiHome and navigation; the record is kept.
setup; pk_stub noRestrict; echo miuiAuto > "$T/data/original-bgcontrol"; : > "$T/pkfail"; hg_restore_all; r=$?; rm -f "$T/pkfail"
if [ "$r" = 0 ] && [ -f "$T/data/original-bgcontrol" ] && grep -q "back to miuiAuto FAILED" "$T/data/log.txt"; then
  ok power_restore_failure_does_not_fail_the_restore; else bad "power_restore_failure_does_not_fail_the_restore (rc=$r)"; fi

setup; mkmod; printf 'NAV_MODE=gestural\nDISABLE_MIUIHOME=1\nWATCHDOG=0\n' > "$T/data/config"; hg_service_main "$T/svc"
if [ "$(cat "$T/pk")" = noRestrict ] && before "am force-stop com.miui.home" "^content update"; then
  ok service_sets_no_restrictions_at_setup; else bad service_sets_no_restrictions_at_setup; fi
setup; mkmod; printf 'NAV_MODE=gestural\nWATCHDOG=0\n' > "$T/data/config"; stub dumpsys "cat \"$FIX/sysui_disconnected.txt\""
hg_service_main "$T/svc"
if ! has "^content update"; then ok service_unbound_leaves_the_battery_setting_alone
else bad service_unbound_leaves_the_battery_setting_alone; fi

setup; mkmod; pk_stub noRestrict; action
if grep -q '^== battery$' "$T/out" && grep -q '^battery: No restrictions' "$T/out"; then ok action_reports_the_battery_setting
else bad action_reports_the_battery_setting; fi
setup; mkmod; action
if grep -q '^battery: miuiAuto (HyperOS may freeze' "$T/out"; then ok action_reports_a_freezable_battery_setting
else bad action_reports_a_freezable_battery_setting; fi
# PowerKeeper's provider is down before the first unlock; Greeze still shows the last write.
setup; mkmod; pk_stub '' empty; stub dumpsys "printf '%s\n' '  [com.hypergestures] uid=10212' '    latestNoControl: duration=10s reason=NO_RESTRICT_APP'"
action
if grep -q 'battery: No restrictions (Greeze' "$T/out" && grep -q 'greezer:.*NO_RESTRICT_APP' "$T/out"; then
  ok action_reports_greezer_when_powerkeeper_cannot_be_read; else bad action_reports_greezer_when_powerkeeper_cannot_be_read; fi

# --- the watchdog's No-restrictions retries (battery: each one starts an app_process VM) --------
pcount() { grep -c "^content query" "$T/calls" 2>/dev/null; }
# First 600 s: every round.
wsetup; pk_stub noRestrict; : > "$T/calls"; wd; wtick 120; wd
if [ "$(pcount)" = 2 ]; then ok watchdog_checks_the_battery_setting_every_round_at_first
else bad "watchdog_checks_the_battery_setting_every_round_at_first ($(pcount))"; fi
# After that, a quiet round makes no PowerKeeper call...
wsetup; pk_stub noRestrict; wtick 600; wd; : > "$T/calls"; wtick 120; wd
if [ "$(pcount)" = 0 ]; then ok watchdog_quiet_round_skips_the_battery_setting
else bad "watchdog_quiet_round_skips_the_battery_setting ($(pcount))"; fi
# ...but the 30-minute bind check re-applies a setting HyperOS reset.
wsetup; pk_stub noRestrict; wd; wtick 1800; pk_stub miuiAuto; : > "$T/calls"; wd
if [ "$(cat "$T/pk")" = noRestrict ] && has "^dumpsys activity"; then ok watchdog_bind_check_reapplies_a_reset_battery_setting
else bad watchdog_bind_check_reapplies_a_reset_battery_setting; fi
# Pending (PowerKeeper never answers): one retry per 480 s, at most 12 a boot, not every round.
wsetup; pk_stub '' empty; : > "$T/data/power-pending"; wtick 600; wd; : > "$T/calls"
i=0; while [ "$i" -lt 60 ]; do wtick 120; wd; i=$((i+1)); done # 2 hours of rounds
n=$(grep -c "^content query --uri content://com.miui.powerkeeper.configure/userTable --projection bgControl" "$T/calls")
# 12 pending retries plus 4 bind checks (every 1800 s), each retry reading bgControl twice at most
if [ "$_hg_pw_tries" = 12 ] && [ "$n" -le 40 ] && [ "$n" -ge 12 ]; then ok watchdog_pending_retries_back_off_and_stop
else bad "watchdog_pending_retries_back_off_and_stop (tries=$_hg_pw_tries reads=$n)"; fi

# --- never "3 buttons" without buttons (2026-10-06) ------------------------------------------
TRANSPARENT=com.android.internal.systemui.navbar.transparent
# The record setup took with Nova as HOME: HyperOS's FSG=0 plus its transparent overlay.
setup; printf 'FSG=0\nNAVBAR=%s\n' "$TRANSPARENT" > "$T/data/original-nav.txt"; hg_restore_nav_state
if has "overlay enable-exclusive --category $TRANSPARENT" && has "settings put global force_fsg_nav_bar 1" \
   && ! has "settings put global force_fsg_nav_bar 0" && grep -q "draws no navigation" "$T/data/log.txt"; then
  ok restore_of_fsg0_transparent_turns_hyperos_gestures_on; else bad restore_of_fsg0_transparent_turns_hyperos_gestures_on; fi
# Real button layouts and the gesture bar keep their recorded FSG.
for nb in "$THREEBUTTON" "$TWOBUTTON" "$GESTURAL"; do
  setup; printf 'FSG=0\nNAVBAR=%s\n' "$nb" > "$T/data/original-nav.txt"; hg_restore_nav_state
  if has "settings put global force_fsg_nav_bar 0" && ! has "force_fsg_nav_bar 1"; then ok "restore_keeps_fsg0_with_${nb##*.}"
  else bad "restore_keeps_fsg0_with_${nb##*.}"; fi
done
# Dormant boot after an earlier version left FSG=0 + transparent: repaired, every dormant boot.
setup; live_nav; echo 0 > "$T/fsg"; printf '[x] %s\n[ ] %s\n[ ] %s\n' "$TRANSPARENT" "$THREEBUTTON" "$GESTURAL" > "$T/overlays"
: > "$T/data/dormant-restored"; WATCHDOG=0; hg_ensure_visible_nav
if [ "$(cat "$T/fsg")" = 1 ] && grep -q "navigation drew nothing" "$T/data/log.txt"; then ok dormant_repairs_navigation_that_draws_nothing
else bad dormant_repairs_navigation_that_draws_nothing; fi
setup; live_nav; echo 0 > "$T/fsg"; printf '[ ] %s\n[x] %s\n' "$TRANSPARENT" "$THREEBUTTON" > "$T/overlays"; hg_ensure_visible_nav
if [ "$(cat "$T/fsg")" = 0 ] && ! has "settings put"; then ok dormant_leaves_visible_buttons_alone
else bad dormant_leaves_visible_buttons_alone; fi
setup; live_nav; echo 1 > "$T/fsg"; printf '[x] %s\n' "$TRANSPARENT" > "$T/overlays"; hg_ensure_visible_nav
if ! has "settings put"; then ok dormant_leaves_hyperos_gestures_alone; else bad dormant_leaves_hyperos_gestures_alone; fi

# --- the app's "Hide gesture line" overlay: off in every undo path; any HOME leaves it to the app
HIDE=com.hypergestures:HgHideGestureLine
hidden() { grep -q "^\[x\] $HIDE\$" "$T/overlays"; } # our overlay is enabled
hide_on() { live_nav; printf 'com.android.systemui\n[x] %s\n[ ] %s\n' "$HIDE" "$GESTURAL" > "$T/overlays"; }
setup; hide_on; hg_hide_line_off; r=$?
if [ "$r" = 0 ] && ! hidden && has "overlay disable $HIDE" && ! has "^overlay list" \
   && grep -q "gesture line: the app's hiding overlay disabled or already off" "$T/data/log.txt"; then
  ok hide_line_off_disables_our_overlay_without_listing; else bad "hide_line_off_disables_our_overlay_without_listing (rc=$r)"; fi
# Disabled already, or not registered (OMS refuses: exit 1): never fatal, nothing else touched.
setup; live_nav; printf 'com.android.systemui\n[ ] %s\n[x] com.android.systemui:other\n' "$HIDE" > "$T/overlays"; hg_hide_line_off; r=$?
stub overlay 'case "$1" in disable) echo "Error: Unable to disable"; exit 1;; esac'; hg_hide_line_off; r2=$?
if [ "$r$r2" = 00 ] && grep -q '^\[x\] com.android.systemui:other$' "$T/overlays" \
   && grep -q "disabling the app's hiding overlay $HIDE refused" "$T/data/log.txt"; then
  ok hide_line_off_with_a_disabled_or_missing_overlay_is_harmless
else bad "hide_line_off_with_a_disabled_or_missing_overlay_is_harmless ($r$r2)"; fi
# A failing disable is logged and never fails the restore of MiuiHome and navigation.
setup; printf '[x] %s\n' "$HIDE" > "$T/overlays"; stub overlay "case \"\$1\" in list) cat \"$T/overlays\";; disable) exit 1;; esac"
hg_restore_all; r=$?
if [ "$r" = 0 ] && has "overlay disable $HIDE" && grep -q "disabling the app's hiding overlay $HIDE refused" "$T/data/log.txt"; then
  ok hide_line_failure_does_not_fail_the_restore; else bad "hide_line_failure_does_not_fail_the_restore (rc=$r)"; fi
setup; hide_on; printf 'FSG=1\nNAVBAR=%s\n' "$THREEBUTTON" > "$T/data/original-nav.txt"
if $SH "$TPL/restore.sh" > "$T/out" 2>&1 && ! hidden; then ok restore_script_shows_the_gesture_line
else bad restore_script_shows_the_gesture_line; fi
setup; hide_on; hg_install_guard "$TPL"; printf 'FSG=0\nNAVBAR=\n' > "$T/data/original-nav.txt"; HG_SYNC=1 $SH "$TPL/uninstall.sh"
if ! hidden && [ ! -e "$T/data" ]; then ok uninstall_shows_the_gesture_line; else bad uninstall_shows_the_gesture_line; fi
dormant; hide_on; hg_rollback "a test" "$T/svc" >/dev/null 2>&1
if ! hidden && has "overlay disable $HIDE"; then ok rollback_shows_the_gesture_line
else bad rollback_shows_the_gesture_line; fi
# Dormant boots leave it to the app (owner, 0.3.9.34: it works whatever HOME is): the first one
# after an active boot restores MiuiHome and the navigation but not the line, and later ones too.
dormant; printf '[x] %s\n[ ] %s\n[x] %s\n' "$HIDE" "$THREEBUTTON" "$GESTURAL" > "$T/overlays"; hg_service_main "$T/svc"
a=0; hidden && has "pm enable com.miui.home" && [ -f "$T/data/dormant-restored" ] && ! has "overlay disable $HIDE" && a=1
rm -f "$T/calls"; hg_service_main "$T/svc"
if [ "$a" = 1 ] && hidden && ! has "overlay disable $HIDE" && ! has "pm enable"; then
  ok dormant_boot_leaves_the_gesture_line_to_the_app; else bad "dormant_boot_leaves_the_gesture_line_to_the_app ($a)"; fi
# An active boot leaves it to the app (its setting decides).
setup; mkmod; live_nav; printf 'NAV_MODE=gestural\nDISABLE_MIUIHOME=1\nWATCHDOG=0\n' > "$T/data/config"
printf '[x] %s\n[ ] %s\n[x] %s\n' "$HIDE" "$THREEBUTTON" "$GESTURAL" > "$T/overlays"; hg_service_main "$T/svc"
if hidden && ! has "overlay disable $HIDE"; then ok active_boot_leaves_the_gesture_line_to_the_app
else bad active_boot_leaves_the_gesture_line_to_the_app; fi
# Stored enabled but not applied ("---", e.g. no idmap): still disabled, so it cannot come back.
setup; printf 'com.android.systemui\n--- %s\n' "$HIDE" > "$T/overlays"; hg_hide_line_off
if has "overlay disable $HIDE"; then ok hide_line_off_disables_an_unapplied_overlay; else bad hide_line_off_disables_an_unapplied_overlay; fi
# HOME becomes MiuiHome during an active boot: only the reboot notice; the line stays as the app
# set it.
wsetup; live_nav; printf '[x] %s\n[x] %s\n' "$HIDE" "$GESTURAL" > "$T/overlays"; hg_home_init; wd
roles "$MIUI" "" new; wtick 120; wd
if hidden && ! has "overlay disable" && notified | grep -qF "reboot to switch gestures to MiuiHome"; then
  ok home_switch_to_miuihome_leaves_the_gesture_line; else bad home_switch_to_miuihome_leaves_the_gesture_line; fi
# The other way (a dormant boot, HOME now another launcher): left to the app's next start.
wsetup; live_nav; printf '[x] %s\n' "$HIDE" > "$T/overlays"; : > "$T/data/dormant"; roles "$MIUI"; hg_home_init
roles "$NOVA" "" new; wtick 120; wd
if hidden && ! has "overlay disable" && notified | grep -qF "reboot to switch gestures to the new launcher"; then
  ok home_switch_away_from_miuihome_leaves_the_gesture_line; else bad home_switch_away_from_miuihome_leaves_the_gesture_line; fi

echo "passed=$PASS failed=$FAIL"
[ "$FAIL" = 0 ]
