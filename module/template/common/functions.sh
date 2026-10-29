# HyperGestures on-device logic (POSIX sh; runs under KernelSU's busybox ash).
# Device commands are indirected through HG_CMD_* so tests can stub them.
#
# Persistent changes this module makes, and what undoes each one (spec §9):
#   MiuiHome stopped + background denied     -> hg_enable_miuihome
#   navbar overlay / force_fsg_nav_bar       -> hg_restore_nav_state (from original-nav.txt, which
#                                               is written before any nav change, fallbacks too)
#   HOME (the owner sets a third-party one)  -> hg_restore_home gives it back to com.miui.home
#   our app's HyperOS battery setting        -> hg_power_restore (from original-bgcontrol, written
#   (PowerKeeper bgControl noRestrict)          before the first change)
#   module disabled itself (strict firmware  -> the owner re-enables it (ksud module enable)
#   check, failed health check, boot loop)
#   KernelSU App Profile of com.hypergestures -> left in place: it only lets our own process see
#   (umount_modules false, hg_ksu_keep_mounts)    module mounts, and matters to nothing else
#   the app's "Hide gesture line" overlay     -> hg_hide_line_off disables it in every undo path
#   on SystemUI (com.hypergestures:              (hg_restore_all); dormant boots and a switch of
#   HgHideGestureLine; opt-in, made by the app)  HOME leave it to the app's setting, which works
#                                               with any HOME; a boot without our APK (module
#                                               disabled or removed) has OMS drop it
# Undo paths: restore.sh (by hand, phone up), uninstall.sh (next boot, deferred until
# boot_completed), the guard in /data/adb/service.d (runs even while the module is disabled), and
# hg_rollback (a failed health check or a crash loop, this boot).
# Not a persistent change: the mounts. system/ (the two priv-app APKs: the app and its
# notification hooks, com.hypergestures.notif in $HG_NOTIF_DIR, the LSPosed module; their privapp
# allowlists and the hidden-API sysconfig) is mounted by the KernelSU metamodule (mixed mode) or by
# post-fs-data (self mode). The hooks' APK sits in the same system/priv-app tree as the app, so
# every mount, its absence and every undo path treat the two alike; it has no process, and
# LSPosed's own module list is the owner's (enabled there by hand, never touched by the module).
# MOUNT=auto, the default, picks mixed while an active metamodule is found and self otherwise, and
# falls back to self for good once a chosen metamodule did not mount the APK (hg_mount_resolve,
# hg_mount_check); MOUNT=metamodule and MOUNT=self force one. The static
# recents overlay (overlay/HyperGesturesOverlay.apk at the module root, which points
# config_recentsComponentName at our app) is self-mounted by post-fs-data on /product/overlay on
# every active boot (hg_self_mount explains why). Both exist only while the module is enabled: once
# it is disabled or removed, the next boot mounts neither, and SystemUI binds MiuiHome's recents
# again. KernelSU safe mode mounts nothing, so it boots with the stock recents too (MiuiHome, the
# navigation and HOME then still need restore.sh; runbook section 5).
# customize.sh dates the module's files to the install time (the zip's dates are fixed), or a
# metamodule's size+mtime sync keeps an old APK of equal size (hg_redate_files); service.sh
# re-dates them and asks for one more reboot when the app still runs an older versionCode.
# 0.3.5-alpha's fabricated recents overlay (com.android.shell:HyperGestures, files in
# /data/resource-cache) and its /data/adb/post-fs-data.d guard are deleted at every post-fs-data
# and by uninstall.sh (hg_cleanup_fab_overlay).
#
# HOME follows (dormant boots). While com.miui.home holds the HOME role, the module stays out of
# the way so stock HyperOS (MiuiHome's own gestures and recents) runs: post-fs-data reads the
# holder from roles.xml (hg_home_state) and, for MiuiHome, mounts no recents overlay (SystemUI
# keeps MiuiHome's gestures and recents) but keeps the app installed for what works with any HOME
# (its "Hide gesture line" overlay, its settings page): a metamodule mounts system/ as on any
# boot, and in self mode post-fs-data mounts system/ alone (hg_self_mount app); nothing binds the
# app's recents without the overlay. It leaves the marker dormant; service.sh then sets nothing up
# and, once after an active boot, puts back what that boot changed (hg_dormant_boot; the app's
# gesture-line overlay stays, its setting decides). Any other holder, or none readable: an active
# boot, as before. The watchdog notices a HOME change at runtime from roles.xml's mtime
# (hg_home_watch) and asks for a reboot, once per change across the boundary; switching needs a
# reboot because the overlay and the recents-protected permissions are only evaluated at boot.
# While its process runs, the app also posts the MiuiHome notice instantly (HgHomeSwitch).
# service.sh asks `cmd role` once a boot (hg_home_crosscheck): a mismatch is logged, and while
# roles.xml names no holder (a format change) its answer (home-role) stands in for roles.xml.
#
# Battery. Nothing here holds a wakelock or wakes the phone: `sleep` is timed on CLOCK_MONOTONIC,
# which stops in suspend, so "every 120 s" means 120 s of awake time. What one watchdog round
# (hg_watchdog_step) costs while the provider is bound, the normal case:
#   0 forks, 0 binder calls, 0 writes. Builtins only: 4 marker tests (restored, disable, remove,
#   watchdog-stop); one `-nt` test of roles.xml against home-mark (hg_home_watch); one glob per HG_CRASH_TAGS tag over $HG_DROPBOX (a readdir of cached dentries)
#   plus string tests on the names (hg_crash_scan); 2 reads of /proc/PID/cmdline (SystemUI and our
#   process are still the ones seen at the last bind check, hg_pid_is); then the loop's `sleep`
#   (a busybox applet; at most one fork where the shell doesn't run it in-process).
# Extra, only when it happens:
#   - the first HG_GESTURAL_WATCH s of the watchdog: `settings get secure navigation_mode` (the
#     gestural revert watch; 2 forks, 1 exec) per round, and `cmd overlay list` | awk | grep
#     (about 4 forks, 1 exec) only when that is not 2;
#   - log.txt changed since the app's copy was made, at most every HG_MIRROR_ROUNDS rounds: a
#     tail into $HG_DATA and one `su <app uid>` (hg_mirror_sync);
#   - a new crash entry of any tag: one read of its header (builtin; a .gz entry: zcat | awk);
#   - roles.xml was written (a HOME change, or RoleService re-saving it): touch -r and 2 sed
#     (and `cmd role` if it names no holder);
#   - a pid changed, the previous check missed, or HG_BIND_RECHECK s passed: the bind check
#     (the app's hypergestures_provider_bound, hg_app_fact: one `settings get`; without a valid
#     one `dumpsys activity service ... | awk`; 2 pidof, the gestural check: about 12 forks,
#     4 execs).
# So an hour of awake time costs 2 bind checks (about 8 execs) instead of 0.3.8's 30 rounds of
# 4 `dumpsys dropbox --print`, the SystemUI dump and `cmd overlay list` (6 execs, about 35-45
# forks and a /data write each: about 180 execs and 1200 forks an hour). Only if $HG_DROPBOX can't
# be listed does the crash watch fall back to the 4 dropbox dumps per round (logged once a boot).
# The health check (HG_HEALTH_WINDOW s after setup, every HG_HEALTH_TICK s) still checks the bind
# (hg_provider_bound) at each tick; its crash checks are the same free scan. A dormant boot's
# watchdog does only the marker tests, the roles.xml `-nt` test and hg_mirror_sync's mark tests
# per round.
: "${HG_CMD_DUMPSYS:=dumpsys}"
: "${HG_CMD_SETTINGS:=settings}"
: "${HG_CMD_OVERLAY:=cmd overlay}"
: "${HG_CMD_PM:=pm}"
: "${HG_CMD_AM:=am}"
: "${HG_CMD_APPOPS:=cmd appops}"
: "${HG_CMD_ROLE:=cmd role}"
: "${HG_CMD_NOTIFY:=cmd notification post}"
: "${HG_CMD_GETPROP:=getprop}"
: "${HG_CMD_SU:=su}"
: "${HG_CMD_LOG:=log}"
: "${HG_CMD_PIDOF:=pidof}"
: "${HG_CMD_KSUD:=ksud}"
: "${HG_CMD_CONTENT:=content}"             # PowerKeeper's per-app battery setting (hg_power_exempt)
: "${HG_CMD_REBOOT:=svc power reboot}"     # hg_after_rollback's one reboot (through PowerManager)
: "${HG_CMD_REBOOT_FALLBACK:=reboot}"      # if that fails (toybox: sync, then sys.powerctl)
: "${HG_PKG_CACHE:=/data/system/package_cache}"
: "${HG_RESCACHE:=/data/resource-cache}"
: "${HG_DATA:=/data/adb/hypergestures}"
# The app's copy of the module log for Save logs (HgLogCollector): module-log.txt (and
# module-log.old.txt) in its external files dir, as the app sees it (getExternalFilesDir), written
# only by hg_mirror_sync as the app's own uid; root never touches that dir.
: "${HG_LOG_MIRROR:=/sdcard/Android/data/com.hypergestures/files/module-log.txt}"
: "${HG_MODDIR:=/data/adb/modules/hypergestures}"
: "${HG_GUARD:=/data/adb/service.d/hypergestures-guard.sh}"
: "${HG_OLD_OVERLAY_GUARD:=/data/adb/post-fs-data.d/hypergestures-overlay-guard.sh}" # 0.3.5-alpha's
: "${HG_UPTIME:=/proc/uptime}"
: "${HG_BOOT_ID_FILE:=/proc/sys/kernel/random/boot_id}"
: "${HG_CMD_MOUNT:=mount}"
: "${HG_CMD_UMOUNT:=umount}"
: "${HG_CMD_CHCON:=chcon}"
: "${HG_STAGE:=/dev/hg_stage}"
: "${HG_MOUNTS:=/proc/mounts}"
: "${HG_ROOT:=}" # prefix of the mount targets (tests point it at a fixture tree)
: "${HG_DROPBOX:=/data/system/dropbox}"   # DropBoxManagerService's entry files (hg_crash_scan)
: "${HG_CMD_ZCAT:=zcat}"                  # only for a new .gz dropbox entry
: "${HG_CMD_STAT:=stat}"                  # post-fs-data's APK stamp (hg_apk_stamp)
: "${HG_TIMEOUT:=/system/bin/timeout}"    # toybox's timeout (hg_timeout)
: "${HG_PROC:=/proc}"                     # the watchdog's pid checks (hg_pid_is)
: "${HG_METAMODULE_LINK:=/data/adb/metamodule}" # KernelSU's link to the active metamodule
: "${HG_MODULES_DIR:=/data/adb/modules}"
: "${HG_ROLES_XML:=}" # RoleService's roles.xml for user 0; empty: the first of HG_ROLES_PATHS that exists
: "${HG_ROLES_PATHS:=/data/misc_de/0/apexdata/com.android.permission/roles.xml /data/system/users/0/roles.xml}"
HG_PKG=com.hypergestures
HG_NOTIFY_RECEIVER=$HG_PKG/com.hypergestures.HgNotifyReceiver # hg_notify's receiver, and its action
HG_NOTIFY_ACTION=com.hypergestures.action.NOTIFY
HG_NAVBAR_PREFIX=com.android.internal.systemui.navbar
HG_SYSUI=com.android.systemui/.SystemUIService
HG_RESTORE_TRIES=3 # boots a failing deferred restore is attempted before it is left to the owner
HG_SYSUI_PKG=com.android.systemui
HG_HIDE_LINE=$HG_PKG:HgHideGestureLine # the app's fabricated overlay (HgHideGestureLine.java)
HG_MIUIHOME=com.miui.home
: "${HG_HEALTH_WINDOW:=120}" # s the health check watches after setup (not a setting; tests shorten it)
HG_HEALTH_TICK=15    # s between its checks
: "${HG_ROLLBACK_WATCH:=600}" # s hg_after_rollback watches (not a setting; tests shorten it)
HG_CRASH_WINDOW=600  # s: 2 crashes of SystemUI or our package this close together are a crash loop
# Our settings page's own process (HgSettingsActivity, android:process=":settings"): its crashes
# don't count, it runs neither recents nor gestures. Every other process of ours does.
HG_CRASH_IGNORED="$HG_PKG:settings"
HG_KSU_HEAL_MAX=3    # restores of a reset KernelSU profile per boot (hg_ksu_heal)
# dropbox tags holding crashes: Java crashes, and native ones (ActivityManager writes the same
# "Process: NAME" header on both, so one parser reads them all). The dumpsys fallback
# (hg_crash_sample): a tag with no entries still prints the "Drop box contents:" header and counts 0.
HG_CRASH_TAGS="system_app_crash data_app_crash system_app_native_crash data_app_native_crash"
HG_BOOT_TRIES=3      # boots in a row that never reach boot_completed before post-fs-data disables us
HG_BIND_RECHECK=1800 # s of watchdog time between bind checks while neither pid changed
HG_GESTURAL_WATCH=600 # s after the watchdog starts during which every tick checks for a gestural revert
HG_POWER_RETRY=480   # s of watchdog time between No-restrictions retries while power-pending exists
HG_POWER_TRIES=12    # such retries per boot; after that only the bind checks retry (hg_power_due)
HG_LOG_MAX=262144    # bytes: a bigger log.txt is moved to log.old.txt at boot (hg_rotate_log)
HG_MIRROR_ROUNDS=5   # watchdog rounds between copies of a changed log for the app (hg_mirror_sync)
: "${HG_MIRROR_TIMEOUT:=10}" # s one log copy as the app's uid may take (hg_timeout_k; tests shorten it)

# Appends to $HG_DATA/log.txt and mirrors to logcat (tag HyperGestures), which survives the
# state dir being deleted after a successful uninstall. The app's copy follows by hg_mirror_sync.
hg_log() {
  mkdir -p "$HG_DATA"
  _hg_lg="$(date '+%F %T') $*"
  echo "$_hg_lg" >> "$HG_DATA/log.txt"
  $HG_CMD_LOG -t HyperGestures "$*" >/dev/null 2>&1
  return 0
}

# _hg_app_uid = our app's uid for user 0 from `pm list packages -U --user 0` (bounded by
# hg_timeout; the exact package's line only; an app uid, 10000-19999), kept for this process;
# 1 if none. Never from a stat of the app's files: the app could plant what such a stat would see.
hg_app_uid() {
  [ -n "${_hg_app_uid:-}" ] && return 0
  _hg_app_uid="$(hg_timeout $HG_CMD_PM list packages -U --user 0 "$HG_PKG" 2>/dev/null |
    awk -v p="package:$HG_PKG" '{ sub(/\r$/, "") } $1 == p && $2 ~ /^uid:[0-9]+$/ {
      print substr($2, 5); exit }')"
  case "$_hg_app_uid" in ''|*[!0-9]*) _hg_app_uid=""; return 1 ;; esac
  [ "$_hg_app_uid" -ge 10000 ] && [ "$_hg_app_uid" -le 19999 ] && return 0
  _hg_app_uid=""
  return 1
}

# 0 if file $1 exists and is newer than mark $2, or $2 does not exist (busybox's -nt is false
# when either file is missing).
hg_newer() {
  [ -f "$1" ] || return 1
  [ ! -f "$2" ] || [ "$1" -nt "$2" ]
}

# Runs a command with HG_MIRROR_TIMEOUT s, then SIGKILL 2 s after the TERM (-k 2) where the
# timeout binary takes -k (toybox's, as hg_timeout prefers it, or another `timeout`; probed once
# per process, falling back to no -k, then to no limit). Only the detached log copy uses it: a
# process asleep on FUSE ignores even SIGKILL, so nothing root waits for may run under it.
hg_timeout_k() {
  if [ -z "${_hg_tk:-}" ]; then
    if [ -x "$HG_TIMEOUT" ] && "$HG_TIMEOUT" -k 2 5 true >/dev/null 2>&1; then _hg_tk="$HG_TIMEOUT -k 2"
    elif [ -x "$HG_TIMEOUT" ]; then _hg_tk="$HG_TIMEOUT"
    elif timeout -k 2 5 true >/dev/null 2>&1; then _hg_tk="timeout -k 2"
    elif command -v timeout >/dev/null 2>&1; then _hg_tk=timeout
    else _hg_tk=none; fi
  fi
  if [ "$_hg_tk" = none ]; then "$@"; else $_hg_tk "$HG_MIRROR_TIMEOUT" "$@"; fi
}

# Writes the newest HG_LOG_MAX bytes of root's file $1 as $2 (a file name) in the app's files dir,
# as the app's uid $3: `su <uid> -c` with a fixed script; only module constants in it (the dir and
# the name, single-quoted; refused if either holds a quote; the uid re-checked as digits). The
# data goes on stdin from a root-only file in $HG_DATA (mirror-put), not a pipe. su <uid> drops
# root's file permissions only (KernelSU keeps its su SELinux domain, which allows everything):
# the script first checks it really runs as that uid and gid with no group 0 (a su that ignored
# the uid would make it a root write), then cds into the dir (by file permissions as the app: a
# link the app planted leads only where the app may go itself), writes $2.tmp and renames it over
# $2, so the app never reads half a copy. Bounded by hg_timeout_k; runs only inside the detached
# hg_mirror_job. 0 written; 3 the dir is not reachable (not created yet, or before the first
# unlock); 4 su did not switch to the uid; 124/137 timed out; anything else: su refused or the
# write failed.
hg_mirror_put() {
  _hg_mpd="${HG_LOG_MIRROR%/*}"
  case "$_hg_mpd$2" in *"'"*) return 1 ;; esac
  case "$3" in ''|*[!0-9]*) return 1 ;; esac
  tail -c "$HG_LOG_MAX" "$1" > "$HG_DATA/mirror-put" 2>/dev/null || return 1
  _hg_mps="[ \"\$(id -u)\" = '$3' ] && [ \"\$(id -g)\" = '$3' ] || exit 4"
  _hg_mps="$_hg_mps; case \" \$(id -G) \" in *' 0 '*) exit 4 ;; esac"
  _hg_mps="$_hg_mps; cd '$_hg_mpd' 2>/dev/null || exit 3"
  _hg_mps="$_hg_mps; umask 077; cat > '$2.tmp' && mv -f '$2.tmp' '$2'"
  hg_timeout_k $HG_CMD_SU "$3" -c "$_hg_mps" < "$HG_DATA/mirror-put" >/dev/null 2>&1
  _hg_mpr=$?
  rm -f "$HG_DATA/mirror-put"
  return "$_hg_mpr"
}

# The copy itself ($1 = the app's uid), detached from the caller (hg_mirror_sync starts it in the
# background and never waits): log.old.txt, then log.txt, each when changed since its mark (or the
# old one after an unreachable dir, mirror-unreached), stopping at the first failure. Root writes
# only in $HG_DATA: the marks (mirror-mark, mirror-mark-old, dated as the file copied) and the
# result, mirror-rc (0, 3 or the failing rc; renamed into place last).
hg_mirror_job() {
  _hg_mjr=0
  for _hg_msf in log.old.txt log.txt; do
    if [ "$_hg_msf" = log.txt ]; then
      _hg_msm="$HG_DATA/mirror-mark"; _hg_msn="${HG_LOG_MIRROR##*/}"
    else
      _hg_msm="$HG_DATA/mirror-mark-old"; _hg_msn="${HG_LOG_MIRROR%.txt}.old.txt"
      _hg_msn="${_hg_msn##*/}"
    fi
    if [ "$_hg_msf" = log.txt ] || [ ! -f "$HG_DATA/mirror-unreached" ]; then
      hg_newer "$HG_DATA/$_hg_msf" "$_hg_msm" || continue
    else
      [ -f "$HG_DATA/$_hg_msf" ] || continue
    fi
    touch -r "$HG_DATA/$_hg_msf" "$_hg_msm.new" 2>/dev/null # the file as it is copied
    hg_mirror_put "$HG_DATA/$_hg_msf" "$_hg_msn" "$1"; _hg_mjr=$?
    case "$_hg_mjr" in
      0) mv -f "$_hg_msm.new" "$_hg_msm" ;;
      3) # Not reachable: wait for a log's next change, and take the old log along then.
         rm -f "$_hg_msm.new"
         [ -f "$HG_DATA/log.old.txt" ] && touch -r "$HG_DATA/log.old.txt" "$HG_DATA/mirror-mark-old"
         touch -r "$HG_DATA/log.txt" "$HG_DATA/mirror-mark" 2>/dev/null
         : > "$HG_DATA/mirror-unreached"
         break ;;
      *) rm -f "$_hg_msm.new"; break ;;
    esac
  done
  [ "$_hg_mjr" = 0 ] && rm -f "$HG_DATA/mirror-unreached"
  echo "$_hg_mjr" > "$HG_DATA/mirror-rc.tmp" && mv -f "$HG_DATA/mirror-rc.tmp" "$HG_DATA/mirror-rc"
} 2>/dev/null

# 0 if process $1 runs: /proc/$1/stat is there and its state is not Z (a job of ours that ended
# stays a zombie until this shell reaps it, and `kill -0` would still find it). Builtins only.
hg_job_running() {
  [ -r "/proc/$1/stat" ] || return 1
  IFS= read -r _hg_jst < "/proc/$1/stat" 2>/dev/null || return 1
  _hg_jst="${_hg_jst##*) }"
  case "$_hg_jst" in Z*|X*|'') return 1 ;; esac
  return 0
}

# 0 if no copy is in flight (the last one's result, mirror-rc, taken in: 0 or 3 fine, 4 or any
# other failure turns copying off for this process, logged once); 1 while one runs (its pid in
# mirror-pid, hg_job_running) or copying is off. A job gone without a result was killed: off too.
# Builtins only (a `wait` for a job of this shell that has ended returns at once).
hg_mirror_reap() {
  [ -f "$HG_DATA/mirror-pid" ] || return 0
  IFS= read -r _hg_mpid < "$HG_DATA/mirror-pid"
  case "$_hg_mpid" in ''|*[!0-9]*) rm -f "$HG_DATA/mirror-pid"; return 0 ;; esac
  hg_job_running "$_hg_mpid" && return 1
  wait "$_hg_mpid" 2>/dev/null # reaps it if it is ours
  _hg_mrc=""
  [ -f "$HG_DATA/mirror-rc" ] && IFS= read -r _hg_mrc < "$HG_DATA/mirror-rc"
  rm -f "$HG_DATA/mirror-pid" "$HG_DATA/mirror-rc"
  case "$_hg_mrc" in
    0|3) return 0 ;;
    4) hg_log "log copy: su did not switch to our app's uid and groups; no copy for Save logs this boot" ;;
    '') hg_log "log copy: the copy ended without a result; no copy for Save logs this boot" ;;
    *) hg_log "log copy: su to our app's uid failed (rc $_hg_mrc); no copy for Save logs this boot" ;;
  esac
  _hg_ms_off=1
  return 1
}

# The app's copy of the module log (Save logs), never written by root (ruling: root writes
# nothing into the app's dirs): root reads its own log.txt and log.old.txt and hands them to su
# as the app's uid (hg_mirror_job, hg_mirror_put), in the background: root never waits for it (a
# su'd writer asleep on FUSE ignores every signal), and a new copy starts only once the last one
# has ended (hg_mirror_reap), so at most one can hang. A round costs builtins only unless log.txt
# (or log.old.txt) changed since the last copy; then at most every HG_MIRROR_ROUNDS calls (the
# watchdog's rounds); $1 = now (service.sh's end) skips that wait. Root never looks at the app's
# dir itself (a test there would follow a link the app planted, possibly into a FUSE mount it
# serves): the su'd script finds out (rc 3), and the copy then waits for the next change of a
# log, so a page never opened costs one su per log change at most. su refused or timed out, su
# not switching to the uid, or no uid: logged once and no copy for the rest of this process (Save
# logs then has no module log); never a root write instead. A line logged within the second of a
# copy reaches it with the next later line.
hg_mirror_sync() {
  [ -z "${_hg_ms_off:-}" ] || return 0
  hg_mirror_reap || return 0
  hg_newer "$HG_DATA/log.txt" "$HG_DATA/mirror-mark" \
    || hg_newer "$HG_DATA/log.old.txt" "$HG_DATA/mirror-mark-old" || return 0
  if [ "${1:-}" != now ]; then
    _hg_ms_wait=$(( ${_hg_ms_wait:-0} - 1 ))
    [ "$_hg_ms_wait" -le 0 ] || return 0
  fi
  _hg_ms_wait="$HG_MIRROR_ROUNDS"
  if ! hg_app_uid; then
    _hg_ms_off=1; hg_log "log copy: no uid for $HG_PKG from pm; no copy for Save logs this boot"
    return 0
  fi
  rm -f "$HG_DATA/mirror-rc"
  (hg_mirror_job "$_hg_app_uid") </dev/null >/dev/null 2>&1 &
  echo "$!" > "$HG_DATA/mirror-pid"
  return 0
}

# Moves a log.txt over HG_LOG_MAX bytes to log.old.txt (replacing the one before), so the log
# never grows without bound (the app's copy follows, hg_mirror_sync). Once per boot, at service.sh
# start once boot_completed was recorded (the only size check: no fork per line).
hg_rotate_log() {
  [ -f "$HG_DATA/log.txt" ] || return 0
  set -- $(wc -c < "$HG_DATA/log.txt" 2>/dev/null)
  case "${1:-}" in ''|*[!0-9]*) return 0 ;; esac
  [ "$1" -gt "$HG_LOG_MAX" ] || return 0
  mv -f "$HG_DATA/log.txt" "$HG_DATA/log.old.txt" && hg_log "log rotated: the previous $1 bytes are in log.old.txt"
  return 0
}

# _hg_vv, _hg_vvc = version and versionCode from $1/module.prop (builtins only; a CR at a line's
# end is dropped). Returns 1 when there is no module.prop or it names no version.
hg_read_version() {
  _hg_vv=""; _hg_vvc=""
  [ -f "$1/module.prop" ] || return 1
  _hg_vcr="$(printf '\r')"
  while IFS= read -r _hg_vl || [ -n "$_hg_vl" ]; do
    _hg_vl="${_hg_vl%"$_hg_vcr"}"
    case "$_hg_vl" in
      version=*) _hg_vv="${_hg_vl#version=}" ;;
      versionCode=*) _hg_vvc="${_hg_vl#versionCode=}" ;;
    esac
    _hg_vl=""
  done < "$1/module.prop"
  [ -n "$_hg_vv" ]
}

# Logs the module's version from $1/module.prop, once per boot. The app reads its version from
# the setting hg_publish_version writes, and this line from its log copy only when that is unset.
hg_log_version() {
  hg_read_version "$1" && hg_log "service: module version $_hg_vv ($_hg_vvc)"
  return 0
}

# Writes $2 to our Settings.Global key $1 for the app (hg_publish_version, hg_publish_status).
# Settings, never files: the app's data dir is the app's to fill, and root writing there can be
# led by a link the app plants (a rename into it crosses fscrypt policies, so mv falls back to a
# copy and a chown by path); a setting has no path to redirect. The app reads its keys
# (HgLogCollector, HgModuleStatus). Needs the settings provider: service.sh after boot_completed,
# or customize.sh while the phone runs (never post-fs-data). Bounded by hg_timeout; 0 = written.
hg_global_put() {
  hg_timeout $HG_CMD_SETTINGS put global "$1" "$2" >/dev/null 2>&1
}

# Deletes our Settings.Global key $1. Never fails.
hg_global_del() {
  hg_timeout $HG_CMD_SETTINGS delete global "$1" >/dev/null 2>&1
  return 0
}

# Deletes every Settings.Global value the module and the app exchange (hg_global_del): the
# module's version, installed update and status, and the app's health facts (HG_FACT_BOUND,
# HG_FACT_MONITOR). For the undo after boot_completed (hg_restore_after_boot: the guard and
# uninstall.sh), so a disabled or removed module leaves none behind; the owner's switches stay.
# Never fails.
hg_globals_clear() {
  for _hg_gk in hypergestures_module_version hypergestures_module_update \
      hypergestures_module_status "$HG_FACT_BOUND" "$HG_FACT_MONITOR"; do
    hg_global_del "$_hg_gk"
  done
}

# HG_FLAT = $1 on one line with no ';' (a newline or CR becomes a space, a ';' a ','): one value of
# the key=value;... list in hypergestures_module_status. Builtins only.
hg_flat() {
  HG_FLAT="$1"; _hg_fln="
"; _hg_flr="$(printf '\r')"
  while :; do
    case "$HG_FLAT" in
      *"$_hg_fln"*) HG_FLAT="${HG_FLAT%%"$_hg_fln"*} ${HG_FLAT#*"$_hg_fln"}" ;;
      *"$_hg_flr"*) HG_FLAT="${HG_FLAT%%"$_hg_flr"*} ${HG_FLAT#*"$_hg_flr"}" ;;
      *";"*) HG_FLAT="${HG_FLAT%%;*},${HG_FLAT#*;}" ;;
      *) return 0 ;;
    esac
  done
}

# Publishes the module's version ($1/module.prop) to the app as a Settings.Global value
# (hg_global_put), "<version> (<versionCode>)": the running module's as
# hypergestures_module_version, with " boot=<this boot's id>" after it, or with $2 = update the
# installed one that runs from the next boot as hypergestures_module_update. The app's About and
# Save logs read them (HgLogCollector.moduleVersion, moduleUpdate), so they no longer depend on
# the app's log copy, which can't be written before the first unlock. hg_service_main writes
# hypergestures_module_version once per boot, active and dormant boots alike, and then deletes
# hypergestures_module_update (that version is the running one now); customize.sh writes
# hypergestures_module_update at install. Never fails a caller: returns 0 when written, 1 when
# module.prop names no version (nothing written), 2 when the write failed (logged; the app then
# finds no value for this boot and reads the version from the log).
hg_publish_version() {
  hg_read_version "$1" || return 1
  if [ "${2:-}" = update ]; then
    _hg_pvk=hypergestures_module_update; _hg_pvt="$_hg_vv ($_hg_vvc)"
  else
    _hg_pvb="$(hg_boot_id)" || _hg_pvb=""
    _hg_pvk=hypergestures_module_version; _hg_pvt="$_hg_vv ($_hg_vvc) boot=$_hg_pvb"
  fi
  hg_global_put "$_hg_pvk" "$_hg_pvt" && return 0
  hg_log "version setting: $_hg_pvk not written (settings put failed; the app reads the version from the log instead)"
  return 2
}

# This boot's state for the app (HgModuleStatus), as Settings.Global hypergestures_module_status
# (hg_global_put): key=value pairs on one line, separated by ';' (each value flattened, hg_flat):
# bootId (this boot's: the app ignores another boot's value), state (HG_STATE: starting, while
# service.sh has not finished setting up; active; dormant; rolled-back; not-set-up; refused), boot
# (post-fs-data's choice: dormant while its marker is there, else active), health (the
# health-status text, without its time) and, for dormant, rolled-back, not-set-up and refused,
# reason (that text without its "FAILED: " or "not run: "), mountMode and mount (post-fs-data's
# mount-mode description and mount-status), and only when they apply: metamoduleMissed (the
# metamodule that did not mount the app: auto uses self), staleApk (hg_app_version_check, this
# boot: "app N, module M"), powerPending=1 (No restrictions not set yet), restoreGaveUp=1 (an undo
# left to the owner). The same facts as action.sh, which the app can't run. Rewritten whole at
# each change, from the choke points that make one: hg_health_status (every state change),
# hg_power_mark, hg_app_version_check. Only in a process that recorded a state
# (hg_health_status: service.sh, or the guard's boot-loop notice, both after boot_completed), so
# never from restore.sh or post-fs-data (no settings provider yet): post-fs-data's choices
# (dormant, mount-mode, mount-status) wait in $HG_DATA for service.sh's first status. Builtins,
# then one settings call. Never fails; a failed write is logged once a boot.
hg_publish_status() {
  [ -n "${HG_STATE:-}" ] || return 0 # not service.sh or the guard after its first state
  _hg_psb="$(hg_boot_id)" || _hg_psb=""
  _hg_pss="$HG_STATE"; _hg_psh="${HG_HEALTH:-}"
  if [ -f "$HG_DATA/dormant" ]; then _hg_psd=dormant; else _hg_psd=active; fi
  hg_flat "$_hg_psb"; _hg_pst="bootId=$HG_FLAT"
  hg_flat "$_hg_pss"; _hg_pst="$_hg_pst;state=$HG_FLAT;boot=$_hg_psd"
  hg_flat "$_hg_psh"; _hg_pst="$_hg_pst;health=$HG_FLAT"
  case "$_hg_pss" in
    starting|active) ;;
    *) case "$_hg_psh" in "FAILED: "*|"not run: "*) _hg_psh="${_hg_psh#*: }" ;; esac
       hg_flat "$_hg_psh"; _hg_pst="$_hg_pst;reason=$HG_FLAT" ;;
  esac
  _hg_psm=""; _hg_psl=""
  [ -f "$HG_DATA/mount-mode" ] && { IFS= read -r _hg_psl; IFS= read -r _hg_psm; } \
    < "$HG_DATA/mount-mode"
  hg_flat "$_hg_psm"; _hg_pst="$_hg_pst;mountMode=$HG_FLAT"
  _hg_psl=""; [ -f "$HG_DATA/mount-status" ] && IFS= read -r _hg_psl < "$HG_DATA/mount-status"
  hg_flat "$_hg_psl"; _hg_pst="$_hg_pst;mount=$HG_FLAT"
  _hg_psl=""
  [ -f "$HG_DATA/metamodule-missed" ] && IFS= read -r _hg_psl < "$HG_DATA/metamodule-missed"
  [ -z "$_hg_psl" ] || { hg_flat "$_hg_psl"; _hg_pst="$_hg_pst;metamoduleMissed=$HG_FLAT"; }
  [ -z "${HG_STALE_APK:-}" ] || { hg_flat "$HG_STALE_APK"; _hg_pst="$_hg_pst;staleApk=$HG_FLAT"; }
  [ -f "$HG_DATA/power-pending" ] && _hg_pst="$_hg_pst;powerPending=1"
  [ -f "$HG_DATA/restore-gave-up" ] && _hg_pst="$_hg_pst;restoreGaveUp=1"
  hg_global_put hypergestures_module_status "$_hg_pst" || hg_log_once \
    "status setting: hypergestures_module_status not written (settings put failed; the app's settings page goes by HOME)" \
    "status setting"
  return 0
}

# Whole seconds since boot, from /proc/uptime: monotonic, so NTP or a clock change can't move it
# (date +%s can jump, or be unset this early). Prints nothing and returns 1 if it can't be read.
hg_uptime() {
  _hg_up="$(sed -n '1s/[^0-9].*//p' "$HG_UPTIME" 2>/dev/null)"
  case "$_hg_up" in ''|*[!0-9]*) return 1 ;; esac
  echo "$_hg_up"
}

hg_wait_boot() {
  until [ "$($HG_CMD_GETPROP sys.boot_completed 2>/dev/null)" = 1 ]; do sleep 2; done
}

# Runs a device command with a 10 s limit, so a wedged dumpsys can't stall the watchdog. Prefers
# toybox's /system/bin/timeout: it waits with a signal timer, while busybox's forks a child and a
# grandchild that polls every second and holds the output pipe open until its next poll. Falls
# back to any `timeout`, then to no limit.
hg_timeout() {
  if [ -x "$HG_TIMEOUT" ]; then "$HG_TIMEOUT" 10 "$@"
  elif command -v timeout >/dev/null 2>&1; then timeout 10 "$@"
  else "$@"; fi
}

# stdin: SystemUI's dump (hg_sysui_dump), full or just the LauncherProxyService section.
# The "pkg=" token must be an exact match, not a prefix: "pkg=com.hypergestures.other" is
# NOT ours. index()/substr() avoid building a regex out of HG_PKG (its dots would need
# escaping and dynamic -v regex escaping is not consistent across awk implementations).
hg_provider_connected() {
  awk -v pkg="pkg=$HG_PKG" '
    /LauncherProxyService state:/ { inside = 1; next }
    inside && /^[[:space:]]*[A-Za-z]+ state:/ { inside = 0 }
    inside && /isConnected=true/ { connected = 1 }
    inside {
      p = index($0, pkg)
      if (p) {
        rest = substr($0, p + length(pkg), 1)
        if (rest == "" || rest !~ /[A-Za-z0-9_.]/) ours = 1
      }
    }
    END { exit !(connected && ours) }
  '
}

# stdin: as hg_provider_connected. 0 if SystemUI has any provider bound (ours or MiuiHome).
hg_any_provider_connected() {
  awk '
    /LauncherProxyService state:/ { inside = 1; next }
    inside && /^[[:space:]]*[A-Za-z]+ state:/ { inside = 0 }
    inside && /isConnected=true/ { connected = 1 }
    END { exit !connected }
  '
}

# Asks SystemUI for only its LauncherProxyService dumpable (the full dump is large). If that
# output lacks the section (the dumpable is named differently on some build), falls back to the
# full dump, so the check can never fail for that reason alone. The first dump of a boot records
# its size and branch (hg_sysui_dump_note), so the log shows whether SystemUI honours the dumpable
# name: a section is well under 2 KB, more than 50 KB means SystemUI printed everything anyway.
hg_sysui_dump() {
  _hg_dump="$(hg_timeout $HG_CMD_DUMPSYS activity service "$HG_SYSUI" LauncherProxyService 2>/dev/null)"
  case "$_hg_dump" in
    *"LauncherProxyService state:"*)
      if [ "${#_hg_dump}" -gt 51200 ]; then
        hg_sysui_dump_note "sysui dump: ${#_hg_dump} bytes via the LauncherProxyService dumpable (over 50 KB: SystemUI seems to ignore the dumpable name and print its full dump)"
      else
        hg_sysui_dump_note "sysui dump: ${#_hg_dump} bytes via the LauncherProxyService dumpable (the section alone)"
      fi
      printf '%s\n' "$_hg_dump" ;;
    *) _hg_full="$(hg_timeout $HG_CMD_DUMPSYS activity service "$HG_SYSUI" 2>/dev/null)"
      hg_sysui_dump_note "sysui dump: the LauncherProxyService dumpable printed no section (${#_hg_dump} bytes), so the full dump was used (${#_hg_full} bytes)"
      printf '%s\n' "$_hg_full" ;;
  esac
}

# Logs $1 and keeps it in sysui-dump (action.sh prints it), the first time this boot only
# (service.sh clears the file at boot). A file, not a variable: hg_sysui_dump runs in a pipeline.
hg_sysui_dump_note() {
  [ -f "$HG_DATA/sysui-dump" ] && return 0
  mkdir -p "$HG_DATA" && printf '%s\n' "$1" > "$HG_DATA/sysui-dump"
  hg_log "$1"
}

# The facts the app owns, app -> module (HgHealthFacts): Settings.Global values it writes as
# "<bootId> <pid> <uptimeMs>" (this boot's id, the process that wrote it, ms since boot).
# hypergestures_provider_bound: SystemUI bound TouchInteractionService and handed it its proxy
# (TISBinder.onInitialize); cleared when SystemUI unbinds it (TouchInteractionService.onUnbind and
# onDestroy, also when SystemUI dies). hypergestures_gesture_monitor: the "swipe-up" input monitor
# is registered on the default display (TouchInteractionHandler's InputResource); cleared when it
# is disposed. The bind and monitor checks go by them first (hg_provider_bound,
# hg_monitor_registered): SystemUI's dump and `dumpsys input` are text formats a firmware update
# can change, and one that no longer matches would roll the module back at every boot. The dumps
# stay the fallback (no valid value: an older app, a failed write, the switch
# hypergestures_health_facts=0, hg_facts_on, a process that died since) and run once a process as a
# cross-check, logged once a boot. Settings, not files in the app's dir: root reads nothing there.
HG_FACT_BOUND=hypergestures_provider_bound
HG_FACT_MONITOR=hypergestures_gesture_monitor

# 0 if the app's value of Settings.Global $1 is this boot's and was written by a pid that still is
# our main process (hg_pid_is): the fact holds; HG_FACT_PID = that pid. The app writes the value,
# so it is untrusted input, parsed with builtins only (never evaluated): one line of at most 200
# characters, exactly three fields "<bootId> <pid> <uptimeMs>", the boot id equal to this boot's
# (/proc, builtin read), the pid and the uptime plain numbers. Nothing of it is logged but the
# checked pid. The owner's switch hypergestures_health_facts=0 turns the facts off: read once a
# process (hg_facts_on), it makes every value count as absent, so the dumps alone decide. One
# `settings get` under hg_timeout (2 execs); unset reads as "null" and fails the checks. Returns
# 1 when the fact does not hold, 2 when the `settings get` itself failed or timed out.
hg_app_fact() {
  HG_FACT_PID=""
  hg_facts_on || return 1
  _hg_aqv="$(hg_timeout $HG_CMD_SETTINGS get global "$1" 2>/dev/null)" || return 2
  [ "${#_hg_aqv}" -le 200 ] || return 1
  case "$_hg_aqv" in *"
"*|*" "*" "*" "*) return 1 ;; *" "*" "*) ;; *) return 1 ;; esac
  _hg_aqb="${_hg_aqv%% *}"; _hg_aqp="${_hg_aqv#* }"; _hg_aqu="${_hg_aqp#* }"
  _hg_aqp="${_hg_aqp%% *}"
  case "$_hg_aqp" in ''|0*|*[!0-9]*|??????????*) return 1 ;; esac
  case "$_hg_aqu" in ''|*[!0-9]*) return 1 ;; esac
  _hg_aqi=""
  [ -r "$HG_BOOT_ID_FILE" ] && IFS= read -r _hg_aqi 2>/dev/null < "$HG_BOOT_ID_FILE"
  [ -n "$_hg_aqi" ] && [ "$_hg_aqb" = "$_hg_aqi" ] || return 1
  hg_pid_is "$_hg_aqp" "$HG_PKG" || return 1
  HG_FACT_PID="$_hg_aqp"
}

# 0 unless the owner set hypergestures_health_facts to 0 (HgRecentsSettings.HEALTH_FACTS). Read
# once a process (_hg_hfs; service.sh is one process for the whole boot, so a change applies from
# the next boot), under hg_timeout; unreadable counts as on (the values are then checked as
# usual, and a provider that can't answer fails them anyway). Logs once a boot when off.
hg_facts_on() {
  if [ -z "${_hg_hfs:-}" ]; then
    _hg_hfs="$(hg_timeout $HG_CMD_SETTINGS get global hypergestures_health_facts 2>/dev/null)" \
      || _hg_hfs=""
    if [ "$_hg_hfs" = 0 ]; then
      _hg_hfs=off
      hg_log_once "app facts: hypergestures_health_facts=0, the checks go by the dumps alone" \
        "facts off"
    else _hg_hfs=on; fi
  fi
  [ "$_hg_hfs" = on ]
}

# The cross-check for a hypergestures_provider_bound that counted (HG_FACT_PID set): the first
# time in this process, SystemUI's dump is read too and the log says (once a boot) whether it
# agrees.
hg_provider_xcheck() {
  [ -z "${_hg_xb:-}" ] || return 0
  _hg_xb=1
  if hg_sysui_dump | hg_provider_connected; then _hg_xw="agrees"
  else _hg_xw="does not show it (has its format changed?); going by the app"; fi
  hg_log_once "bind check: the app's $HG_FACT_BOUND (pid $HG_FACT_PID, this boot) says SystemUI bound it; SystemUI's dump $_hg_xw" \
    "fact $HG_FACT_BOUND"
}

# 0 if SystemUI has our provider bound: the app's hypergestures_provider_bound (hg_app_fact,
# cross-checked once, hg_provider_xcheck), else SystemUI's dump (hg_provider_connected).
hg_provider_bound() {
  if hg_app_fact "$HG_FACT_BOUND"; then hg_provider_xcheck; return 0; fi
  hg_sysui_dump | hg_provider_connected
}

# Polls until SystemUI has bound our provider (hg_provider_bound). $1 = seconds to wait at most,
# by the monotonic clock (a slow dumpsys or settings call counts too), checked every second; at
# the deadline one last check runs.
# Never more than $1/5+1 checks 5 s apart either, so a clock that can't be read, or doesn't
# advance, still ends the wait. Between two checks it reads the app's hypergestures_provider_bound
# every second, so the app's value ends the wait at once; once such a read fails or times out
# (a provider that does not answer), only the 5 s checks read it for the rest of this wait. 0 =
# bound.
hg_wait_provider() {
  _hg_left=$(( $1 / 5 + 1 ))
  _hg_deadline=""; _hg_wpoll=1
  _hg_t="$(hg_uptime)" && _hg_deadline=$(( _hg_t + $1 ))
  while :; do
    hg_provider_bound && return 0
    _hg_left=$(( _hg_left - 1 ))
    [ "$_hg_left" -gt 0 ] || return 1
    hg_wait_past && return 1
    for _hg_ws in 1 2 3 4 5; do
      sleep 1
      if [ "$_hg_wpoll" = 1 ]; then
        hg_app_fact "$HG_FACT_BOUND"
        case $? in
          0) hg_provider_xcheck; return 0 ;;
          2) _hg_wpoll=0 ;;
        esac
      fi
      hg_wait_past && break # one last check at the deadline
    done
  done
}

# 0 if hg_wait_provider's deadline (_hg_deadline; empty: none) has passed.
hg_wait_past() {
  [ -n "$_hg_deadline" ] && _hg_t="$(hg_uptime)" && [ "$_hg_t" -ge "$_hg_deadline" ]
}

# Prints a value recorded by hg_record_nav_state ($1 = FSG | NAVBAR); empty if unknown.
hg_recorded() {
  sed -n "s/^$1=//p" "$HG_DATA/original-nav.txt" 2>/dev/null
}

# The navbar overlays `cmd overlay list` shows as enabled ([x]), one per line. Tolerates extra
# spaces and a trailing CR (stripped, so the printed names are exact).
hg_enabled_navbar_overlays() {
  $HG_CMD_OVERLAY list 2>/dev/null | awk -v p="$HG_NAVBAR_PREFIX." '{ sub(/\r$/, "") } $1 == "[x]" && index($2, p) == 1 { print $2 }'
}

# Settings.Secure navigation_mode as SystemUI's NavigationModeController writes it from the navbar
# overlay in force (0 three buttons, 1 two buttons, 2 gestural): the nav mode itself, where the
# overlay names are a format we parse (`cmd overlay list`). Empty if unreadable or anything else.
# Bounded by hg_timeout.
hg_nav_mode() {
  _hg_nmv="$(hg_timeout $HG_CMD_SETTINGS get secure navigation_mode 2>/dev/null)"
  case "$_hg_nmv" in 0|1|2) echo "$_hg_nmv" ;; esac
}

# 0 if a gesture navigation mode is on: navigation_mode 2 (hg_nav_mode) first; else a gestural
# navbar overlay, or Xiaomi's force_fsg_nav_bar (HyperOS's own gestures, which navigation_mode
# need not show). Both need a bound QuickStep provider; without one the phone has no navigation.
# The overlay list stays a fallback here: navigation_mode is written by SystemUI, a moment after
# the overlay changes and not at all while SystemUI is down, and a gesture mode missed here would
# leave the phone without the 3-button fallback. $1 = now: the overlays and force_fsg_nav_bar only
# (right after a restore changed them, navigation_mode may still say the old mode).
hg_gesture_nav_active() {
  [ "${1:-}" != now ] && [ "$(hg_nav_mode)" = 2 ] && return 0
  for _hg_o in $(hg_enabled_navbar_overlays); do
    case "$_hg_o" in "$HG_NAVBAR_PREFIX.gestural"*) return 0 ;; esac
  done
  [ "$($HG_CMD_SETTINGS get global force_fsg_nav_bar 2>/dev/null)" = 1 ]
}

# Navbar overlay back to the recorded original. With none recorded as enabled (HyperOS may enable
# no navbar overlay at all), disables the gestural one and every other navbar overlay enabled now
# (threebutton from the watchdog or fallback, twobutton from nav-mode experiments), so the device
# default applies again. Non-zero if a command failed.
hg_restore_navbar_overlay() {
  _hg_navbar="$(hg_recorded NAVBAR)"
  if [ -n "$_hg_navbar" ]; then
    $HG_CMD_OVERLAY enable-exclusive --category "$_hg_navbar"
    return
  fi
  _hg_nrc=0
  $HG_CMD_OVERLAY disable "$HG_NAVBAR_PREFIX.gestural" || _hg_nrc=1
  for _hg_o in $(hg_enabled_navbar_overlays); do
    [ "$_hg_o" = "$HG_NAVBAR_PREFIX.gestural" ] && continue
    $HG_CMD_OVERLAY disable "$_hg_o" || _hg_nrc=1
  done
  return $_hg_nrc
}

# Switches the navigation mode to $1; 1 (and logged) if a command for it failed.
hg_apply_nav_mode() {
  _hg_nm=0
  case "$1" in
    gestural)
      $HG_CMD_OVERLAY enable-exclusive --category "$HG_NAVBAR_PREFIX.gestural" || _hg_nm=1
      _hg_nmd="gestural" ;;
    threebutton)
      $HG_CMD_SETTINGS put global force_fsg_nav_bar 0 || _hg_nm=1
      $HG_CMD_OVERLAY enable-exclusive --category "$HG_NAVBAR_PREFIX.threebutton" || _hg_nm=1
      _hg_nmd="threebutton" ;;
    fsg)
      # Xiaomi fsg sits on the base navbar: first drop a gestural overlay an earlier boot left.
      hg_restore_navbar_overlay || _hg_nm=1
      $HG_CMD_SETTINGS put global force_fsg_nav_bar 1 || _hg_nm=1
      _hg_nmd="fsg (needs shim to stick with a third-party home)" ;;
    keep|"") hg_log "nav mode: unchanged"; return 0 ;;
    *) hg_log "nav mode: unknown '$1', unchanged"; return 0 ;;
  esac
  if [ "$_hg_nm" = 0 ]; then hg_log "nav mode: $_hg_nmd"; else hg_log "nav mode: $_hg_nmd FAILED (a command failed)"; fi
  return "$_hg_nm"
}

hg_record_nav_state() {
  [ -f "$HG_DATA/original-nav.txt" ] && return 0
  mkdir -p "$HG_DATA"
  fsg="$($HG_CMD_SETTINGS get global force_fsg_nav_bar 2>/dev/null)"
  navbar="$(hg_enabled_navbar_overlays | head -n 1)"
  printf 'FSG=%s\nNAVBAR=%s\n' "$fsg" "$navbar" > "$HG_DATA/original-nav.txt"
  hg_log "recorded original nav state: FSG=$fsg NAVBAR=$navbar"
}

# 0 if navbar overlay $1 draws nothing to navigate with on its own: not a button layout
# (threebutton, twobutton) and not the gesture bar (gestural*), e.g. HyperOS's "transparent". Such
# an overlay only works with Xiaomi's full-screen gestures (force_fsg_nav_bar=1); with 0 the phone
# shows "3 buttons" selected and draws no buttons. Empty (no overlay: the device default) is not.
hg_navbar_needs_fsg() {
  case "$1" in
    ''|"$HG_NAVBAR_PREFIX.threebutton"|"$HG_NAVBAR_PREFIX.twobutton"|"$HG_NAVBAR_PREFIX.gestural"*) return 1 ;;
  esac
  return 0
}

# Nav back to what hg_record_nav_state saw. Nothing recorded = nav was never changed. A missing
# or non-0/1 FSG is left alone. Non-zero if a device command failed.
# The record is taken at setup, when a third-party launcher is HOME, and HyperOS then forces
# force_fsg_nav_bar=0 while keeping its "transparent" navbar overlay (phone, 2026-10-01: FSG=0
# NAVBAR=...transparent). Restored as is with MiuiHome as HOME, that left "3 buttons" selected
# with no buttons drawn (2026-10-06). A recorded overlay that needs FSG (hg_navbar_needs_fsg) is
# therefore restored with force_fsg_nav_bar=1: HyperOS's own full-screen gestures.
hg_restore_nav_state() {
  [ -f "$HG_DATA/original-nav.txt" ] || return 0
  _hg_rc=0
  hg_restore_navbar_overlay || _hg_rc=1
  _hg_fsg="$(hg_recorded FSG)"
  if [ "$_hg_fsg" = 0 ] && hg_navbar_needs_fsg "$(hg_recorded NAVBAR)"; then
    hg_log "restore: recorded FSG=0 with $(hg_recorded NAVBAR) draws no navigation; restoring HyperOS gestures (FSG=1)"
    _hg_fsg=1
  fi
  case "$_hg_fsg" in
    0|1) $HG_CMD_SETTINGS put global force_fsg_nav_bar "$_hg_fsg" || _hg_rc=1 ;;
  esac
  hg_log "restored nav state: FSG=$_hg_fsg NAVBAR=$(hg_recorded NAVBAR) (rc=$_hg_rc)"
  return $_hg_rc
}

# A dormant boot's safety net, every dormant boot: if the navigation is set so that nothing is
# drawn (force_fsg_nav_bar not 1 and the enabled navbar overlay needs it, hg_navbar_needs_fsg),
# turns HyperOS's full-screen gestures on. Repairs a phone an earlier version restored that way.
hg_ensure_visible_nav() {
  _hg_ev_fsg="$($HG_CMD_SETTINGS get global force_fsg_nav_bar 2>/dev/null)"
  [ "$_hg_ev_fsg" = 1 ] && return 0
  _hg_ev_nav="$(hg_enabled_navbar_overlays | head -n 1)"
  hg_navbar_needs_fsg "$_hg_ev_nav" || return 0
  if $HG_CMD_SETTINGS put global force_fsg_nav_bar 1; then
    hg_log "dormant: navigation drew nothing (FSG=${_hg_ev_fsg:-unset}, $_hg_ev_nav); HyperOS gestures turned on (FSG=1)"
    return 0
  fi
  hg_log "dormant: navigation draws nothing (FSG=${_hg_ev_fsg:-unset}, $_hg_ev_nav) and turning HyperOS gestures on FAILED"
  return 1
}

# HyperOS refuses `pm disable-user` for MiuiHome ("Cannot disable miui core packages"), so it is
# denied background running and stopped instead. Never while it is the home app: that kills home.
hg_disable_miuihome() {
  if $HG_CMD_ROLE get-role-holders android.app.role.HOME 2>/dev/null | grep -q '^com\.miui\.home$'; then
    hg_log "MiuiHome is the home app: left running"; return 0
  fi
  if $HG_CMD_APPOPS set com.miui.home RUN_ANY_IN_BACKGROUND ignore && $HG_CMD_AM force-stop com.miui.home; then
    hg_log "MiuiHome stopped and denied background running"; return 0
  fi
  hg_log "MiuiHome stop FAILED"
  return 1
}

# Resets the background restriction, and enables MiuiHome in case a module version that still
# used `pm disable-user` disabled it (a no-op on an enabled package).
hg_enable_miuihome() {
  $HG_CMD_APPOPS set com.miui.home RUN_ANY_IN_BACKGROUND default >/dev/null 2>&1
  if $HG_CMD_PM enable com.miui.home; then hg_log "MiuiHome enabled"; return 0; fi
  hg_log "MiuiHome enable FAILED"
  return 1
}

# Undoes every persistent change but HOME (hg_restore_home): what setup changed
# (hg_restore_setup) and the app's gesture-line overlay (hg_hide_line_off): the undo paths
# (restore.sh, the guard, uninstall.sh, the rollback) mean stock HyperOS. Needs system services,
# i.e. boot completed.
hg_restore_all() {
  _hg_all_rc=0
  hg_restore_setup || _hg_all_rc=1
  hg_hide_line_off
  return $_hg_all_rc
}

# What an active boot's setup changed: MiuiHome and the navigation, and (best effort, never
# failing the restore) our app's battery setting. A dormant boot's restore (hg_dormant_boot) stops
# here: the app's gesture-line overlay is not setup's but the owner's setting, which works
# whatever HOME is.
hg_restore_setup() {
  _hg_set_rc=0
  hg_enable_miuihome || _hg_set_rc=1
  hg_restore_nav_state || _hg_set_rc=1
  hg_power_restore
  return $_hg_set_rc
}

# The app's opt-in "Hide gesture line" (Settings.Global hypergestures_hide_gesture_line) is a
# fabricated overlay the app owns on SystemUI ($HG_HIDE_LINE) that makes the handle's colours
# transparent. It works whatever HOME is (owner, 0.3.9.34), so dormant boots and HOME switches
# leave it to the app, which syncs it with the setting whenever it starts (the gesture service,
# its settings page, BOOT_COMPLETED). Only the undo paths (hg_restore_all) disable it, as stock
# means stock there; root passes OverlayManagerService's actor check. While our APK stays
# installed (every boot of an enabled module: a metamodule's mount, or our own in self mode,
# dormant boots included) OMS keeps it registered, and the app enables it again while the setting
# is on at its next start (gesture service, BOOT_COMPLETED) or the next opening of its settings
# page (so restore.sh, which leaves the module enabled, lasts until then); a boot that does not
# mount the APK (module disabled or removed) has OMS drop it. After a rollback
# a still-running app process may re-sync it: harmless, SystemUI only reads it at its next start
# and the module is disabled by then. SystemUI draws the line again from its next start. One
# `cmd overlay disable`, whatever its state: OMS takes a disable of a disabled overlay as done,
# and refuses one it does not know (exit 1), so no `cmd overlay list` (whose format we would
# parse) is needed. Best effort: always 0; a refused disable is logged.
hg_hide_line_off() {
  if $HG_CMD_OVERLAY disable "$HG_HIDE_LINE" >/dev/null 2>&1; then
    hg_log "gesture line: the app's hiding overlay disabled or already off (visible from SystemUI's next start)"
  else
    hg_log "gesture line: disabling the app's hiding overlay $HG_HIDE_LINE refused (not registered, or OMS said no)"
  fi
  return 0
}

# --- HyperOS battery setting (Greeze) ----------------------------------------------------------
# HyperOS's app freezer (Greeze, `dumpsys greezer`) freezes our app for up to 3 s after every
# broadcast it receives (SCREEN_ON, SCREEN_OFF, PACKAGE_CHANGED): SmartPower moves it from idle
# to background for 3 s and Greeze freezes it then, so a swipe made right after unlocking waits
# in the frozen process (phone logs 2026-10-05). Apps set to Battery saver > "No restrictions"
# are never frozen (Greeze: NO_RESTRICT_APP), and HyperOS shows that option only for user apps.
# PowerKeeper keeps it per package and user in its provider's userTable (bgControl: miuiAuto by
# default, noRestrict, noBg); setup sets ours to noRestrict for user 0. Our app does no work
# while idle, so the exemption costs no battery.
HG_PK_URI=content://com.miui.powerkeeper.configure/userTable

# HG_BGC = our app's bgControl for user 0 ("" if PowerKeeper has no row, or can't be read).
hg_power_get() {
  HG_BGC=""
  _hg_pq="$($HG_CMD_CONTENT query --uri "$HG_PK_URI" --projection bgControl \
    --where "pkgName='$HG_PKG' AND userId=0" 2>/dev/null)"
  case "$_hg_pq" in
    *bgControl=*) HG_BGC="${_hg_pq#*bgControl=}"; HG_BGC="${HG_BGC%%[!A-Za-z]*}" ;;
  esac
}

# Sets our app's bgControl for user 0 to $1. Tries update first (the row usually exists), then
# insert. 1 if it could not be set (read back).
hg_power_set() {
  $HG_CMD_CONTENT update --uri "$HG_PK_URI" --bind "bgControl:s:$1" \
    --where "pkgName='$HG_PKG' AND userId=0" >/dev/null 2>&1
  hg_power_get
  if [ "$HG_BGC" = "$1" ]; then return 0; fi
  $HG_CMD_CONTENT insert --uri "$HG_PK_URI" --bind userId:i:0 --bind "pkgName:s:$HG_PKG" \
    --bind "bgControl:s:$1" >/dev/null 2>&1
  hg_power_get
  [ "$HG_BGC" = "$1" ]
}

# The marker power-pending: set ($1 = 1) or cleared (0). Only a change is published to the app
# (hg_publish_status); the retries that change nothing cost one test.
hg_power_mark() {
  if [ "$1" = 1 ]; then
    [ -f "$HG_DATA/power-pending" ] && return 0
    mkdir -p "$HG_DATA" && : > "$HG_DATA/power-pending"
  else
    [ -f "$HG_DATA/power-pending" ] || return 0
    rm -f "$HG_DATA/power-pending"
  fi
  hg_publish_status
}

# Setup (every active boot, as HyperOS may reset it): our app to "No restrictions". The original
# value is recorded once ("none" when PowerKeeper had no row) for hg_power_restore. A PowerKeeper
# that can't be read yet (HyperOS starts it after boot_completed; phone log 2026-10-05 11:08)
# leaves $HG_DATA/power-pending and hg_power_retry tries again from the health check and the
# watchdog. A lasting unread or failed write keeps the pending marker.
hg_power_exempt() {
  hg_power_get
  if [ "$HG_BGC" = noRestrict ]; then
    hg_power_mark 0
    return 0
  fi
  if [ ! -f "$HG_DATA/original-bgcontrol" ]; then
    if [ -z "$HG_BGC" ] && ! $HG_CMD_CONTENT query --uri "$HG_PK_URI" --projection _id \
         --where "userId=0" 2>/dev/null | grep -q '^Row:'; then
      hg_power_mark 1
      hg_log_once "battery: PowerKeeper's app list can't be read yet; will retry. HyperOS may freeze $HG_PKG briefly after screen-on"
      return 1
    fi
    mkdir -p "$HG_DATA" && echo "${HG_BGC:-none}" > "$HG_DATA/original-bgcontrol"
  fi
  _hg_pwas="${HG_BGC:-none}"
  if hg_power_set noRestrict; then
    hg_power_mark 0
    hg_log "battery: $HG_PKG set to No restrictions (was $_hg_pwas), so HyperOS does not freeze it"
    return 0
  fi
  hg_power_mark 1
  hg_log_once "battery: setting $HG_PKG to No restrictions FAILED; will retry. HyperOS may freeze it briefly after screen-on"
  return 1
}

# Re-applies No restrictions when needed. The health check calls this every tick; the watchdog
# when hg_power_due says so. Not gated on pending alone: HyperOS can reset the row after unlock
# (phone log 2026-10-05 13:38, ChangedNoRestrictAppsList wipe). Each call runs `content`, an
# app_process VM (about 0.3 s of CPU), so the watchdog spaces them out.
hg_power_retry() {
  hg_power_get
  if [ "$HG_BGC" = noRestrict ]; then
    hg_power_mark 0
    return 0
  fi
  hg_power_exempt
}

# 0 if this watchdog round retries No restrictions ($1 = 1 on a bind-check round). Every round for
# the first HG_GESTURAL_WATCH s (covers the first unlock); then at every bind check (a SystemUI or
# our restart, a miss, and every HG_BIND_RECHECK s: catches a later reset by HyperOS for about two
# VM starts an hour); and while power-pending exists, every HG_POWER_RETRY s of watchdog time,
# HG_POWER_TRIES times a boot (a PowerKeeper that never answers, e.g. after an OTA, costs nothing
# more than the bind checks). Builtins only.
hg_power_due() {
  [ "${_hg_wd_elapsed:-0}" -lt "$HG_GESTURAL_WATCH" ] && return 0
  [ "${1:-0}" = 1 ] && return 0
  [ -f "$HG_DATA/power-pending" ] || return 1
  [ "${_hg_pw_tries:-0}" -lt "$HG_POWER_TRIES" ] || return 1
  [ $(( ${_hg_wd_elapsed:-0} - ${_hg_pw_at:-0} )) -ge "$HG_POWER_RETRY" ] || return 1
  _hg_pw_tries=$(( ${_hg_pw_tries:-0} + 1 ))
  return 0
}

# Puts our app's recorded original bgControl back (none: PowerKeeper's default, miuiAuto), then
# forgets it. Best effort: logs a failure, keeps the record for the next restore, returns 0.
hg_power_restore() {
  [ -f "$HG_DATA/original-bgcontrol" ] || return 0
  _hg_po="$(cat "$HG_DATA/original-bgcontrol" 2>/dev/null)"
  case "$_hg_po" in ''|none) _hg_po=miuiAuto ;; esac
  if hg_power_set "$_hg_po"; then
    rm -f "$HG_DATA/original-bgcontrol"; hg_power_mark 0
    hg_log "battery: $HG_PKG back to $_hg_po"
  else
    hg_log "battery: putting $HG_PKG back to $_hg_po FAILED (kept for the next restore)"
  fi
  return 0
}

# HOME back to MiuiHome (the owner set a third-party launcher to use the module; spec D-15). A
# no-op when it already is. Needs system services, i.e. boot completed.
hg_restore_home() {
  if $HG_CMD_ROLE add-role-holder android.app.role.HOME com.miui.home >/dev/null 2>&1; then
    hg_log "HOME set to com.miui.home"; return 0
  fi
  hg_log "HOME -> com.miui.home FAILED"
  return 1
}

hg_boot_id() {
  cat "$HG_BOOT_ID_FILE" 2>/dev/null || awk '/^btime/ { print $2 }' /proc/stat 2>/dev/null
}

# Removes the restore locks of earlier boots ($1 = a lock to keep, e.g. this boot's; none = all).
hg_drop_stale_locks() {
  for _hg_l in "$HG_DATA"/restore.lock.*; do
    [ -d "$_hg_l" ] && [ "$_hg_l" != "${1:-}" ] && rmdir "$_hg_l" 2>/dev/null
  done
  return 0
}

# The deferred undo behind uninstall.sh and the guard: waits for boot_completed, then restores
# MiuiHome, the navigation and HOME (a failure of any of them counts as a failed attempt).
# $1 = mark (module disabled: leave a marker so later boots don't repeat it) or cleanup (module
# gone: delete the state dir and the guard). On the uninstall boot both uninstall.sh's job and
# the guard get here; a lock named after the boot id lets only the first act. It is taken after
# boot, so a caller that died earlier never blocks the other, and a lock left by an earlier boot
# never blocks a later one (it is dropped then). A failed attempt keeps this boot's lock, so the
# other actor does not attempt (and count) a second time: one attempt per boot. On failure
# everything is kept for a retry at the next boot, for HG_RESTORE_TRIES boots; then the owner is
# told to restore by hand (the recorded values go to the log, logcat and the notification), the
# guard idles (marker restore-gave-up), and a module that is gone still has its files cleaned up.
hg_restore_after_boot() {
  hg_wait_boot
  _hg_boot="$(hg_boot_id)"
  _hg_lock="$HG_DATA/restore.lock.${_hg_boot:-unknown}"
  mkdir "$_hg_lock" 2>/dev/null || return 0
  hg_drop_stale_locks "$_hg_lock"
  # Undoing for a module that is disabled or gone: none of the values it and the app exchange
  # stays behind (the owner's hypergestures_* switches do).
  hg_globals_clear
  _hg_rrc=0
  hg_restore_all || _hg_rrc=1
  hg_restore_home || _hg_rrc=1
  if [ "$_hg_rrc" != 0 ]; then
    # Without a boot id the lock name repeats at every boot, so it must not outlive a failure.
    [ -n "$_hg_boot" ] || rmdir "$_hg_lock" 2>/dev/null
    _hg_tries=$(( $(cat "$HG_DATA/restore-attempts" 2>/dev/null || echo 0) + 1 ))
    if [ "$_hg_tries" -lt "$HG_RESTORE_TRIES" ]; then
      echo "$_hg_tries" > "$HG_DATA/restore-attempts"
      hg_log "restore failed (boot $_hg_tries of $HG_RESTORE_TRIES); state kept for a retry at the next boot"
      return 1
    fi
    hg_log "restore failed $_hg_tries boots in a row; manual restore needed: pm enable com.miui.home," \
      "navbar overlay NAVBAR=$(hg_recorded NAVBAR), force_fsg_nav_bar FSG=$(hg_recorded FSG)," \
      "HOME com.miui.home (runbook section 5)"
    hg_notify "HyperGestures could not undo its changes ($_hg_tries boots): manual restore needed. Enable MiuiHome, set it as the home app and set navigation in Settings, or see $HG_DATA/log.txt (logcat tag HyperGestures)."
    if [ "$1" = cleanup ]; then
      rm -f "$HG_GUARD"
      rm -rf "$HG_DATA"
    else
      echo "$_hg_tries" > "$HG_DATA/restore-attempts"
      touch "$HG_DATA/restore-gave-up"
    fi
    return 1
  fi
  rm -f "$HG_DATA/restore-attempts"
  if [ "$1" = cleanup ]; then
    rm -f "$HG_GUARD"
    rm -rf "$HG_DATA"
    $HG_CMD_LOG -t HyperGestures "module gone: restored, removed $HG_DATA and the guard" >/dev/null 2>&1
  else
    touch "$HG_DATA/guard-restored"
    hg_log "guard: module disabled, restored MiuiHome, navigation and HOME"
    rmdir "$_hg_lock" 2>/dev/null
  fi
  return 0
}

# The guard's decision. $1 = module dir, $2 = state dir. Echoes one of:
#   none                 module enabled (its service.sh is in charge), or already restored
#                        while disabled (marker guard-restored), or a failing restore was given
#                        up (marker restore-gave-up)
#   restore              module disabled or marked for removal: undo once
#   restore_and_cleanup  module gone: undo, then delete the state dir and the guard
hg_guard_decide() {
  if [ ! -d "$1" ]; then
    echo restore_and_cleanup
  elif [ -f "$1/disable" ] || [ -f "$1/remove" ]; then
    if [ -f "$2/guard-restored" ] || [ -f "$2/restore-gave-up" ]; then echo none; else echo restore; fi
  else
    echo none
  fi
}

# Body of the guard script. $1 = module dir. After a boot-loop trip (hg_boot_guard wrote disable
# and the marker bootloop) the guard's restore is the rollback, and it tells the owner why.
hg_guard_run() {
  case "$(hg_guard_decide "$1" "$HG_DATA")" in
    restore)
      hg_restore_after_boot mark; _hg_grc=$?
      hg_bootloop_claim && hg_bootloop_notice "$1" "$_hg_grc"
      hg_rollback_reboot_notice ;;
    restore_and_cleanup) hg_restore_after_boot cleanup ;;
  esac
}

# Once after hg_after_rollback rebooted the phone (marker rollback-reboot; rollback-reboot-told
# records the notice): tells the owner why it rebooted.
hg_rollback_reboot_notice() {
  [ -f "$HG_DATA/rollback-reboot" ] && [ ! -f "$HG_DATA/rollback-reboot-told" ] || return 0
  _hg_rrw="$(cat "$HG_DATA/rollback-reboot" 2>/dev/null)"
  : > "$HG_DATA/rollback-reboot-told"
  hg_log "guard: told the owner about the reboot after the rollback ($_hg_rrw)"
  hg_notify "HyperGestures rolled back and then restarted the phone, because ${_hg_rrw:-SystemUI crashed again after the rollback}. The phone is back to stock and the module is disabled; re-enable it in KernelSU after fixing. See $HG_DATA/log.txt."
}

# Installs the disable-safe guard ($HG_GUARD) and the copy of this file it sources: KernelSU runs
# /data/adb/service.d/* at every boot whatever this module's state. Temp file + rename, so a
# guard (or copy) that is being read right now is never rewritten underneath its reader.
hg_install_guard() { # $1 = module dir
  mkdir -p "$HG_DATA" "${HG_GUARD%/*}" &&
    cp "$1/common/functions.sh" "$HG_DATA/.functions.sh.tmp" &&
    mv -f "$HG_DATA/.functions.sh.tmp" "$HG_DATA/functions.sh" &&
    cp "$1/common/hypergestures-guard.sh" "$HG_DATA/.guard.tmp" &&
    chmod 755 "$HG_DATA/.guard.tmp" &&
    mv -f "$HG_DATA/.guard.tmp" "$HG_GUARD" && return 0
  hg_log "guard install FAILED"
  return 1
}

# Posts a notification ($1 = text; $2 = its tag, a plain word, default hypergestures: each notice
# replaces the previous one with the same tag).
# First through our app's HgNotifyReceiver, which posts it as com.hypergestures on the app's own
# "HyperGestures" channel: HyperOS hides what `cmd notification post` posts (it is attributed to
# com.android.shell). The receiver answers result code 1 only once it has posted, so anything else
# (the app not installed, e.g. after the module disabled itself; its notifications off; am failing
# or hanging) falls back to `cmd notification post`, as root, then as the shell uid. Never fails.
hg_notify() {
  _hg_ntag="${2:-hypergestures}"
  hg_notify_app "$1" "$_hg_ntag" && return 0
  $HG_CMD_NOTIFY -S bigtext -t HyperGestures "$_hg_ntag" "$1" >/dev/null 2>&1 && return 0
  if command -v $HG_CMD_SU >/dev/null 2>&1; then
    # The text goes into a single-quoted word of the -c string: each ' becomes '\''.
    _hg_q="$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"
    $HG_CMD_SU 2000 -c "$HG_CMD_NOTIFY -S bigtext -t HyperGestures $_hg_ntag '$_hg_q'" >/dev/null 2>&1
  fi
  return 0
}

# The broadcast to HgNotifyReceiver ($1 = text, $2 = tag). Run as root, which passes the receiver's
# android.permission.DUMP check; the text is one argv word, so am takes it verbatim (quotes,
# newlines). Ordered, as `am broadcast` always sends: it waits for the receiver and prints
# "Broadcast completed: result=N". --include-stopped-packages: a force-stop leaves the package
# stopped until SystemUI rebinds it; --receiver-foreground: the foreground queue, short timeout.
hg_notify_app() {
  hg_timeout $HG_CMD_AM broadcast --receiver-foreground --include-stopped-packages \
      -a "$HG_NOTIFY_ACTION" -n "$HG_NOTIFY_RECEIVER" --es text "$1" --es tag "${2:-hypergestures}" 2>/dev/null |
    awk '{ sub(/\r$/, "") } /^Broadcast completed: result=1(,|$)/ { posted = 1 } END { exit !posted }'
}

# Our app may post notifications (runtime permission POST_NOTIFICATIONS, which a priv-app is not
# granted by default). Once per boot from service.sh; fails quietly (e.g. the app not mounted).
hg_grant_notifications() {
  $HG_CMD_PM grant "$HG_PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
}

# The HyperOS build the module was made for ($1 = module dir; its expected-fingerprint, from
# tools/framework-stub/device-build.txt), and the one this phone runs.
hg_expected_fingerprint() { tr -d '\r\n' 2>/dev/null < "$1/expected-fingerprint"; }
hg_phone_fingerprint() { $HG_CMD_GETPROP ro.build.fingerprint 2>/dev/null; }

# Why this phone's build is not the one the module was made for; prints nothing if it is ($1 =
# module dir). The APK's hidden-API calls, inlined device resource values and AIDL transaction
# tables come from the build in expected-fingerprint; on any other build they may be wrong. By
# default (STRICT_FIRMWARE=0) that is a warning and the health check decides; STRICT_FIRMWARE=1
# refuses instead, unless IGNORE_FINGERPRINT=1 (deliberate testing only).
hg_firmware_problem() {
  _hg_want="$(hg_expected_fingerprint "$1")"
  if [ -z "$_hg_want" ]; then
    echo "firmware check impossible: the module has no expected-fingerprint"
    return 0
  fi
  _hg_have="$(hg_phone_fingerprint)"
  [ "$_hg_want" = "$_hg_have" ] && return 0
  echo "firmware mismatch: built for $_hg_want, this phone runs $_hg_have"
}

# action.sh's firmware line ($1 = module dir): whether the build really matches, and what
# service.sh does about a mismatch under the firmware policy.
hg_firmware_status() {
  _hg_problem="$(hg_firmware_problem "$1")"
  if [ -z "$_hg_problem" ]; then
    echo "match: yes"
  elif [ "${STRICT_FIRMWARE:-0}" != 1 ]; then
    echo "match: NO (service.sh sets up anyway and the health check rolls back if anything is wrong; runbook section 9) - $_hg_problem"
  elif [ "${IGNORE_FINGERPRINT:-0}" = 1 ]; then
    echo "match: NO (ignored: IGNORE_FINGERPRINT=1, so service.sh sets up anyway) - $_hg_problem"
  else
    echo "match: NO (STRICT_FIRMWARE=1: service.sh disables the module at boot; runbook section 9) - $_hg_problem"
  fi
}

# action.sh's firmware policy line.
hg_firmware_policy() {
  if [ "${STRICT_FIRMWARE:-0}" = 1 ]; then
    if [ "${IGNORE_FINGERPRINT:-0}" = 1 ]; then
      echo "policy: strict (STRICT_FIRMWARE=1), overridden by IGNORE_FINGERPRINT=1: sets up on any build"
    else
      echo "policy: strict (STRICT_FIRMWARE=1): refuses to set up on another build and disables itself"
    fi
  else
    echo "policy: health check (STRICT_FIRMWARE=0): sets up on any build; rolls back and disables itself if unhealthy"
  fi
}

# The first sentence of a firmware notification ($1 = module dir).
hg_firmware_head() {
  _hg_want="$(hg_expected_fingerprint "$1")"
  if [ -n "$_hg_want" ]; then
    echo "Firmware changed ($_hg_want → $(hg_phone_fingerprint))."
  else
    echo "Firmware check impossible (the module has no expected-fingerprint)."
  fi
}

# STRICT_FIRMWARE=1 only: the phone runs a build the module was not made for ($1 = module dir,
# $2 = hg_firmware_problem's why), typically after an OTA with the module still enabled. The APK
# may be wrong for this build. post-fs-data left the recents overlay unmounted for the same reason
# (so SystemUI normally binds MiuiHome's recents), but whatever is bound is not trusted. Disables
# the module from the next boot (a `disable` file: then neither the APK nor the overlay is
# mounted, and the guard restores MiuiHome, the
# recorded navigation and HOME), and for the rest of this boot moves a gesture navigation mode to
# 3 buttons, after recording the nav state it replaces. The owner re-enables the module once it is
# rebuilt for this build, or to test anyway with IGNORE_FINGERPRINT=1 (or STRICT_FIRMWARE=0).
hg_firmware_refuse() {
  _hg_head="$(hg_firmware_head "$1")"
  _hg_nav=""; _hg_navlog=""
  if hg_gesture_nav_active; then
    hg_record_nav_state
    if hg_apply_nav_mode threebutton; then
      _hg_nav=" Navigation was switched to 3 buttons for now."; _hg_navlog="; 3-button navigation until then"
    else
      _hg_nav=" Switching navigation to 3 buttons FAILED."; _hg_navlog="; switching to 3-button navigation FAILED"
    fi
  fi
  if touch "$1/disable" 2>/dev/null && [ -f "$1/disable" ]; then
    hg_log "$2; disabled the module from the next boot (the guard then restores MiuiHome and navigation)$_hg_navlog"
    hg_notify "$_hg_head HyperGestures disabled itself; reboot to return to stock. Rebuild for the new firmware, or set IGNORE_FINGERPRINT=1 to test anyway.$_hg_nav"
  else
    hg_log "$2; could not disable the module ($1/disable not written): disable it in the KernelSU Manager app and reboot"
    hg_notify "$_hg_head HyperGestures could not disable itself: disable it in the KernelSU Manager app and reboot to return to stock.$_hg_nav See $HG_DATA/log.txt"
  fi
}

# Setup did not happen ($1 = why). Changes nothing, except: when a gesture navigation mode is on
# while SystemUI has no provider bound at all (neither ours nor MiuiHome), the phone has no
# navigation, so switch to 3-button navigation, as the watchdog would. The nav state it replaces
# is recorded first, so restore.sh, the guard and uninstall.sh put it back (and a later bound boot
# does not take 3-button for the original). MiuiHome is left alone.
hg_not_set_up() {
  if hg_gesture_nav_active && ! hg_sysui_dump | hg_any_provider_connected; then
    hg_log "$1; no provider bound and a gesture nav mode is on: MiuiHome left alone, falling back to 3-button"
    hg_record_nav_state
    # A later dormant boot must put the recorded navigation back (hg_dormant_boot).
    rm -f "$HG_DATA/dormant-restored"
    if hg_apply_nav_mode threebutton; then _hg_nav="navigation was switched to 3 buttons"
    else _hg_nav="switching navigation to 3 buttons FAILED"; fi
    hg_notify "HyperGestures is not active ($1). No gesture provider is bound, so $_hg_nav. See $HG_DATA/log.txt"
  else
    hg_log "$1; nothing changed"
    hg_notify "HyperGestures is not active ($1), so nothing was changed. See $HG_DATA/log.txt"
  fi
}

# SystemUI (NavigationModeControllerInjector) can switch the gestural overlay off on its own; seen
# once, ~10 s after HOME changed to a third-party launcher. True if NAV_MODE wants it and it's off.
# Never after restore.sh ran this boot (marker restored): the owner asked for the original nav.
# navigation_mode first (hg_nav_mode): 2 is gestural, so nothing to re-apply, without listing the
# overlays. 0 or 1 is confirmed by the overlay list (SystemUI writes the setting a moment after
# the overlay changes, so right after our enable it may still say 0); unreadable: the list alone.
hg_gestural_reverted() {
  [ "${NAV_MODE:-}" = gestural ] || return 1
  [ -f "$HG_DATA/restored" ] && return 1
  [ "$(hg_nav_mode)" = 2 ] && return 1
  ! hg_enabled_navbar_overlays | grep -qx "$HG_NAVBAR_PREFIX.gestural"
}

# Re-applies gestural at most 3 times per boot (count in gestural-reapplies, cleared at boot), so
# the watchdog never plays tug-of-war with SystemUI or the owner; then logs and notifies once.
hg_reapply_gestural() {
  _hg_re="$(cat "$HG_DATA/gestural-reapplies" 2>/dev/null)"
  case "$_hg_re" in ''|*[!0-9]*) _hg_re=0 ;; esac
  if [ "$_hg_re" -lt 3 ]; then
    hg_apply_nav_mode gestural >/dev/null 2>&1
    echo $(( _hg_re + 1 )) > "$HG_DATA/gestural-reapplies"
    hg_log "watchdog: gestural was reverted (by SystemUI?), re-applied"
  elif [ "$_hg_re" = 3 ]; then
    echo 4 > "$HG_DATA/gestural-reapplies"
    hg_log "watchdog: gestural reverted 3 times this boot, leaving navigation as it is"
    hg_notify "HyperGestures: navigation keeps switching away from gestures; leaving it. See $HG_DATA/log.txt"
  fi
}

# --- watchdog ---------------------------------------------------------------------------------
# The watchdog's state lives in shell variables of the service.sh process (hg_watchdog_loop runs
# in that process, never in a subshell, so they last from tick to tick): _hg_wd_miss (unbound
# checks in a row), _hg_wd_spid and _hg_wd_opid (the pids of SystemUI and of our process at the
# last bound check; empty = unknown), _hg_wd_since (watchdog seconds since that check) and
# _hg_wd_elapsed (watchdog seconds since the loop started) and _hg_wd_heals (hg_ksu_heal's
# restores this boot). Watchdog seconds are the loop's own
# sleeps: awake time, never time in suspend.
hg_watchdog_init() {
  _hg_wd_miss=0; _hg_wd_spid=""; _hg_wd_opid=""; _hg_wd_since=0; _hg_wd_elapsed=0; HG_WD=0
  _hg_wd_heals=0
  _hg_pw_at=0; _hg_pw_tries=0
}

# 0 if pid $1 still is the process named $2: /proc/$1/cmdline starts with that name (read with a
# builtin; a pid that was reused by another process does not count). Empty $1: 1.
hg_pid_is() {
  [ -n "$1" ] && [ -r "$HG_PROC/$1/cmdline" ] || return 1
  _hg_pcl=""
  IFS= read -r _hg_pcl 2>/dev/null < "$HG_PROC/$1/cmdline"
  case "$_hg_pcl" in "$2"|"$2"[!A-Za-z0-9_.:]*) return 0 ;; esac
  return 1
}

# The first pid pidof prints for process name $1, in HG_PID ("" if none).
hg_pid_of() {
  set -- $($HG_CMD_PIDOF "$1" 2>/dev/null)
  HG_PID="${1:-}"
}

# 0 (and logs why, once) if there is nothing left to guard ($1 = module dir): restore.sh ran this
# boot (marker restored), the owner disabled the module or marked it for removal, or set
# WATCHDOG=0 (setconf.sh leaves the marker watchdog-stop). Builtin tests only.
hg_watchdog_idle() {
  if [ -f "$HG_DATA/restored" ]; then _hg_idle="restore.sh ran"
  elif [ -f "$1/disable" ]; then _hg_idle="the module is disabled"
  elif [ -f "$1/remove" ]; then _hg_idle="the module is marked for removal"
  elif [ -f "$HG_DATA/watchdog-stop" ]; then _hg_idle="WATCHDOG=0 was set"
  else return 1; fi
  hg_log "watchdog: stopped ($_hg_idle)"
  return 0
}

# One watchdog tick ($1 = module dir, default HG_MODDIR). Sets HG_WD to 0 (bound, or nothing
# happened that could unbind it), the number of unbound checks in a row, "fallback" (3 of them:
# 3-button navigation), "rollback" (a crash loop, hg_crash_scan: hg_rollback ran) or "stop"
# (hg_watchdog_idle). SystemUI unbinds our provider only when one of the two processes dies or the
# recents component changes (a package update, which also restarts our process), so the bind is
# checked only when one of the two pids no longer is the process it was, after a miss, and every
# HG_BIND_RECHECK s as a safety net; the rest of the time a tick costs no fork (see the header).
# A SystemUI revert of gestural is watched at every tick for the first HG_GESTURAL_WATCH s, and
# at every bind check after that (a SystemUI restart can bring it back).
hg_watchdog_step() {
  _hg_wmd="${1:-$HG_MODDIR}"
  if hg_watchdog_idle "$_hg_wmd"; then HG_WD=stop; return 0; fi
  hg_mirror_sync
  hg_home_watch
  # Battery setting: the early rounds and a pending retry here (hg_power_due); the bind-check
  # rounds retry below. A quiet round otherwise stays free of PowerKeeper calls.
  if hg_power_due 0; then _hg_pw_at="${_hg_wd_elapsed:-0}"; hg_power_retry >/dev/null 2>&1; fi
  hg_ksu_heal
  if hg_crash_scan "within $(( HG_CRASH_WINDOW / 60 )) minutes"; then
    hg_rollback "$HG_CRASH_WHY" "$_hg_wmd" >/dev/null 2>&1
    HG_WD=rollback; return 0
  fi
  if [ "${_hg_wd_miss:-0}" = 0 ] && [ "${_hg_wd_since:-0}" -lt "$HG_BIND_RECHECK" ] &&
     hg_pid_is "${_hg_wd_spid:-}" "$HG_SYSUI_PKG" && hg_pid_is "${_hg_wd_opid:-}" "$HG_PKG"; then
    if [ "${_hg_wd_elapsed:-0}" -lt "$HG_GESTURAL_WATCH" ] && hg_gestural_reverted; then
      hg_reapply_gestural >/dev/null 2>&1
    fi
    HG_WD=0; return 0
  fi
  if [ "${_hg_wd_elapsed:-0}" -ge "$HG_GESTURAL_WATCH" ] && [ "${_hg_pw_at:-0}" != "${_hg_wd_elapsed:-0}" ] \
     && hg_power_due 1; then
    _hg_pw_at="${_hg_wd_elapsed:-0}"; hg_power_retry >/dev/null 2>&1
  fi
  if hg_provider_bound; then
    hg_gestural_reverted && hg_reapply_gestural >/dev/null 2>&1
    hg_pid_of "$HG_SYSUI_PKG"; _hg_wd_spid="$HG_PID"
    hg_pid_of "$HG_PKG"; _hg_wd_opid="$HG_PID"
    if [ -z "$_hg_wd_spid" ] || [ -z "$_hg_wd_opid" ]; then
      hg_log_once "watchdog: pidof printed nothing for $HG_SYSUI_PKG or $HG_PKG; the bind is checked at every tick"
    fi
    _hg_wd_since=0; _hg_wd_miss=0; HG_WD=0; return 0
  fi
  _hg_wd_spid=""; _hg_wd_miss=$(( ${_hg_wd_miss:-0} + 1 ))
  if [ "$_hg_wd_miss" -ge 3 ]; then
    hg_record_nav_state >/dev/null 2>&1 # already recorded at setup; never fall back unrecorded
    if hg_apply_nav_mode threebutton >/dev/null 2>&1; then _hg_nav="Switched to 3-button navigation"
    else _hg_nav="Switching to 3-button navigation FAILED"; fi
    hg_notify "Gesture provider is not bound. $_hg_nav. See $HG_DATA/log.txt"
    hg_log "watchdog: provider not bound after $_hg_wd_miss checks, fell back to 3-button"
    HG_WD=fallback; return 0
  fi
  HG_WD="$_hg_wd_miss"
}

# Our process is gone or another one since the last bound check (it died, crashed or was
# restarted): KernelSU may have reset our App Profile on the way (removing a package update does;
# owner's phone, 2026-10-09), and then our process no longer sees its APK and crash-loops until a
# rollback. Puts the profile back first (hg_ksu_keep_mounts; a no-op that returns 1 when it is
# still set), restarts our package so SystemUI's next bind starts a process that sees the mounts,
# and restarts the crash watch so those crashes are not counted. Forks only when our pid changed.
# At most HG_KSU_HEAL_MAX times a boot (_hg_wd_heals, reset by hg_watchdog_init): a profile that
# keeps being reset must not keep resetting the crash watch, so after that it logs once and leaves
# the crashes to the crash watch.
hg_ksu_heal() {
  [ -n "${_hg_wd_opid:-}" ] || return 1
  hg_pid_is "$_hg_wd_opid" "$HG_PKG" && return 1
  if [ "${_hg_wd_heals:-0}" -ge "$HG_KSU_HEAL_MAX" ]; then
    hg_log_once "watchdog: the KernelSU profile of $HG_PKG was restored $HG_KSU_HEAL_MAX times this boot; not again, its crashes are left to the crash watch"
    return 1
  fi
  hg_ksu_keep_mounts || return 1
  _hg_wd_heals=$(( ${_hg_wd_heals:-0} + 1 ))
  $HG_CMD_AM force-stop "$HG_PKG" >/dev/null 2>&1
  hg_crash_reset
  _hg_wd_opid=""
  hg_log "watchdog: the KernelSU profile of $HG_PKG had been reset (it would not see its APK); restored it and restarted $HG_PKG"
  return 0
}

# Ticks every 120 s while the provider is bound, every 30 s after a miss, and, while bound, every
# HG_HEALTH_TICK s for HG_CRASH_WINDOW s of watchdog time after a crash of SystemUI or ours
# (_hg_cr_fast, set by hg_crash_add), so a second crash is acted on before SystemUI's own crash
# recovery escalates. The crash scan is builtins only; within the first HG_GESTURAL_WATCH s a
# tick also forks for the gestural check, so a crash then costs up to 40 of those. A miss keeps
# its 30 s interval, so a slow rebind after a crash still gets about 60 s before the 3-button
# fallback. 3 misses in a row
# switch to 3-button navigation and end the watchdog, and so do a crash-loop rollback and
# hg_watchdog_idle. $1 = module dir. The crash watch's reference must be set (hg_crash_reset,
# done at setup); the fallback crash counter ages with the loop's sleeps (_hg_cr_age).
hg_watchdog_loop() {
  hg_watchdog_init
  while :; do
    hg_watchdog_step "${1:-}"
    case "$HG_WD" in fallback|rollback|stop) return 0 ;; esac
    if [ "$HG_WD" = 0 ]; then _hg_ws=120; else _hg_ws=30; fi
    [ "$HG_WD" = 0 ] && [ "${_hg_cr_fast:-0}" -gt 0 ] && _hg_ws="$HG_HEALTH_TICK"
    sleep "$_hg_ws"
    _hg_wd_since=$(( _hg_wd_since + _hg_ws )); _hg_wd_elapsed=$(( _hg_wd_elapsed + _hg_ws ))
    _hg_cr_age=$(( ${_hg_cr_age:-0} + _hg_ws ))
    _hg_cr_fast=$(( ${_hg_cr_fast:-0} - _hg_ws )); [ "$_hg_cr_fast" -gt 0 ] || _hg_cr_fast=0
  done
}

# --- HOME follows: dormant boots -------------------------------------------------------------
# Prints the holder of android.app.role.HOME in roles.xml $1, as RoleService writes it
# (<role name="android.app.role.HOME"><holder name="PKG" /></role>, indented or on one line, CR
# tolerated); nothing for a missing file, an empty or missing role, or anything unparsable (e.g.
# binary XML). sed only: the first splits the file before every "<", the second keeps a flag in
# its hold space while inside the HOME role.
hg_home_holder() {
  sed 's/</\
</g' "$1" 2>/dev/null | sed -n '
/^<role[[:space:]]/{
  x
  s/.*//
  x
  /name="android\.app\.role\.HOME"/!b
  /\/[[:space:]]*>/b
  x
  s/.*/home/
  x
  b
}
/^<\/role/{
  x
  s/.*//
  x
  b
}
/^<holder[[:space:]]/{
  x
  /^home$/!{
    x
    b
  }
  x
  s/^<holder[[:space:]][^>]*name="\([^"]*\)".*/\1/p
  q
}'
}

# HG_ROLES = the roles.xml to read: HG_ROLES_XML if set, else the first of HG_ROLES_PATHS that
# exists (the permission module's path since Android 11, then the old one). 1 if none.
hg_roles_path() {
  HG_ROLES=""
  if [ -n "$HG_ROLES_XML" ]; then
    [ -f "$HG_ROLES_XML" ] && HG_ROLES="$HG_ROLES_XML"
  else
    for _hg_rp in $HG_ROLES_PATHS; do [ -f "$_hg_rp" ] && { HG_ROLES="$_hg_rp"; break; }; done
  fi
  [ -n "$HG_ROLES" ]
}

# Prints the HOME holder as RoleService answers `cmd role get-role-holders` (holders joined by
# ";": the first), or nothing: no holder, a failed or timed-out command (bounded by hg_timeout),
# or anything that is not a package name. Needs system_server: service.sh, action.sh. Returns 1
# only if the command itself failed.
hg_home_role() {
  _hg_hr="$(hg_timeout $HG_CMD_ROLE get-role-holders --user 0 android.app.role.HOME 2>/dev/null)" \
    || return 1
  _hg_hr="${_hg_hr%%;*}"
  case "$_hg_hr" in ''|*[!A-Za-z0-9_.]*) return 0 ;; esac
  echo "$_hg_hr"
}

# HG_HOME = the HOME holder ("" if unknown) and HG_HOME_STATE = dormant (it is MiuiHome), active
# (another launcher) or unknown (no holder known: the module stays active). roles.xml first. When
# it names none (missing, a format we can't parse), `cmd role`'s answer as the last service.sh
# saw it (home-role, hg_home_crosscheck), so a changed roles.xml format does not leave every boot
# "unknown, active" for good; with $1 = live (system_server up: the watchdog, action.sh) `cmd role`
# is asked now, and home-role kept in step. HG_HOME_SRC = roles.xml, cmd role, home-role or "".
hg_home_state() {
  HG_HOME=""; HG_HOME_STATE=unknown; HG_HOME_SRC=""
  if hg_roles_path; then
    HG_HOME="$(hg_home_holder "$HG_ROLES")"
    case "$HG_HOME" in ''|*[!A-Za-z0-9_.]*) HG_HOME="" ;; *) HG_HOME_SRC=roles.xml ;; esac
  fi
  if [ -z "$HG_HOME" ] && [ "${1:-}" = live ]; then
    if _hg_hl="$(hg_home_role)"; then
      hg_home_role_save "$_hg_hl"; HG_HOME="$_hg_hl"; [ -n "$HG_HOME" ] && HG_HOME_SRC="cmd role"
    fi
  fi
  if [ -z "$HG_HOME" ] && [ -f "$HG_DATA/home-role" ]; then
    IFS= read -r HG_HOME < "$HG_DATA/home-role" 2>/dev/null
    case "$HG_HOME" in ''|*[!A-Za-z0-9_.]*) HG_HOME="" ;; *) HG_HOME_SRC=home-role ;; esac
  fi
  [ -n "$HG_HOME" ] || return 0
  if [ "$HG_HOME" = "$HG_MIUIHOME" ]; then HG_HOME_STATE=dormant; else HG_HOME_STATE=active; fi
}

# Records `cmd role`'s HOME holder $1 in home-role for hg_home_state ("": none, the file goes).
hg_home_role_save() {
  if [ -z "$1" ]; then rm -f "$HG_DATA/home-role"; return 0; fi
  [ "$(cat "$HG_DATA/home-role" 2>/dev/null)" = "$1" ] && return 0
  echo "$1" > "$HG_DATA/home-role.tmp" 2>/dev/null && mv -f "$HG_DATA/home-role.tmp" "$HG_DATA/home-role"
  return 0
}

# Once per boot (service.sh, after boot_completed): roles.xml's HOME holder against `cmd role`'s.
# A mismatch is logged (roles.xml still decides: RoleService writes it a moment after a change);
# roles.xml naming none while `cmd role` names one is logged too, and from then on hg_home_state
# goes by `cmd role` (home-role) until roles.xml can be read again. A failed `cmd role` changes
# nothing. One `cmd role` per boot.
hg_home_crosscheck() {
  _hg_hx=""
  hg_roles_path && _hg_hx="$(hg_home_holder "$HG_ROLES")"
  case "$_hg_hx" in *[!A-Za-z0-9_.]*) _hg_hx="" ;; esac
  _hg_hc="$(hg_home_role)" || { hg_log "HOME check: cmd role failed; roles.xml says ${_hg_hx:-nothing}"; return 0; }
  hg_home_role_save "$_hg_hc"
  if [ -z "$_hg_hx" ] && [ -n "$_hg_hc" ]; then
    hg_log "HOME check: roles.xml (${HG_ROLES:-not found}) names no HOME holder, cmd role says $_hg_hc: the module goes by cmd role while roles.xml can't be read"
  elif [ -n "$_hg_hx" ] && [ "$_hg_hx" != "$_hg_hc" ]; then
    hg_log "HOME check: roles.xml says $_hg_hx but cmd role says ${_hg_hc:-nothing}; roles.xml decides"
  fi
  return 0
}

# Starts the runtime HOME watch (service.sh, before the watchdog): this boot's state (the marker
# dormant from post-fs-data), and home-mark with roles.xml's mtime, so hg_home_watch's `-nt` sees
# only later writes. Checks once right away, for a change since post-fs-data.
hg_home_init() {
  if [ -f "$HG_DATA/dormant" ]; then _hg_home_boot=dormant; else _hg_home_boot=active; fi
  _hg_home_told=""; _hg_roles=""
  # Without a roles.xml to watch: one check now all the same (cmd role, hg_home_state live).
  if hg_roles_path && touch -r "$HG_ROLES" "$HG_DATA/home-mark" 2>/dev/null; then
    _hg_roles="$HG_ROLES"
  fi
  hg_home_state live; hg_home_react
}

# One watchdog round's HOME check: builtins only, unless roles.xml was written since home-mark
# (a HOME change, or RoleService saving another role): then re-read the holder.
hg_home_watch() {
  [ -n "${_hg_roles:-}" ] && [ "$_hg_roles" -nt "$HG_DATA/home-mark" ] || return 0
  touch -r "$_hg_roles" "$HG_DATA/home-mark" 2>/dev/null
  hg_home_state live; hg_home_react
}

# After hg_home_state: if HOME crossed the boundary against this boot (an active boot and HOME is
# now MiuiHome, or a dormant boot and HOME is now another launcher), asks for a reboot, once per
# change (back across the boundary re-arms it). Nothing else changes: the app's gesture-line
# overlay follows its setting with any HOME. While active with MiuiHome as HOME, its gesture
# engine fights ours until the reboot.
hg_home_react() {
  case "$HG_HOME_STATE" in unknown) return 0 ;; esac
  if [ "$HG_HOME_STATE" = "${_hg_home_boot:-active}" ]; then _hg_home_told=""; return 0; fi
  [ "${_hg_home_told:-}" = "$HG_HOME_STATE" ] && return 0
  _hg_home_told="$HG_HOME_STATE"
  if [ "$HG_HOME_STATE" = dormant ]; then
    _hg_to=MiuiHome
  else
    _hg_to="the new launcher ($HG_HOME)"
  fi
  hg_log "HOME is now $HG_HOME: reboot to switch gestures to $_hg_to"
  hg_notify "HyperGestures: reboot to switch gestures to $_hg_to"
}

# service.sh on a dormant boot ($1 = module dir): sets nothing up. Once after an active boot (marker
# dormant-restored, removed again by the next active setup) it puts back what setup changed
# (hg_restore_setup): MiuiHome's background restriction and the recorded navigation (a gesture
# mode is fine: MiuiHome is SystemUI's provider again, our overlay is not mounted). Unlike
# restore.sh it leaves the app's gesture-line overlay to the app's setting, which works with any
# HOME. Later dormant boots leave the owner's settings alone. With WATCHDOG=1 the watchdog then
# only watches HOME.
hg_dormant_boot() {
  hg_log "dormant boot: HOME is $HG_MIUIHOME, so nothing is set up (stock HyperOS gestures and recents)"
  if [ ! -f "$HG_DATA/dormant-restored" ]; then
    if hg_restore_setup; then
      touch "$HG_DATA/dormant-restored"; hg_log "dormant: MiuiHome and the recorded navigation restored"
    else
      hg_log "dormant: restoring MiuiHome or the navigation FAILED; tried again at the next dormant boot"
    fi
  fi
  hg_ensure_visible_nav
  hg_health_status dormant "not run: dormant: HOME is $HG_MIUIHOME (choose another launcher and reboot to use the module)"
  [ "${WATCHDOG:-1}" = 1 ] || return 0
  hg_home_init
  hg_dormant_loop "$1"
}

# The dormant boot's watchdog: every 120 s, the idle markers and hg_home_watch (builtins only).
hg_dormant_loop() {
  while :; do
    hg_watchdog_idle "$1" && return 0
    hg_mirror_sync
    hg_home_watch
    sleep 120
  done
}

# --- health check, rollback, boot-loop protection -------------------------------------------
# Logs $1 the first time this boot only (list in log-once, cleared at boot): checks run every 15 s,
# then at every watchdog tick, and a lasting problem must not flood the log. $2 = the key to
# remember instead of the message (for a message that varies). Builtins only, apart from the log.
hg_log_once() {
  _hg_lk="${2:-$1}"
  if [ -f "$HG_DATA/log-once" ]; then
    while IFS= read -r _hg_ll; do [ "$_hg_ll" = "$_hg_lk" ] && return 0; done < "$HG_DATA/log-once"
  fi
  mkdir -p "$HG_DATA" && printf '%s\n' "$_hg_lk" >> "$HG_DATA/log-once"
  hg_log "$1"
}

# --- crash watch -------------------------------------------------------------------------------
# A crash loop is 2 dropbox crash entries (HG_CRASH_TAGS) of SystemUI, or 2 of our package, no
# more than HG_CRASH_WINDOW s apart, both newer than the reference set by hg_crash_reset (at
# boot_completed, and again at setup), so crashes from before it never count.
#
# DropBoxManagerService keeps each entry in a file $HG_DROPBOX/TAG@<ms>.<ext> (ext txt, txt.gz,
# dat, dat.gz; lost once the content was dropped), <ms> being System.currentTimeMillis() when the
# entry was added (UTC epoch ms; the service keeps timestamps unique and increasing, and drags an
# entry more than 10 s in the future back). So the name alone dates an entry and no clock of ours
# is involved: suspends, NTP and time zones play no part, and a prune of old entries cannot hide a
# new one (nothing is counted against a baseline). An entry is new when its timestamp is above the
# newest one the last scan saw (_hg_cr_seen), and only new entries are opened, to read ActivityManager's
# "Process: NAME" header (process PKG or PKG:sub counts; PKG.other and HG_CRASH_IGNORED, our
# settings page's process, do not). A scan is one glob per tag (a readdir of cached dentries) plus builtin string tests: no fork, no binder call, no
# write. Only a new .gz entry forks ($HG_CMD_ZCAT | awk). Fails open, never fakes a crash: a .lost
# entry, an entry without the header, dropbox rate limiting, and a clock set back behind the newest
# entry (new entries then sort below it) can each hide one.
#
# The state lives in shell variables of the service.sh process: _hg_cr_mode (dir, or dumpsys for
# the fallback), _hg_cr_seen, and per process the timestamps of its recent new entries
# (_hg_cr_ls SystemUI, _hg_cr_lo ours: each pruned to the last HG_CRASH_WINDOW s).
# Fallback: if $HG_DROPBOX can't be listed at a reset (it holds no entry of any tag, while a real
# dropbox always holds some, SYSTEM_BOOT at least: a path change or SELinux) or the shell lacks
# 64-bit arithmetic, crashes are counted from `dumpsys dropbox --print` instead (hg_crash_sample,
# 4 dumps per check; logged once a boot): 2 entries more than a reference sample, which the
# watchdog renews every HG_CRASH_WINDOW s of its own time (_hg_cr_age).

# HG_TS = the timestamp in the name of dropbox entry $1; 1 if it has none (or the glob matched nothing).
hg_dropbox_ts() {
  HG_TS="${1##*@}"; HG_TS="${HG_TS%%.*}"
  case "$HG_TS" in ''|*[!0-9]*) return 1 ;; esac
}

# 0 if $HG_DROPBOX can be read by name: it lists at least one entry, and this shell does 64-bit
# arithmetic (13-digit millisecond timestamps).
hg_dropbox_usable() {
  [ "$(( 1790000000000 + 1 ))" = 1790000000001 ] || return 1
  [ 1790000000001 -gt 1790000000000 ] 2>/dev/null || return 1
  for _hg_f in "$HG_DROPBOX"/*@*; do [ -e "$_hg_f" ] && return 0; break; done
  return 1
}

# HG_WHO = sysui or ours, from the "Process:" header of dropbox entry $1 (its first 5 lines); 1 if
# it is neither, or unreadable.
hg_dropbox_who() {
  _hg_pn=""
  case "$1" in
    *.lost) return 1 ;;
    *.gz) _hg_pn="$($HG_CMD_ZCAT "$1" 2>/dev/null | awk '{ sub(/\r$/, "") } /^Process: / { print $2; exit } NR >= 5 { exit }')" ;;
    *) [ -r "$1" ] || return 1
      _hg_i=0
      while [ "$_hg_i" -lt 5 ] && IFS= read -r _hg_ln; do
        case "$_hg_ln" in "Process: "*) _hg_pn="${_hg_ln#Process: }"; break ;; esac
        _hg_i=$(( _hg_i + 1 ))
      done 2>/dev/null < "$1" ;;
  esac
  _hg_pn="${_hg_pn%%[!A-Za-z0-9_.:]*}" # a trailing CR or blank
  case "$_hg_pn" in
    "$HG_CRASH_IGNORED") return 1 ;;
    "$HG_SYSUI_PKG"|"$HG_SYSUI_PKG":*) HG_WHO=sysui ;;
    "$HG_PKG"|"$HG_PKG":*) HG_WHO=ours ;;
    *) return 1 ;;
  esac
}

# A new reference for the crash watch: only entries added after this count (see above).
hg_crash_reset() {
  _hg_cr_ls=""; _hg_cr_lo=""; _hg_cr_age=0; _hg_cr_ref=""; _hg_cr_fast=0
  if hg_dropbox_usable; then
    _hg_cr_mode=dir; _hg_cr_seen=0
    for _hg_tag in $HG_CRASH_TAGS; do
      for _hg_f in "$HG_DROPBOX/$_hg_tag"@*; do
        hg_dropbox_ts "$_hg_f" && [ "$HG_TS" -gt "$_hg_cr_seen" ] && _hg_cr_seen="$HG_TS"
      done
    done
    return 0
  fi
  _hg_cr_mode=dumpsys
  hg_log_once "crash watch: $HG_DROPBOX can't be listed (or no 64-bit shell arithmetic); counting dumpsys dropbox --print instead"
  _hg_cr_ref="$(hg_crash_sample)"
}

# Records new entry timestamp $1 of HG_WHO; 0 (HG_CRASH_WHY says why, $2 = the period for the
# message) if an earlier new entry of the same process is no more than HG_CRASH_WINDOW s away.
hg_crash_add() {
  if [ "$HG_WHO" = sysui ]; then _hg_cal="$_hg_cr_ls"; _hg_cp="$HG_SYSUI_PKG"; else _hg_cal="$_hg_cr_lo"; _hg_cp="$HG_PKG"; fi
  _hg_cr_fast="$HG_CRASH_WINDOW" # the watchdog ticks faster while a second crash would count
  HG_CRASH_SEEN="$_hg_cp" # this scan saw a new crash of it (hg_after_rollback)
  _hg_k=1
  for _hg_u in $_hg_cal; do
    _hg_d=$(( $1 - _hg_u ))
    [ "$_hg_d" -lt 0 ] && _hg_d=$(( 0 - _hg_d ))
    [ "$_hg_d" -le $(( HG_CRASH_WINDOW * 1000 )) ] && _hg_k=$(( _hg_k + 1 ))
  done
  if [ "$HG_WHO" = sysui ]; then _hg_cr_ls="$_hg_cal $1"; else _hg_cr_lo="$_hg_cal $1"; fi
  [ "$_hg_k" -ge 2 ] || return 1
  HG_CRASH_WHY="$_hg_cp crashed $_hg_k times $2"
}

# HG_LIST = the timestamps of list $1 no more than HG_CRASH_WINDOW s older than its newest.
hg_crash_prune() {
  _hg_mx=0
  for _hg_u in $1; do [ "$_hg_u" -gt "$_hg_mx" ] && _hg_mx="$_hg_u"; done
  HG_LIST=""
  for _hg_u in $1; do
    [ $(( _hg_mx - _hg_u )) -le $(( HG_CRASH_WINDOW * 1000 )) ] && HG_LIST="$HG_LIST $_hg_u"
  done
  return 0
}

# One crash check ($1 = the period for the message: "since setup", "within 10 minutes"). 0 and
# HG_CRASH_WHY: a crash loop. Without a reference yet it sets one and passes.
hg_crash_scan() {
  HG_CRASH_WHY=""; HG_CRASH_SEEN=""
  if [ -z "${_hg_cr_mode:-}" ]; then hg_crash_reset; return 1; fi
  if [ "$_hg_cr_mode" = dumpsys ]; then hg_crash_scan_dumpsys "$1"; return; fi
  _hg_cr_new="$_hg_cr_seen"; _hg_cr_hit=1
  for _hg_tag in $HG_CRASH_TAGS; do
    for _hg_f in "$HG_DROPBOX/$_hg_tag"@*; do
      hg_dropbox_ts "$_hg_f" && [ "$HG_TS" -gt "$_hg_cr_seen" ] || continue
      [ "$HG_TS" -gt "$_hg_cr_new" ] && _hg_cr_new="$HG_TS"
      hg_dropbox_who "$_hg_f" || continue
      hg_crash_add "$HG_TS" "$1" && _hg_cr_hit=0
    done
  done
  _hg_cr_seen="$_hg_cr_new"
  hg_crash_prune "$_hg_cr_ls"; _hg_cr_ls="$HG_LIST"
  hg_crash_prune "$_hg_cr_lo"; _hg_cr_lo="$HG_LIST"
  return "$_hg_cr_hit"
}

# The fallback's check: counts against _hg_cr_ref (see above). An unknown sample passes; a count
# that went down (the dropbox pruned old entries) or an unknown reference re-baselines.
hg_crash_scan_dumpsys() {
  _hg_cur="$(hg_crash_sample)"
  HG_CRASH_WHY="$(hg_crash_compare "$_hg_cur" "$_hg_cr_ref" "$1")"
  case $? in
    0) return 0 ;;
    3) _hg_cr_ref="$_hg_cur"; _hg_cr_age=0
       hg_log_once "crash count: the reference was unknown or above the dropbox count (old entries dropped?); re-baselined" ;;
    *) case "$_hg_cur" in
         [0-9]*) if [ "${_hg_cr_age:-0}" -ge "$HG_CRASH_WINDOW" ]; then _hg_cr_ref="$_hg_cur"; _hg_cr_age=0; fi ;;
       esac ;;
  esac
  HG_CRASH_WHY=""
  return 1
}

# The fallback's parser. stdin: `dumpsys dropbox --print TAG` (DropBoxManagerService): a "Drop box
# contents:" line, then per entry a line of '=' and a header line, then the text, whose first
# "Process: NAME" line names the process. $1 = packages, space-separated: process PKG or PKG:sub
# counts, PKG.other does not, nor HG_CRASH_IGNORED. Prints one count per package, in order, or
# "?" when the output is not a dropbox dump.
hg_dropbox_count() {
  awk -v pkgs="$1" -v ignored="$HG_CRASH_IGNORED" '
    BEGIN { np = split(pkgs, pkg, " ") }
    { sub(/\r$/, "") }
    /^Drop box contents:/ { known = 1; next }
    /^========================================/ { inside = 1; next }
    inside && /^Process: / {
      inside = 0
      if ($2 == ignored) next
      for (i = 1; i <= np; i++) if ($2 == pkg[i] || index($2, pkg[i] ":") == 1) n[i]++
    }
    END {
      if (!known) { print "?"; exit }
      # printf, not `out (expr)`: busybox awk (the standalone ash of KernelSU) parses that as a call.
      for (i = 1; i <= np; i++) printf "%s%d", (i > 1 ? " " : ""), n[i] + 0
      print ""
    }
  '
}

# The fallback's sample: "SYSUI OURS", the dropbox entries of SystemUI and of our package over
# HG_CRASH_TAGS (each tag read once), or "?" when a dump can't be read or parsed (logged once a
# boot; every check that gets "?" passes).
hg_crash_sample() {
  _hg_s1=0; _hg_s2=0
  for _hg_tag in $HG_CRASH_TAGS; do
    # The dump is captured first, with the command's own exit status: hg_timeout kills a slow dump
    # after 10 s and a pipeline would report awk's status, so a truncated dump (header present,
    # newest entries missing) would parse as a lower count and skew a reference or fake a rise.
    _hg_box="$(hg_timeout $HG_CMD_DUMPSYS dropbox --print "$_hg_tag" 2>/dev/null)" && _hg_boxok=1 || _hg_boxok=0
    _hg_c="?"
    if [ "$_hg_boxok" = 1 ]; then
      case "$_hg_box" in
        *"DUMP TIMEOUT"*) ;;
        *) _hg_c="$(printf '%s\n' "$_hg_box" | hg_dropbox_count "$HG_SYSUI_PKG $HG_PKG")" ;;
      esac
    fi
    case "$_hg_c" in
      [0-9]*\ [0-9]*) ;;
      *)
        hg_log_once "crash count: dumpsys dropbox --print $_hg_tag unreadable or unparsable (failed, timed out or not a dropbox dump); crash checks pass"
        echo "?"; return 0 ;;
    esac
    _hg_s1=$(( _hg_s1 + ${_hg_c%% *} )); _hg_s2=$(( _hg_s2 + ${_hg_c##* } ))
  done
  echo "$_hg_s1 $_hg_s2"
}

# The fallback's rule. $1 = a sample, $2 = the reference sample, $3 = the period for the message.
# 0 (and prints why): SystemUI or our package has 2 or more entries more than the reference. 1:
# fine, or $1 unknown (fail open). 3: the reference is unknown, or a count went down, so the
# caller re-baselines on $1.
hg_crash_compare() {
  _hg_c1="${1%% *}"; _hg_c2="${1##* }"; _hg_r1="${2%% *}"; _hg_r2="${2##* }"
  case "$_hg_c1$_hg_c2" in ''|*[!0-9]*) return 1 ;; esac
  case "$_hg_r1$_hg_r2" in ''|*[!0-9]*) return 3 ;; esac
  if [ "$_hg_c1" -lt "$_hg_r1" ] || [ "$_hg_c2" -lt "$_hg_r2" ]; then return 3; fi
  if [ $(( _hg_c1 - _hg_r1 )) -ge 2 ]; then echo "$HG_SYSUI_PKG crashed $(( _hg_c1 - _hg_r1 )) times $3"; return 0; fi
  if [ $(( _hg_c2 - _hg_r2 )) -ge 2 ]; then echo "$HG_PKG crashed $(( _hg_c2 - _hg_r2 )) times $3"; return 0; fi
  return 1
}

# 0 if the gestural navbar overlay is enabled (the only mode in which our TouchInteractionService
# registers its swipe-up gesture monitor).
hg_gestural_on() { hg_enabled_navbar_overlays | grep -qx "$HG_NAVBAR_PREFIX.gestural"; }

# 0 if our swipe-up gesture monitor is registered: the app's hypergestures_gesture_monitor
# (hg_app_fact), else `dumpsys input` (hg_monitor_in_dump, whose 1 and 2 it returns). The first
# time the value counts in this process, the dump is read too and the log says (once a boot)
# whether it agrees.
hg_monitor_registered() {
  if hg_app_fact "$HG_FACT_MONITOR"; then
    if [ -z "${_hg_xm:-}" ]; then
      _hg_xm=1
      hg_monitor_in_dump
      case $? in
        0) _hg_xw="agrees" ;;
        1) _hg_xw="does not list it (has its format changed?); going by the app" ;;
        *) _hg_xw="can't tell" ;;
      esac
      hg_log_once "monitor check: the app's $HG_FACT_MONITOR (pid $HG_FACT_PID, this boot) says the swipe-up monitor is registered; dumpsys input $_hg_xw" \
        "fact $HG_FACT_MONITOR"
    fi
    return 0
  fi
  hg_monitor_in_dump
}

# 0 if `dumpsys input` lists a "[Gesture Monitor] swipe-up" whose ownerPid is a pid of our process
# (pidof); 1 if there is none, or every one parsed belongs to another pid; 2 if it can't tell:
# dumpsys input printed nothing, pidof printed nothing, or a swipe-up line has no ownerPid.
hg_monitor_in_dump() {
  _hg_pids=" $(echo $($HG_CMD_PIDOF "$HG_PKG" 2>/dev/null)) "
  [ "$_hg_pids" = "  " ] && return 2
  hg_timeout $HG_CMD_DUMPSYS input 2>/dev/null | awk -v pids="$_hg_pids" '
    index($0, "[Gesture Monitor] swipe-up") {
      if (!match($0, /ownerPid=[0-9]+/)) { unknown = 1; next }
      if (index(pids, " " substr($0, RSTART + 9, RLENGTH - 9) " ")) found = 1
    }
    END { if (found) exit 0; if (NR == 0 || unknown) exit 2; exit 1 }'
}

# The end-of-window checks; prints why and returns 1 if one fails. The monitor is only checked in
# gestural mode: in 3-button, fsg or keep mode Launcher3 registers none.
hg_health_final() {
  if ! hg_provider_bound; then
    echo "provider not bound at the end of the health window"; return 1
  fi
  hg_gestural_on || return 0
  hg_monitor_registered
  case $? in
    0) return 0 ;;
    2) hg_log_once "health: can't tell whether the gesture monitor is ours (no dumpsys input, no pidof, or no ownerPid); that check passes"
       return 0 ;;
  esac
  echo "gesture monitor not registered (no [Gesture Monitor] swipe-up owned by $HG_PKG)"
  return 1
}

# Records this boot's state ($1, HG_STATE: starting, active, dormant, rolled-back, not-set-up or
# refused) and the latest health result (the rest, HG_HEALTH) for action.sh, and both for the
# app (hg_publish_status).
hg_health_status() {
  HG_STATE="$1"; shift; HG_HEALTH="$*"
  mkdir -p "$HG_DATA" && echo "$* ($(date '+%F %T'))" > "$HG_DATA/health-status"
  hg_publish_status
}

# This boot reached boot_completed: not a boot loop (hg_boot_guard). $1 = why, for the log.
hg_boot_finished() {
  [ -f "$HG_DATA/boot-attempts" ] || return 0
  rm -f "$HG_DATA/boot-attempts"
  hg_log "boot finished ($1): boot-loop count reset"
}

# The health check, right after setup ($1 = module dir; the crash watch's reference was set when
# the provider was bound, hg_crash_reset). Every HG_HEALTH_TICK s for HG_HEALTH_WINDOW s (by the
# uptime clock, and never more than WINDOW/TICK ticks): SystemUI keeps our provider bound, and
# neither SystemUI nor our package crashed 2 or more times since setup (hg_crash_scan: free; the
# SystemUI dump is the one exec per tick). One unbound tick is let through, two in a
# row fail: one crash of our process is allowed, and SystemUI rebinds after it. At the end of the
# window the provider must be bound and, in gestural mode, our swipe-up gesture monitor
# registered; if not, all of that is checked once more HG_HEALTH_TICK s later. Healthy: 0,
# health-status "ok". Otherwise hg_rollback and 1. HEALTH_TEST=fail in the config forces one
# failure and removes itself from the config.
hg_health_check() {
  hg_health_status active "running (checks for $HG_HEALTH_WINDOW s after setup)"
  if [ "${HEALTH_TEST:-}" = fail ]; then
    HEALTH_TEST=off
    hg_config_drop HEALTH_TEST
    hg_rollback "forced by HEALTH_TEST" "$1"; return 1
  fi
  hg_log "health check: watching for $HG_HEALTH_WINDOW s"
  _hg_ticks=$(( HG_HEALTH_WINDOW / HG_HEALTH_TICK ))
  _hg_end=""
  _hg_t="$(hg_uptime)" && _hg_end=$(( _hg_t + HG_HEALTH_WINDOW ))
  _hg_unbound=0
  while [ "$_hg_ticks" -gt 0 ]; do
    sleep "$HG_HEALTH_TICK"
    _hg_ticks=$(( _hg_ticks - 1 ))
    hg_power_retry >/dev/null 2>&1
    if hg_crash_scan "since setup"; then
      hg_rollback "$HG_CRASH_WHY" "$1"; return 1
    fi
    if hg_provider_bound; then
      _hg_unbound=0
    else
      _hg_unbound=$(( _hg_unbound + 1 ))
      hg_log "health check: provider not bound ($_hg_unbound in a row)"
      if [ "$_hg_unbound" -ge 2 ]; then
        hg_rollback "provider not bound at 2 checks in a row" "$1"; return 1
      fi
    fi
    if [ -n "$_hg_end" ] && _hg_t="$(hg_uptime)" && [ "$_hg_t" -ge "$_hg_end" ]; then break; fi
  done
  hg_power_retry >/dev/null 2>&1
  if ! _hg_why="$(hg_health_final)"; then
    hg_log "health check: $_hg_why; checking once more in $HG_HEALTH_TICK s"
    sleep "$HG_HEALTH_TICK"
    if hg_crash_scan "since setup"; then
      hg_rollback "$HG_CRASH_WHY" "$1"; return 1
    fi
    if ! _hg_why="$(hg_health_final)"; then
      hg_rollback "$_hg_why" "$1"; return 1
    fi
  fi
  if hg_gestural_on; then _hg_mon="gesture monitor registered"; else _hg_mon="no gesture monitor needed (not gestural)"; fi
  hg_health_status active "ok: provider bound, no crash loop, $_hg_mon"
  hg_log "health check passed ($_hg_mon)"
  rm -f "$HG_DATA/rollback-reboot" "$HG_DATA/rollback-reboot-told" # a later rollback may reboot again
  return 0
}

# Returns the phone to stock and disables the module: $1 = why, $2 = module dir (default
# HG_MODDIR), $3 = the notification (default: the failed health check's), $4 = noguard when the
# undo guard is not installed. Every step runs even if
# an earlier one failed, and each is logged with its result. Nothing here needs SystemUI (pm, cmd,
# settings and the notification service live in system_server), so it works while SystemUI
# crash-loops. The failed APK and the recents overlay stay mounted until the next boot (SystemUI
# keeps binding us), so a gesture navigation mode
# (the restored original) is moved to 3 buttons for the rest of this one, as hg_firmware_refuse
# does; the guard restores the original at the next boot. Without the guard (noguard) nothing
# would put the original back, so navigation stays as restored. The caller then stops.
hg_rollback() {
  _hg_md="${2:-$HG_MODDIR}"
  hg_log "rollback: $1"
  if hg_restore_all; then _hg_r1=ok; else _hg_r1=FAILED; fi
  hg_log "rollback: 1/6 MiuiHome and navigation restored: $_hg_r1"
  _hg_r2="not needed (no gesture mode)"
  if [ "${4:-}" = noguard ]; then
    _hg_r2="left as restored (no undo guard to bring the original back after 3-button)"
  elif hg_gesture_nav_active now; then
    hg_record_nav_state # a no-op when recorded: the original is never replaced by 3-button
    if hg_apply_nav_mode threebutton; then _hg_r2="3-button until reboot"; else _hg_r2=FAILED; fi
  fi
  hg_log "rollback: 2/6 navigation for the rest of this boot: $_hg_r2"
  if hg_restore_home; then _hg_r3=ok; else _hg_r3=FAILED; fi
  hg_log "rollback: 3/6 HOME back to com.miui.home: $_hg_r3"
  if touch "$_hg_md/disable" 2>/dev/null && [ -f "$_hg_md/disable" ]; then _hg_r4=ok; else _hg_r4=FAILED; fi
  hg_log "rollback: 4/6 module disabled from the next boot ($_hg_md/disable): $_hg_r4"
  if mkdir -p "$HG_DATA" 2>/dev/null && touch "$HG_DATA/restored" 2>/dev/null; then _hg_r5=ok; else _hg_r5=FAILED; fi
  hg_log "rollback: 5/6 restored marker (the watchdog leaves navigation alone): $_hg_r5"
  rm -f "$HG_DATA/boot-attempts"
  hg_health_status rolled-back "FAILED: $1; rolled back (restore $_hg_r1, HOME $_hg_r3, disable $_hg_r4)"
  _hg_msg="${3:-HyperGestures failed its health check ($1) and restored stock navigation. It is disabled; re-enable it in KernelSU after fixing. See $HG_DATA/log.txt.}"
  [ "$_hg_r4" = ok ] || _hg_msg="$_hg_msg It could NOT disable itself: disable it in the KernelSU Manager app."
  [ "$_hg_r1$_hg_r3" = okok ] || _hg_msg="$_hg_msg Part of the restore FAILED: see runbook section 5."
  hg_notify "$_hg_msg Reboot to finish returning to stock."
  hg_log "rollback: 6/6 owner notified; watchdog stopped"
}

# After a rollback this boot ($1 = module dir). The recents overlay stays mounted until the next
# boot, so SystemUI keeps binding our app; if that is what crashes SystemUI, the crash loop would
# go on until SystemUI's own crash recovery escalates. So for 10 minutes, every
# HG_HEALTH_TICK s (the builtin crash scan, no fork), a further crash of SystemUI or ours reboots
# the phone, once: the module is disabled, so that boot is stock and the guard tells the owner why.
# (HG_ROLLBACK_WATCH s, HG_CRASH_WINDOW by default.)
# Only with the disable file in place (otherwise the next boot would set up again and could roll
# back and reboot again), and not again while the marker rollback-reboot is there (a healthy setup
# removes it). Stops early if the owner re-enables the module.
hg_after_rollback() {
  if [ ! -f "$1/disable" ]; then
    hg_log "after rollback: the module is not disabled, so no automatic reboot"; return 0
  fi
  if [ -f "$HG_DATA/rollback-reboot" ]; then
    hg_log "after rollback: already rebooted once for a rollback, so no automatic reboot"; return 0
  fi
  hg_log "after rollback: watching $HG_ROLLBACK_WATCH s; a further crash of SystemUI or $HG_PKG reboots into stock once"
  _hg_ar=$(( HG_ROLLBACK_WATCH / HG_HEALTH_TICK ))
  while [ "$_hg_ar" -gt 0 ]; do
    sleep "$HG_HEALTH_TICK"
    _hg_ar=$(( _hg_ar - 1 ))
    [ -f "$1/disable" ] || { hg_log "after rollback: the module was re-enabled; watch ended"; return 0; }
    hg_crash_scan "after the rollback"; _hg_arl=$?
    if [ "$_hg_arl" = 0 ] || [ -n "$HG_CRASH_SEEN" ]; then
      _hg_arw="${HG_CRASH_SEEN:-SystemUI or $HG_PKG}"
      mkdir -p "$HG_DATA" && echo "$_hg_arw crashed again after the rollback" > "$HG_DATA/rollback-reboot"
      hg_log "after rollback: $_hg_arw crashed again; rebooting into stock (once)"
      $HG_CMD_REBOOT >/dev/null 2>&1 || $HG_CMD_REBOOT_FALLBACK >/dev/null 2>&1
      return 0
    fi
  done
  hg_log "after rollback: no further crash in $HG_ROLLBACK_WATCH s; watch ended"
}

# Boot-loop protection, run first by post-fs-data (only while the module is enabled; $1 = module
# dir). Every boot is counted in boot-attempts, and hg_service_main resets the count as soon as the
# boot reaches boot_completed. A count of HG_BOOT_TRIES means that many boots in a row never got
# there, e.g. the mounted APK breaks the boot. Then: writes skip_mount and disable (so the
# metamodule mounts nothing; if it read skip_mount before this script ran, which is unverified,
# the disable still keeps the next boot clean), resets the count, and leaves the marker bootloop:
# at boot_completed the guard (the module now counts as disabled) or hg_service_main restores
# stock, sets HOME and notifies once. 0 = boot normally; 1 = tripped, mount nothing and leave
# skip_mount alone.
hg_boot_guard() {
  _hg_n="$(cat "$HG_DATA/boot-attempts" 2>/dev/null)"
  case "$_hg_n" in ''|*[!0-9]*) _hg_n=0 ;; esac
  mkdir -p "$HG_DATA"
  if [ "$_hg_n" -lt "$HG_BOOT_TRIES" ]; then
    # A marker nobody took (an older guard copy) must not roll back a module the owner re-enabled.
    rm -f "$HG_DATA/bootloop"
    echo $(( _hg_n + 1 )) > "$HG_DATA/boot-attempts"
    return 0
  fi
  if hg_sync_skip_mount "$1" self; then _hg_b1=ok; else _hg_b1=FAILED; fi
  if touch "$1/disable" 2>/dev/null && [ -f "$1/disable" ]; then _hg_b2=ok; else _hg_b2=FAILED; fi
  echo "$_hg_n" > "$HG_DATA/bootloop"
  # The guard must restore (and take the marker) even if it restored after an earlier disable.
  rm -f "$HG_DATA/boot-attempts" "$HG_DATA/guard-restored"
  hg_log "post-fs-data: $_hg_n boots in a row did not reach boot_completed (boot-loop protection):" \
    "skip_mount $_hg_b1, disable $_hg_b2; stock is restored at boot_completed"
  hg_mount_status "skipped: boot-loop protection ($_hg_n boots in a row did not reach boot_completed; module disabled)"
  return 1
}

# 0 if this caller takes the boot-loop marker. A rename succeeds for one caller only, so the guard
# and service.sh never both notify.
hg_bootloop_claim() {
  mv -f "$HG_DATA/bootloop" "$HG_DATA/.bootloop.$$" 2>/dev/null || return 1
  rm -f "$HG_DATA/.bootloop.$$"
}

# The boot-loop rollback's message ($1 = 0 if stock was restored).
hg_bootloop_message() {
  if [ "$1" = 0 ]; then
    echo "HyperGestures stopped: $HG_BOOT_TRIES boots in a row did not finish starting up; restored stock and disabled itself. See $HG_DATA/log.txt."
  else
    echo "HyperGestures stopped: $HG_BOOT_TRIES boots in a row did not finish starting up; it disabled itself, but restoring stock FAILED (retried at the next boot; runbook section 5). See $HG_DATA/log.txt."
  fi
}

# The guard's side of a boot-loop trip, after its restore ($1 = module dir, $2 = that restore's
# status): skip_mount back in step with the mount mode (hg_mount_resolve; so re-enabling the module
# mounts it again), the health record, one notification.
hg_bootloop_notice() {
  hg_sync_skip_mount "$1" "$(hg_mount_mode "$1" "$(hg_config_get MOUNT)")"
  if [ "$2" = 0 ]; then _hg_st=ok; else _hg_st=FAILED; fi
  hg_health_status rolled-back "FAILED: $HG_BOOT_TRIES boots in a row did not finish starting up; module disabled, restore $_hg_st"
  hg_log "boot-loop protection: restore $_hg_st, owner notified"
  hg_notify "$(hg_bootloop_message "$2")"
}

# Sources $HG_DATA/config if it parses as shell: a syntax error in a dot script ends a
# non-interactive shell, which would stop post-fs-data before it counts the boot, or service.sh
# before setup. One that does not parse is logged (once a boot) and ignored: every setting takes
# its default. 1 if it was ignored.
hg_load_config() {
  [ -f "$HG_DATA/config" ] || return 0
  if ( . "$HG_DATA/config" ) >/dev/null 2>&1; then . "$HG_DATA/config"; return 0; fi
  hg_log_once "config: $HG_DATA/config does not parse as shell; ignored, every setting at its default"
  return 1
}

# Creates $HG_DATA/config from the module's config.default ($1 = module dir) if there is none.
hg_ensure_config() {
  [ -f "$HG_DATA/config" ] && return 0
  mkdir -p "$HG_DATA" && cp "$1/config.default" "$HG_DATA/config"
}

# Prints the value of setting $1 in $HG_DATA/config (the last line wins, as when it is sourced);
# empty if none.
hg_config_get() {
  awk -v k="$1" '
    { sub(/\r$/, "") }
    $0 ~ ("^[ \t]*" k "=") { v = $0; sub(/^[ \t]*[^=]*=/, "", v) }
    END { print v }
  ' "$HG_DATA/config" 2>/dev/null
}

# Removes every line of setting $1 from $HG_DATA/config (temp file + rename).
hg_config_drop() {
  [ -f "$HG_DATA/config" ] || return 0
  if awk -v k="$1" '$0 !~ ("^[ \t]*" k "=")' "$HG_DATA/config" > "$HG_DATA/.config.tmp" &&
     mv -f "$HG_DATA/.config.tmp" "$HG_DATA/config"; then
    hg_log "config: $1 removed"; return 0
  fi
  rm -f "$HG_DATA/.config.tmp"
  hg_log "config: could not remove $1 from $HG_DATA/config"
  return 1
}

# Body of setconf.sh: sets one setting in $HG_DATA/config ($1 = module dir, $2 = key, $3 = value),
# whether or not the config has a line for it yet (a `sed s/^KEY=.../` silently does nothing on a
# missing line). Creates the config from config.default first if there is none. Replaces the first
# KEY= line in place and drops any later one (the last would win when service.sh sources the
# file), or appends one; drops CRs a Windows editor may have left. Only known keys and values are
# accepted: service.sh sources the file as shell code. MOUNT also creates/removes the module's
# skip_mount flag for the mode it resolves to now (hg_mount_resolve, hg_sync_skip_mount), fails if
# it can't, and drops the metamodule-missed marker (so MOUNT=auto tries the metamodule again).
# WATCHDOG=0 also stops the running watchdog at its next tick (marker watchdog-stop).
hg_setconf() {
  if [ "$#" -ne 3 ]; then
    echo "usage: setconf.sh KEY VALUE   (NAV_MODE gestural|fsg|threebutton|keep, DISABLE_MIUIHOME 0|1," \
      "WATCHDOG 0|1, STRICT_FIRMWARE 0|1, IGNORE_FINGERPRINT 0|1, MOUNT auto|metamodule|self, HEALTH_TEST fail|off)" >&2
    return 2
  fi
  case "$2=$3" in
    NAV_MODE=gestural|NAV_MODE=fsg|NAV_MODE=threebutton|NAV_MODE=keep) ;;
    DISABLE_MIUIHOME=[01]|WATCHDOG=[01]|IGNORE_FINGERPRINT=[01]|STRICT_FIRMWARE=[01]) ;;
    MOUNT=auto|MOUNT=metamodule|MOUNT=self) ;;
    HEALTH_TEST=fail|HEALTH_TEST=off) ;;
    NAV_MODE=*) echo "setconf: NAV_MODE must be gestural, fsg, threebutton or keep (got '$3')" >&2; return 2 ;;
    DISABLE_MIUIHOME=*|WATCHDOG=*|IGNORE_FINGERPRINT=*|STRICT_FIRMWARE=*) echo "setconf: $2 must be 0 or 1 (got '$3')" >&2; return 2 ;;
    MOUNT=*) echo "setconf: MOUNT must be auto, metamodule or self (got '$3')" >&2; return 2 ;;
    HEALTH_TEST=*) echo "setconf: HEALTH_TEST must be fail or off (got '$3')" >&2; return 2 ;;
    *) echo "setconf: unknown setting '$2' (NAV_MODE, DISABLE_MIUIHOME, WATCHDOG, STRICT_FIRMWARE," \
         "IGNORE_FINGERPRINT, MOUNT or HEALTH_TEST)" >&2; return 2 ;;
  esac
  if ! hg_ensure_config "$1"; then
    echo "setconf: no $HG_DATA/config, and it could not be created from $1/config.default" >&2
    return 1
  fi
  if ! awk -v k="$2" -v v="$3" '
      { sub(/\r$/, "") }
      $0 ~ ("^[ \t]*" k "=") { if (!done) print k "=" v; done = 1; next }
      { print }
      END { if (!done) print k "=" v }
    ' "$HG_DATA/config" > "$HG_DATA/.config.tmp" || ! mv -f "$HG_DATA/.config.tmp" "$HG_DATA/config"; then
    rm -f "$HG_DATA/.config.tmp"
    echo "setconf: could not write $HG_DATA/config" >&2
    return 1
  fi
  hg_log "setconf: $2=$3"
  if [ "$2" = MOUNT ]; then
    rm -f "$HG_DATA/metamodule-missed"
    hg_mount_resolve "$1" "$3"
    if [ "$HG_MOUNT_MODE" = self ]; then _hg_sk="skip_mount set"; _hg_skv=created; else _hg_sk="skip_mount removed"; _hg_skv=removed; fi
    if ! hg_sync_skip_mount "$1" "$HG_MOUNT_MODE"; then
      echo "setconf: $2=$3 is in $HG_DATA/config, but $1/skip_mount could not be $_hg_skv (see $HG_DATA/log.txt)" >&2
      return 1
    fi
    echo "$2=$3 (in $HG_DATA/config; now: $HG_MOUNT_DESC; $_hg_sk; reboot to apply)"
    return 0
  fi
  if [ "$2" = WATCHDOG ]; then
    if [ "$3" = 0 ]; then
      touch "$HG_DATA/watchdog-stop" 2>/dev/null
      echo "$2=$3 (in $HG_DATA/config; a running watchdog stops at its next check)"
      return 0
    fi
    rm -f "$HG_DATA/watchdog-stop"
  fi
  if [ "$2" = HEALTH_TEST ] && [ "$3" = fail ]; then
    echo "$2=$3 (in $HG_DATA/config; the next boot's health check fails once and rolls back)"
    return 0
  fi
  echo "$2=$3 (in $HG_DATA/config; takes effect at the next boot)"
}

# --- mounting (post-fs-data) ----------------------------------------------------------------
# What is mounted, by the mount mode (hg_mount_resolve turns MOUNT into one of the two):
#   metamodule ("mixed"): the KernelSU metamodule (hybrid_mount, meta-overlayfs, ...) mounts the
#     module's system/ tree (priv-app APKs, privapp allowlists, hidden-API sysconfig); post-fs-data
#     self-mounts only the static recents overlay on /product/overlay. skip_mount is removed.
#   self: post-fs-data self-mounts system/ and the overlay, and the module's skip_mount flag keeps
#     any metamodule from mounting system/ too.
# A dormant boot (HOME is MiuiHome) mounts no overlay, so SystemUI keeps MiuiHome's recents, but
# keeps the app installed for what works with any HOME (the gesture-line overlay): the metamodule
# mounts system/ as usual, and in self mode post-fs-data mounts system/ alone (hg_self_mount app).
# MOUNT=auto (the default, and any unknown value) is mixed while an active metamodule is found
# (hg_metamodule_active) and self otherwise; MOUNT=metamodule and MOUNT=self force one.
# The overlay ships outside system/ (overlay/HyperGesturesOverlay.apk at the module root), so no
# metamodule ever mounts it: KernelSU's kernel_umount hides the mounts it tracks (the metamodule's)
# from every non-root process, WebView's zygote included, which holds the framework overlays open;
# every WebView renderer then aborts ("JNI FatalError: (zygote) Failed
# open(/product/overlay/HyperGesturesOverlay.apk)"). Our own mounts are not tracked by KernelSU, so
# every process sees them. It has to be a static overlay present before zygote: SystemUI binds the
# QuickStep provider that config_recentsComponentName names, and PackageManager grants our
# "recents"-protected permissions (INTERNAL_SYSTEM_WINDOW, MONITOR_INPUT, MANAGE_ACTIVITY_TASKS,
# STATUS_BAR_SERVICE) only to that package, when it evaluates permissions early in system_server's
# boot, before any mutable overlay (a fabricated one, as 0.3.5 tried) applies.
# Each module dir <dir> is copied to $HG_STAGE/<dir> (a tmpfs: overlayfs refuses the
# case-insensitive /data as a lower layer) and stacked read-only over <target> with overlayfs.
# Order matters: the privapp allowlist and hidden-API config go in before the APK (a priv-app
# without its allowlist bootloops, ro.control_privapp_permissions=enforce); the overlay goes last
# (an overlay naming a package that isn't there only leaves recents unbound).
# system/priv-app holds two APK directories: HyperGestures (the app) and $HG_NOTIF_DIR (the
# notification hooks, com.hypergestures.notif: the LSPosed module, its own APK because LSPosed
# copies every dex of a module's APK into each process it hooks). One mount of system/priv-app
# brings both; the app's directory stands for the tree wherever a check asks whether it is there.
HG_OVERLAY_APK=HyperGesturesOverlay.apk
HG_NOTIF_DIR=HyperGesturesNotif
HG_PAYLOAD_SYSTEM="system/etc/permissions:/system/etc/permissions system/etc/sysconfig:/system/etc/sysconfig system/priv-app:/system/priv-app"
HG_PAYLOAD_OVERLAY="overlay:/product/overlay"

# $1 = module dir, $2 = self (create the flag) or anything else (remove it). Whether KernelSU and
# the metamodule read skip_mount before or after this module's post-fs-data runs is unverified, so
# a change may only take effect at the next boot; the code is safe either way (hg_self_mount skips
# when the APK is already there, and a boot with neither mount just leaves setup undone).
# Non-zero, and logged, if the flag could not be written or removed.
hg_sync_skip_mount() {
  if [ "$2" = self ]; then
    # touch, not `: >`: a failed redirection on a special builtin exits a POSIX shell.
    touch "$1/skip_mount" 2>/dev/null && [ -f "$1/skip_mount" ] && return 0
    hg_log "skip_mount: could not create $1/skip_mount: a metamodule may mount system/ as well"
  else
    rm -f "$1/skip_mount" 2>/dev/null
    [ -e "$1/skip_mount" ] || return 0
    hg_log "skip_mount: could not remove $1/skip_mount: the metamodule will not mount system/"
  fi
  return 1
}

# 0 if $1 (a directory under $HG_MODULES_DIR) is a metamodule KernelSU will run: module.prop says
# metamodule=1 (or true), it has the metamount.sh mount hook (the KernelSU metamodule contract),
# and neither a disable nor a remove flag; never this module. Sets HG_MM_ID (its directory name)
# and HG_MM_VC (its versionCode). Builtins only.
hg_metamodule_ok() {
  _hg_mid="${1##*/}"
  [ "$_hg_mid" = hypergestures ] && return 1
  [ -d "$1" ] && [ -f "$1/metamount.sh" ] && [ -f "$1/module.prop" ] || return 1
  if [ -f "$1/disable" ] || [ -f "$1/remove" ]; then return 1; fi
  _hg_mflag=""; _hg_mvc=""; _hg_ml=""
  while IFS= read -r _hg_ml || [ -n "$_hg_ml" ]; do
    case "$_hg_ml" in
      metamodule=*) _hg_mflag="${_hg_ml#metamodule=}"; _hg_mflag="${_hg_mflag%%[!A-Za-z0-9]*}" ;;
      versionCode=*) _hg_mvc="${_hg_ml#versionCode=}"; _hg_mvc="${_hg_mvc%%[!0-9]*}" ;;
    esac
    _hg_ml=""
  done < "$1/module.prop"
  case "$_hg_mflag" in 1|true|TRUE|True) ;; *) return 1 ;; esac
  HG_MM_ID="$_hg_mid"; HG_MM_VC="${_hg_mvc:-0}"
}

# 0 if an active metamodule will mount modules' system/ trees (HG_MM_ID, HG_MM_VC name it). KernelSU
# links $HG_METAMODULE_LINK to the metamodule's directory: when the link exists only it counts (it
# must lead to a directory under $HG_MODULES_DIR: a dangling link means the metamodule is gone, one
# into modules_update means it is only installed until the next boot). Without the link (another
# KernelSU fork) exactly one module in $HG_MODULES_DIR must pass hg_metamodule_ok. Static checks,
# no binaries (-ef compares device and inode, following the link).
hg_metamodule_active() {
  HG_MM_ID=""; HG_MM_VC=""
  if [ -e "$HG_METAMODULE_LINK" ] || [ -L "$HG_METAMODULE_LINK" ]; then
    [ -d "$HG_METAMODULE_LINK" ] || return 1
    for _hg_mdir in "$HG_MODULES_DIR"/*; do
      [ "$_hg_mdir" -ef "$HG_METAMODULE_LINK" ] || continue
      hg_metamodule_ok "$_hg_mdir"; return
    done
    return 1
  fi
  _hg_mn=0; _hg_mfound=""
  for _hg_mdir in "$HG_MODULES_DIR"/*; do
    hg_metamodule_ok "$_hg_mdir" || continue
    _hg_mn=$(( _hg_mn + 1 )); _hg_mfound="$HG_MM_ID $HG_MM_VC"
  done
  HG_MM_ID=""; HG_MM_VC=""
  [ "$_hg_mn" = 1 ] || return 1
  HG_MM_ID="${_hg_mfound% *}"; HG_MM_VC="${_hg_mfound##* }"
}

# Turns MOUNT ($2; $1 = module dir) into HG_MOUNT_MODE (metamodule or self), HG_MOUNT_REQ (auto,
# metamodule or self: what was asked for; anything else counts as auto) and HG_MOUNT_DESC (for the
# log and action.sh). auto picks metamodule (mixed) when hg_metamodule_active finds one, unless the
# marker metamodule-missed names that same metamodule and versionCode: at an earlier boot it was
# chosen and did not mount our APK (hg_mount_check), so self is used until the metamodule changes
# (an update, another one) or the owner sets MOUNT again (setconf.sh drops the marker).
hg_mount_resolve() {
  HG_MOUNT_REQ="$2"
  case "$HG_MOUNT_REQ" in metamodule|self) ;; *) HG_MOUNT_REQ=auto ;; esac
  case "$HG_MOUNT_REQ" in
    self) HG_MM_ID=""; HG_MM_VC=""; HG_MOUNT_MODE=self; HG_MOUNT_DESC="self (MOUNT=self)" ;;
    metamodule)
      HG_MOUNT_MODE=metamodule
      if hg_metamodule_active; then HG_MOUNT_DESC="metamodule → mixed ($HG_MM_ID)"
      else HG_MOUNT_DESC="metamodule → mixed (MOUNT=metamodule, but no active metamodule was found)"; fi ;;
    auto)
      if ! hg_metamodule_active; then
        HG_MOUNT_MODE=self; HG_MOUNT_DESC="auto → self (no active metamodule)"
      else
        _hg_missed=""
        [ -f "$HG_DATA/metamodule-missed" ] && IFS= read -r _hg_missed < "$HG_DATA/metamodule-missed"
        if [ "$_hg_missed" = "$HG_MM_ID $HG_MM_VC" ]; then
          HG_MOUNT_MODE=self
          HG_MOUNT_DESC="auto → self ($HG_MM_ID did not mount the app at an earlier boot; setconf.sh MOUNT auto tries it again)"
        else
          HG_MOUNT_MODE=metamodule; HG_MOUNT_DESC="auto → mixed ($HG_MM_ID)"
        fi
      fi ;;
  esac
  return 0
}

# Prints the mount mode MOUNT $2 resolves to ($1 = module dir).
hg_mount_mode() { hg_mount_resolve "$1" "$2"; echo "$HG_MOUNT_MODE"; }

# service.sh, after boot_completed ($1 = module dir): 0 if this boot's post-fs-data chose mixed for
# MOUNT=auto (mount-mode) but the metamodule did not mount our APK. That metamodule may have rules
# of its own (hybrid_mount can skip modules), so the static checks can't tell in advance. Then the
# marker metamodule-missed names it (id and versionCode), so the next boot's auto picks self, and
# skip_mount is set now (in case the metamodule reads it before our post-fs-data runs). A mixed
# boot whose APK is there drops a marker left from before. HG_MM_ID = the metamodule.
hg_mount_check() {
  [ -f "$HG_DATA/mount-mode" ] || return 1
  _hg_q=""; _hg_m=""; HG_MM_ID=""; _hg_v=""
  read -r _hg_q _hg_m HG_MM_ID _hg_v _hg_rest < "$HG_DATA/mount-mode"
  [ "$_hg_q $_hg_m" = "auto metamodule" ] || return 1
  if [ -e "$HG_ROOT/system/priv-app/HyperGestures" ]; then
    rm -f "$HG_DATA/metamodule-missed"; return 1
  fi
  echo "$HG_MM_ID $_hg_v" > "$HG_DATA/metamodule-missed"
  hg_sync_skip_mount "$1" self
  hg_log "mount: MOUNT=auto chose mixed ($HG_MM_ID), but $HG_ROOT/system/priv-app/HyperGestures is not mounted:" \
    "the next boot mounts system/ itself (self; setconf.sh MOUNT auto tries $HG_MM_ID again)"
  return 0
}

# Records the outcome for action.sh: mode self "ok...", "skipped: ..." or "FAILED: ..."; mixed
# (mode metamodule) the same prefixed "metamodule + overlay " (the metamodule's own mount of system/
# is not ours to record).
hg_mount_status() {
  mkdir -p "$HG_DATA"
  echo "$*" > "$HG_DATA/mount-status"
  hg_log "payload mount: $*"
}

# $1 = module dir, $2 = what to mount: self (system/ and the overlay: MOUNT=self), app (system/
# alone: a dormant boot in self mode) or anything else (metamodule: the overlay alone). What is
# already there is left alone: our stage mounted earlier this boot, or our APK or overlay mounted
# by something else (e.g. a metamodule that mounted system/ despite skip_mount: then only the
# overlay is mounted). The rest goes to hg_payload_mount (all or nothing). Without the overlay
# SystemUI keeps MiuiHome's recents and never binds our provider, so service.sh changes nothing
# (hg_not_set_up); on a dormant boot that is the point.
hg_self_mount() {
  case "$2" in
    self) _hg_pre="" ;;
    app) _hg_pre="dormant (HOME is $HG_MIUIHOME), app only: " ;;
    *) _hg_pre="metamodule + overlay " ;;
  esac
  if grep -q '^hg_stage ' "$HG_MOUNTS" 2>/dev/null; then
    hg_mount_status "${_hg_pre}ok (already mounted this boot)"; return 0
  fi
  _hg_map=""; _hg_there=""
  case "$2" in self|app)
    if [ -e "$HG_ROOT/system/priv-app/HyperGestures" ]; then
      _hg_there=" /system/priv-app/HyperGestures"
    else
      _hg_map="$HG_PAYLOAD_SYSTEM"
    fi
  esac
  if [ "$2" = app ]; then
    : # no recents overlay: SystemUI keeps MiuiHome's recents and gestures
  elif [ -e "$HG_ROOT/product/overlay/$HG_OVERLAY_APK" ]; then
    _hg_there="$_hg_there /product/overlay/$HG_OVERLAY_APK"
  else
    _hg_map="$_hg_map $HG_PAYLOAD_OVERLAY"
  fi
  case "$_hg_map" in
    *:*) ;;
    *) hg_mount_status "${_hg_pre}skipped: already there (mounted by something else):$_hg_there"; return 0 ;;
  esac
  hg_payload_mount "$1" "$_hg_map" "$_hg_pre" "$_hg_there"
}

# hg_self_mount's mounts ($1 = module dir, $2 = the "dir:target" pairs, in mount order, $3 = the
# status prefix, $4 = what was already there, for the status). All or nothing: on any failure the
# mounts made so far are undone, newest first; if an undo fails, the older ones stay (so an APK is
# never left without its allowlist).
hg_payload_mount() {
  _hg_map="$2"; _hg_pre="$3"; _hg_there="$4"
  for _hg_m in $_hg_map; do
    if [ ! -d "$1/${_hg_m%%:*}" ] || [ ! -d "$HG_ROOT${_hg_m#*:}" ]; then
      hg_mount_status "${_hg_pre}FAILED: missing $1/${_hg_m%%:*} or ${_hg_m#*:}; nothing mounted"; return 1
    fi
  done
  mkdir -p "$HG_STAGE"
  if ! $HG_CMD_MOUNT -t tmpfs -o mode=0755,size=64m hg_stage "$HG_STAGE"; then
    hg_mount_status "${_hg_pre}FAILED: tmpfs on $HG_STAGE; nothing mounted"; return 1
  fi
  _hg_staged=1
  for _hg_m in $_hg_map; do
    _hg_d="${_hg_m%%:*}"
    mkdir -p "$HG_STAGE/$_hg_d" && cp -a "$1/$_hg_d/." "$HG_STAGE/$_hg_d/" || { _hg_staged=0; break; }
  done
  if [ "$_hg_staged" != 1 ] || ! $HG_CMD_CHCON -R u:object_r:system_file:s0 "$HG_STAGE"; then
    $HG_CMD_UMOUNT "$HG_STAGE"
    hg_mount_status "${_hg_pre}FAILED: staging the payload; nothing mounted"; return 1
  fi
  _hg_done=""
  for _hg_m in $_hg_map; do
    _hg_dst="$HG_ROOT${_hg_m#*:}"
    if $HG_CMD_MOUNT -t overlay hg_payload -o "ro,lowerdir=$HG_STAGE/${_hg_m%%:*}:$_hg_dst" "$_hg_dst"; then
      _hg_done="$_hg_dst $_hg_done"
      continue
    fi
    _hg_left=""
    for _hg_u in $_hg_done; do
      if [ -z "$_hg_left" ] && $HG_CMD_UMOUNT "$_hg_u"; then continue; fi
      _hg_left="$_hg_left $_hg_u"
    done
    [ -z "$_hg_left" ] && $HG_CMD_UMOUNT "$HG_STAGE"
    hg_mount_status "${_hg_pre}FAILED: overlay on ${_hg_m#*:}; rolled back${_hg_left:+ (still mounted:$_hg_left)}"
    return 1
  done
  hg_mount_status "${_hg_pre}ok${_hg_there:+ (already there, mounted by something else, left alone:$_hg_there)}"
}

# Drops PackageManager's cached parse of our three APKs (entries named after the APK file or its
# directory as mounted: /system/priv-app/HyperGestures/ gives
# $HG_PKG_CACHE/<hash>/HyperGestures-<n>-<hash>, /system/priv-app/HyperGesturesNotif/ gives
# .../HyperGesturesNotif-<n>-<hash>, the overlay's single file
# /product/overlay/HyperGesturesOverlay.apk gives .../HyperGesturesOverlay.apk-<n>-<hash>).
# PackageManager reuses an entry while it is newer than the APK, and a metamodule's ext4 image (like
# our own tmpfs stage) gives the mounted APK a 1970 mtime, since the clock is not set yet at
# post-fs-data: an updated APK would keep the previous version's parse, services and all. Runs in
# post-fs-data, before PackageManager starts; only when the APKs changed (hg_drop_apk_caches).
hg_drop_package_cache() {
  rm -f "$HG_PKG_CACHE"/*/HyperGestures-* "$HG_PKG_CACHE"/*/"$HG_NOTIF_DIR"-* \
    "$HG_PKG_CACHE"/*/HyperGesturesOverlay.apk-* 2>/dev/null
  return 0
}

# Prints what identifies the installed APKs ($1 = module dir, $2 = mount mode): the module's
# versionCode, the mode, and each APK's path, size and mtime (one stat: the app, the notification
# hooks and the overlay). Nothing if stat fails.
hg_apk_stamp() {
  _hg_svc=""; _hg_sl=""
  if [ -f "$1/module.prop" ]; then
    while IFS= read -r _hg_sl || [ -n "$_hg_sl" ]; do
      case "$_hg_sl" in versionCode=*) _hg_svc="${_hg_sl#versionCode=}"; _hg_svc="${_hg_svc%%[!0-9]*}" ;; esac
      _hg_sl=""
    done < "$1/module.prop"
  fi
  _hg_sst="$($HG_CMD_STAT -c '%n %s %Y' "$1"/system/priv-app/HyperGestures/*.apk \
    "$1/system/priv-app/$HG_NOTIF_DIR"/*.apk "$1/overlay/$HG_OVERLAY_APK" 2>/dev/null)" || return 0
  printf 'versionCode=%s mode=%s\n%s\n' "$_hg_svc" "$2" "$_hg_sst"
}

# Drops the static overlay's idmap and our package-cache entries (hg_drop_package_cache) only when
# the APKs changed since the last boot ($1 = module dir, $2 = mount mode): the stamp in apk-stamp
# (hg_apk_stamp; customize.sh deletes it at every install) differs, or can't be taken. Unchanged
# APKs keep PackageManager's cached parse and the idmap, so a normal boot re-parses nothing of ours.
hg_drop_apk_caches() {
  _hg_stamp="$(hg_apk_stamp "$1" "$2")"
  if [ -n "$_hg_stamp" ] && [ -f "$HG_DATA/apk-stamp" ] && [ "$(cat "$HG_DATA/apk-stamp" 2>/dev/null)" = "$_hg_stamp" ]; then
    return 0
  fi
  rm -f "$HG_RESCACHE"/*HyperGesturesOverlay* 2>/dev/null
  hg_drop_package_cache
  mkdir -p "$HG_DATA"
  if [ -n "$_hg_stamp" ]; then
    printf '%s\n' "$_hg_stamp" > "$HG_DATA/apk-stamp"
    hg_log "post-fs-data: APKs new or changed: package cache and overlay idmap dropped"
  else
    rm -f "$HG_DATA/apk-stamp"
    hg_log "post-fs-data: no APK stamp (stat failed): package cache and overlay idmap dropped"
  fi
}

# Dates every file and directory under $1 (symlinks left alone) to now. customize.sh runs it on
# each install, and hg_app_version_check after a stale APK: the build's zip gives every entry the
# same fixed date, so two builds' APKs of equal size look identical to a metamodule that syncs
# system/ into its own image with a size+mtime quick check (hybrid_mount in ext4 mode), which then
# keeps the previous APK. touch -c creates nothing; owners, modes and SELinux labels stay as they
# are. Falls back to one touch per file where find has no `-exec {} +`. Returns 1 if any failed.
hg_redate_files() {
  [ -d "$1" ] || return 1
  find "$1" ! -type l -exec touch -c {} + 2>/dev/null ||
    find "$1" ! -type l -exec touch -c {} \; 2>/dev/null
}

# The versionCode PackageManager reports for our app (empty if it can't be read: not installed,
# pm failing or hanging). One `pm list packages` (a substring filter: only our exact name counts).
hg_app_version_code() {
  hg_timeout $HG_CMD_PM list packages --show-versioncode "$HG_PKG" 2>/dev/null |
    awk -v p="package:$HG_PKG" '{ sub(/\r$/, "") }
      $1 == p { for (i = 2; i <= NF; i++) if ($i ~ /^versionCode:[0-9]+$/) { print substr($i, 13); exit } }'
}

# Whether the running app is the APK this module ships ($1 = module dir): PackageManager's
# versionCode against module.prop's. A lower one means the system kept the previous APK; the
# likely cause, a metamodule's size+mtime sync skipping it (hg_redate_files), is unverified. Then
# it logs both APKs' size and mtime (the mounted one and the module's: what the metamodule serves),
# re-dates the module's files again, so the next boot's sync copies the new APK, deletes
# apk-stamp, so that boot drops the package cache too (hg_drop_apk_caches), and notifies under its
# own tag (hypergestures-update), so a later notice this boot does not replace it: reboot once
# more, or, from the second such boot in a row ($HG_DATA/stale-boots), that a reboot did not help.
# Never reboots. Once per active boot, from hg_service_main. An unreadable version is only logged;
# a higher one (a newer app sideloaded over ours, which no reboot changes) and a match are logged
# and end a stale-boot run.
hg_app_version_check() {
  _hg_avm=""
  if [ -f "$1/module.prop" ]; then
    while IFS= read -r _hg_avl || [ -n "$_hg_avl" ]; do
      case "$_hg_avl" in versionCode=*) _hg_avm="${_hg_avl#versionCode=}"; _hg_avm="${_hg_avm%%[!0-9]*}" ;; esac
      _hg_avl=""
    done < "$1/module.prop"
  fi
  [ -n "$_hg_avm" ] || return 0
  _hg_ava="$(hg_app_version_code)"
  if [ -z "$_hg_ava" ]; then
    hg_log "service: the app's versionCode could not be read from PackageManager; not compared with the module's ($_hg_avm)"
    return 0
  fi
  # Both are digits only (the awk and the read above), so -eq/-gt compare them as numbers.
  if [ "$_hg_ava" -eq "$_hg_avm" ]; then
    rm -f "$HG_DATA/stale-boots"; hg_log "service: app version matches the module ($_hg_ava)"; return 0
  fi
  if [ "$_hg_ava" -gt "$_hg_avm" ]; then # a newer app sideloaded over ours: no reboot changes it
    rm -f "$HG_DATA/stale-boots"; hg_log "service: app $_hg_ava is newer than the module $_hg_avm: left alone"; return 0
  fi
  _hg_avn="$(cat "$HG_DATA/stale-boots" 2>/dev/null)"
  case "$_hg_avn" in ''|*[!0-9]*) _hg_avn=0 ;; esac
  _hg_avn=$(( _hg_avn + 1 )); echo "$_hg_avn" > "$HG_DATA/stale-boots"
  HG_STALE_APK="app $_hg_ava, module $_hg_avm"; hg_publish_status # About: update not applied
  _hg_avp=/system/priv-app/HyperGestures/HyperGestures.apk
  hg_log "service: stale APK (boot $_hg_avn in a row): mounted $_hg_avp"     "$($HG_CMD_STAT -c '%s bytes, mtime %y' "$HG_ROOT$_hg_avp" 2>/dev/null || echo unreadable);"     "the module's $($HG_CMD_STAT -c '%s bytes, mtime %y' "$1$_hg_avp" 2>/dev/null || echo unreadable)"
  rm -f "$HG_DATA/apk-stamp"
  if hg_redate_files "$1"; then _hg_avr="module files re-dated"; else _hg_avr="re-dating the module files FAILED"; fi
  if [ "$_hg_avn" -gt 1 ]; then
    hg_log "service: app is $_hg_ava but the module is $_hg_avm: the system kept the previous APK again, after a reboot ($_hg_avr, apk-stamp deleted)"
    hg_notify "HyperGestures update still not applied after a reboot (app $_hg_ava, module $_hg_avm). Reinstall the module in KernelSU, or set MOUNT=self (setconf.sh) and reboot." hypergestures-update
  else
    hg_log "service: app is $_hg_ava but the module is $_hg_avm: the system kept the previous APK ($_hg_avr, apk-stamp deleted, so the next boot copies it; reboot once more)"
    hg_notify "HyperGestures update not applied yet (app $_hg_ava, module $_hg_avm): reboot once more." hypergestures-update
  fi
  return 0
}

# KernelSU unmounts module mounts in every app process whose App Profile says umount_modules, the
# default for non-root apps, so our own process would not find its APK under a metamodule's mounts
# ("ClassLoader referenced unknown path" and a crash in bindApplication, while SystemUI keeps
# retrying). Stores a profile for our package that keeps them. Needs a known uid (a fresh install
# gets one only once PackageManager has scanned it: service.sh tries again). Returns 0 only when it
# changed the profile and a re-read shows the change; 1 when it is already set, the package is
# unknown, the change did not stick, or there is no `ksud profile` (another root solution, whose
# own unmount rules then apply). Needed only in mixed mode (KernelSU-tracked mounts), harmless in
# self mode, so it runs in both (a later switch to mixed then finds it set).
hg_ksu_keep_mounts() {
  _hg_prof="$($HG_CMD_KSUD profile get "$HG_PKG" 2>/dev/null)" || return 1
  printf '%s\n' "$_hg_prof" | grep -q '"non_root"' || return 1 # a root profile: not ours to change
  printf '%s\n' "$_hg_prof" | grep -q '"umount_modules": *false' && return 1
  _hg_uid="$(printf '%s\n' "$_hg_prof" | sed -n 's/.*"uid": *\(-\{0,1\}[0-9]*\).*/\1/p' | head -n 1)"
  case "$_hg_uid" in ''|-*|0) return 1 ;; esac # the package is not known yet
  mkdir -p "$HG_DATA"
  printf '%s\n' "$_hg_prof" | sed -e 's/"stored": *false/"stored": true/' \
    -e 's/"use_default": *true/"use_default": false/' \
    -e 's/"umount_modules": *true/"umount_modules": false/' > "$HG_DATA/ksu-profile.json"
  if ! $HG_CMD_KSUD profile set "$HG_DATA/ksu-profile.json" >/dev/null 2>&1; then
    hg_log "KernelSU profile: could not set umount_modules false for $HG_PKG; its process may not see the mounted APK"
    return 1
  fi
  # `set` succeeding proves nothing if a later ksud spells a key differently (the sed edits above
  # then change nothing): only a re-read profile that keeps the mounts counts.
  if $HG_CMD_KSUD profile get "$HG_PKG" 2>/dev/null | grep -q '"umount_modules": *false'; then
    hg_log "KernelSU profile: $HG_PKG (uid $_hg_uid) now keeps module mounts (umount_modules false)"
    return 0
  fi
  hg_log_once "KernelSU profile: ksud profile set succeeded, but $HG_PKG's profile still does not say umount_modules false; its process may not see the mounted APK"
  return 1
}

# 0.3.5-alpha pointed config_recentsComponentName at our app with a fabricated overlay
# (com.android.shell:HyperGestures: a .frro file and its idmap in $HG_RESCACHE, which
# OverlayManager keeps, enabled, across reboots) and installed a guard in /data/adb/post-fs-data.d
# that deleted them while the module was disabled. That could not work (the "recents" permissions
# are granted before a mutable overlay applies; see the mounting section above), and neither is
# needed now: deletes all three if present (cheap; a no-op once gone). Only while system_server is
# not running (post-fs-data, uninstall.sh): it then starts without the overlay.
hg_cleanup_fab_overlay() {
  _hg_gone=""
  for _hg_f in "$HG_RESCACHE"/com.android.shell-HyperGestures-*.frro \
               "$HG_RESCACHE"/*@com.android.shell-HyperGestures-*.frro@idmap "$HG_OLD_OVERLAY_GUARD"; do
    [ -e "$_hg_f" ] || continue
    rm -f "$_hg_f" && _hg_gone="$_hg_gone ${_hg_f##*/}"
  done
  [ -z "$_hg_gone" ] || hg_log "0.3.5 leftovers deleted (fabricated recents overlay, post-fs-data.d guard):$_hg_gone"
  return 0
}

# post-fs-data.sh body ($1 = module dir): runs before zygote, in init's mount namespace, so
# system_server's package scan sees the mounts. Deletes 0.3.5's leftovers, then counts the boot for
# the boot-loop protection (hg_boot_guard; when it trips nothing else happens). Otherwise resolves
# the mount mode (hg_mount_resolve; recorded in mount-mode for service.sh and action.sh), writes
# skip_mount for it before any mount (whether the metamodule reads the flag before or after this
# script is unverified: both orders are safe, see hg_self_mount and hg_mount_check), drops the
# overlay idmap and our package-cache entries if the APKs changed (hg_drop_apk_caches), and
# self-mounts: the recents overlay in mixed mode (the metamodule mounts system/), system/ and the
# overlay in self mode (hg_self_mount). On a dormant boot (HOME is MiuiHome: marker dormant) no
# overlay, so SystemUI keeps MiuiHome's recents: nothing in mixed mode, system/ alone in self mode
# (the app stays installed for what works with any HOME). Nothing at all on a firmware mismatch
# under STRICT_FIRMWARE=1 (service.sh then disables the module, unless the boot is dormant) unless
# IGNORE_FINGERPRINT=1.
hg_post_fs_data_main() {
  hg_cleanup_fab_overlay
  hg_load_config
  rm -f "$HG_DATA/mount-mode" "$HG_DATA/dormant"
  hg_boot_guard "$1" || return 0
  hg_ksu_keep_mounts
  hg_mount_resolve "$1" "${MOUNT:-}"
  _hg_mode="$HG_MOUNT_MODE"
  if [ "$_hg_mode" = self ]; then _hg_pre=""; else _hg_pre="metamodule + overlay "; fi
  printf '%s %s %s %s\n%s\n' "$HG_MOUNT_REQ" "$_hg_mode" "${HG_MM_ID:--}" "${HG_MM_VC:--}" "$HG_MOUNT_DESC" > "$HG_DATA/mount-mode"
  hg_log "mount mode: $HG_MOUNT_DESC"
  hg_sync_skip_mount "$1" "$_hg_mode"
  hg_drop_apk_caches "$1" "$_hg_mode"
  hg_home_state
  [ "$HG_HOME_SRC" = home-role ] && hg_log "post-fs-data: roles.xml names no HOME holder; going by cmd role's answer at the last boot ($HG_HOME)"
  _hg_dw="dormant (HOME is $HG_MIUIHOME: stock HyperOS gestures and recents this boot)"
  if [ "$HG_HOME_STATE" = dormant ]; then
    echo "$HG_HOME" > "$HG_DATA/dormant"
    if [ "$_hg_mode" != self ]; then
      hg_mount_status "${_hg_pre}skipped: $_hg_dw"; return 0 # the metamodule mounts the app
    fi
  fi
  [ "$HG_HOME_STATE" = unknown ] && hg_log "post-fs-data: HOME unknown (${HG_ROLES:-no roles.xml} unreadable or without a HOME holder): active boot"
  _hg_why="$(hg_firmware_problem "$1")"
  if [ -n "$_hg_why" ] && [ "${STRICT_FIRMWARE:-0}" = 1 ] && [ "${IGNORE_FINGERPRINT:-0}" != 1 ]; then
    if [ "$HG_HOME_STATE" = dormant ]; then
      hg_mount_status "skipped: $_hg_dw, the app too: $_hg_why (STRICT_FIRMWARE=1)"
    else
      hg_mount_status "${_hg_pre}skipped: $_hg_why (STRICT_FIRMWARE=1)"
    fi
    return 0
  fi
  _hg_ns="$(readlink /proc/self/ns/mnt 2>/dev/null)"; _hg_ns1="$(readlink /proc/1/ns/mnt 2>/dev/null)"
  [ -n "$_hg_ns" ] && [ -n "$_hg_ns1" ] && [ "$_hg_ns" != "$_hg_ns1" ] &&
    hg_log "post-fs-data: not in init's mount namespace ($_hg_ns vs $_hg_ns1); zygote may not see the payload"
  if [ "$HG_HOME_STATE" = dormant ]; then
    hg_log "post-fs-data: $_hg_dw: the app alone is mounted (no recents overlay)"
    _hg_mode=app
  fi
  hg_self_mount "$1" "$_hg_mode"
}

# service.sh body. $1 = module dir. Changes nothing on the phone unless the undo guard is in place
# and SystemUI has bound our provider: without the system/ tree or the recents overlay mounted (no
# working metamodule, or hg_self_mount failed) or with a permission problem it never binds, and
# disabling MiuiHome or switching nav mode would only cost navigation. Then the health check
# watches the result and rolls back if it is broken (hg_health_check), and the watchdog keeps
# watching. On another HyperOS build than the APK was compiled against it notifies and relies on
# the health check; with
# STRICT_FIRMWARE=1 it disables itself instead (hg_firmware_refuse), unless IGNORE_FINGERPRINT=1.
# A boot-loop trip (hg_boot_guard in post-fs-data) is rolled back here if the guard hasn't. When
# the provider never binds while SystemUI or our package crash-loops (e.g. after an OTA), it rolls
# back too: nothing was set up, but the mounted APK may be what breaks SystemUI. When MOUNT=auto
# chose mixed and the metamodule did not mount the APK, the next boot mounts it itself
# (hg_mount_check). A dormant boot (HOME is MiuiHome) sets nothing up (hg_dormant_boot).
hg_service_main() {
  hg_wait_boot
  mkdir -p "$HG_DATA"
  hg_boot_finished "boot_completed reached" # whatever happens next, this boot was not a boot loop
  # After it: an earlier version touched the app's log copy here, through /sdcard's FUSE mount,
  # which may block this early.
  hg_rotate_log
  hg_log_version "$1"
  hg_home_crosscheck # before the watchdog's HOME watch reads home-role
  # The running version for the app's About (Settings.Global: the provider is up now); an update
  # customize.sh noted for it is the running one from this boot on.
  hg_publish_version "$1" && hg_global_del hypergestures_module_update
  # From here on action.sh must not show the previous boot's result as this boot's.
  hg_health_status starting "pending: waiting for the provider (this boot's setup has not finished)"
  # Per-boot records: the log-once list and the first SystemUI dump's size. The crash-* files are
  # 0.3.8's count baselines and window, left from an upgrade (the crash watch keeps no files now).
  rm -f "$HG_DATA/log-once" "$HG_DATA/sysui-dump" \
    "$HG_DATA/crash-boot" "$HG_DATA/crash-setup" "$HG_DATA/crash-setup-up" "$HG_DATA/crash-ring" \
    "$HG_DATA/mirror-pid" "$HG_DATA/mirror-rc" # an earlier boot's log copy job, if any
  # A fresh install gets its uid only now: its process started without the mounts and crashed, so
  # stop it and let SystemUI's next bind attempt start one that sees them; the crash watch's
  # reference below is set after that and leaves those crashes out.
  hg_ksu_keep_mounts && $HG_CMD_AM force-stop "$HG_PKG" >/dev/null 2>&1
  hg_grant_notifications # before this boot's first hg_notify
  hg_crash_reset
  hg_install_guard "$1"; _hg_guard=$?
  if [ "$_hg_guard" != 0 ]; then
    hg_log "setup aborted: the undo guard could not be installed; nothing changed"
    if hg_bootloop_claim; then # the rollback needs no guard
      hg_rollback "$HG_BOOT_TRIES boots in a row did not finish starting up" "$1" "$(hg_bootloop_message 0)" noguard
    else
      hg_health_status not-set-up "not run: the undo guard could not be installed, nothing changed"
      hg_notify "HyperGestures did not set up: its undo guard could not be installed, so nothing was changed. See $HG_DATA/log.txt"
    fi
    return 0
  fi
  # Running again: a later disable must undo again, with a fresh retry budget.
  rm -f "$HG_DATA/guard-restored" "$HG_DATA/restore-gave-up" "$HG_DATA/restore-attempts"
  # Per-boot watchdog state: restore.sh's "restored" marker, setconf's watchdog-stop and the
  # gestural re-apply count.
  rm -f "$HG_DATA/restored" "$HG_DATA/watchdog-stop" "$HG_DATA/gestural-reapplies"
  hg_drop_stale_locks
  hg_ensure_config "$1"
  hg_load_config
  : "${NAV_MODE:=gestural}" "${DISABLE_MIUIHOME:=1}" "${WATCHDOG:=1}" "${IGNORE_FINGERPRINT:=0}" "${STRICT_FIRMWARE:=0}"
  hg_log "boot: NAV_MODE=$NAV_MODE DISABLE_MIUIHOME=$DISABLE_MIUIHOME WATCHDOG=$WATCHDOG" \
    "IGNORE_FINGERPRINT=$IGNORE_FINGERPRINT STRICT_FIRMWARE=$STRICT_FIRMWARE"
  if hg_bootloop_claim; then
    hg_rollback "$HG_BOOT_TRIES boots in a row did not finish starting up" "$1" "$(hg_bootloop_message 0)"
    hg_sync_skip_mount "$1" "$(hg_mount_mode "$1" "${MOUNT:-}")"
    return 0
  fi
  if [ -f "$HG_DATA/dormant" ]; then hg_dormant_boot "$1"; return 0; fi
  hg_app_version_check "$1" # an update whose APK the system did not take: one more reboot

  _hg_why="$(hg_firmware_problem "$1")"
  if [ -n "$_hg_why" ]; then
    if [ "$STRICT_FIRMWARE" != 1 ]; then
      hg_log "$_hg_why; setting up anyway (STRICT_FIRMWARE=0), the health check decides"
      # Once per firmware: the next boots on the same build only log it.
      _hg_head="$(hg_firmware_head "$1")"
      if [ "$(cat "$HG_DATA/firmware-notified" 2>/dev/null)" != "$_hg_head" ]; then
        hg_notify "$_hg_head HyperGestures will run a health check and disable itself if anything is wrong."
        echo "$_hg_head" > "$HG_DATA/firmware-notified"
      fi
    elif [ "$IGNORE_FINGERPRINT" != 1 ]; then
      hg_firmware_refuse "$1" "$_hg_why"
      hg_health_status refused "not run: firmware refused (STRICT_FIRMWARE=1)"
      return 0
    else
      hg_log "$_hg_why; ignored (IGNORE_FINGERPRINT=1), setting up anyway"
    fi
  fi
  if hg_mount_check "$1"; then
    # Without the APK the provider can't bind: no need to wait for it.
    hg_not_set_up "the metamodule $HG_MM_ID did not mount the app; from the next boot the module mounts it itself (MOUNT=auto), so reboot"
    hg_health_status not-set-up "not run: the metamodule $HG_MM_ID did not mount the app (the next boot mounts it itself)"
    return 0
  fi
  if ! hg_wait_provider 60; then
    if hg_crash_scan "since boot while the provider never bound"; then
      _hg_why="$HG_CRASH_WHY"
      # Only our mounted APK can be the cause. With it not mounted the crash loop is not ours to
      # roll back: log it and fall through to the ordinary "not set up" path (module stays enabled).
      if [ -e "$HG_ROOT/system/priv-app/HyperGestures" ]; then
        hg_rollback "$_hg_why" "$1"
        hg_after_rollback "$1"
        return 0
      fi
      hg_log "crash loop seen ($_hg_why) but $HG_ROOT/system/priv-app/HyperGestures is not mounted, so it is not ours; no rollback"
    fi
    hg_not_set_up "provider not bound 60 s after boot"
    hg_health_status not-set-up "not run: provider not bound 60 s after boot, nothing set up"
    return 0
  fi
  hg_log "provider bound"
  if [ -f "$1/disable" ] || [ -f "$1/remove" ]; then
    # Disabled (or marked for removal) in KernelSU while this boot waited: the guard undoes at
    # the next boot, so set nothing up for it to race with.
    hg_log "setup skipped: the module was disabled or marked for removal during boot"
    hg_health_status not-set-up "not run: the module was disabled or marked for removal during boot"
    return 0
  fi
  hg_crash_reset
  hg_record_nav_state
  [ "$DISABLE_MIUIHOME" = 1 ] && hg_disable_miuihome
  hg_power_exempt
  hg_apply_nav_mode "$NAV_MODE"
  rm -f "$HG_DATA/dormant-restored" # a later dormant boot puts back what this one changed
  hg_health_check "$1" || { hg_after_rollback "$1"; return 0; }
  [ "$WATCHDOG" = 1 ] || return 0
  hg_home_init
  hg_watchdog_loop "$1"
  [ "$HG_WD" = rollback ] && hg_after_rollback "$1"
  return 0
}
