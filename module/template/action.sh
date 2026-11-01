#!/system/bin/sh
# KernelSU action button: print a status summary.
MODDIR="${0%/*}"
. "$MODDIR/common/functions.sh"
hg_load_config || echo "config: $HG_DATA/config does not parse as shell; every setting at its default"
_act_battery() {
  hg_power_get
  _act_gz="$($HG_CMD_DUMPSYS greezer 2>/dev/null | grep -A3 "\[$HG_PKG\]" | grep -m1 'latestNoControl')"
  case "$HG_BGC" in
    noRestrict) echo "battery: No restrictions (HyperOS does not freeze the app)" ;;
    '')
      case "$_act_gz" in
        *NO_RESTRICT_APP*) echo "battery: No restrictions (Greeze; PowerKeeper's table can't be read yet)" ;;
        *) echo "battery: unknown (PowerKeeper has no entry or can't be read)" ;;
      esac ;;
    *) echo "battery: $HG_BGC (HyperOS may freeze the app briefly after screen-on; the next active boot sets No restrictions)" ;;
  esac
  [ -f "$HG_DATA/power-pending" ] && echo "battery: pending (PowerKeeper was not ready at setup; retrying)"
  [ -n "$_act_gz" ] && echo "greezer:${_act_gz#*latestNoControl:}"
}
echo "== module"
if [ -f "$MODDIR/remove" ]; then
  echo "module: marked for removal (uninstalled at the next boot)"
elif [ -f "$MODDIR/disable" ]; then
  echo "module: DISABLED (not loaded from the next boot; re-enable: ksud module enable hypergestures)"
else
  echo "module: enabled"
fi
echo "== firmware"
echo "expected: $(hg_expected_fingerprint "$MODDIR")"
echo "phone:    $(hg_phone_fingerprint)"
hg_firmware_status "$MODDIR"
# post-fs-data's per-boot records (mount-mode, mount-status, the marker dormant) belong to this
# boot only when mount-mode names this boot's id (its third line). Otherwise they are an earlier
# boot's (post-fs-data did not run: the module was disabled or removed at boot, or KernelSU's
# safe mode) and are not shown as this boot's. A record without the id (an older module) counts
# while the module is enabled.
hg_rec_boot="$(sed -n 3p "$HG_DATA/mount-mode" 2>/dev/null)"
hg_off=""; { [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ]; } && hg_off=1
if [ -n "$hg_rec_boot" ]; then
  hg_fresh=0; [ "$hg_rec_boot" = "$(hg_boot_id)" ] && hg_fresh=1
elif [ -n "$hg_off" ]; then hg_fresh=0
else hg_fresh=1; fi
hg_stale="no record this boot (post-fs-data did not run: module disabled or removed, or safe mode)"
echo "== home"
# HOME now (roles.xml, else cmd role) against this boot (post-fs-data's marker dormant).
hg_home_state live
if [ "$hg_fresh" = 0 ]; then hg_boot=stale
elif [ -f "$HG_DATA/dormant" ]; then hg_boot=dormant; else hg_boot=active; fi
case "$HG_HOME_STATE/$hg_boot" in
  */stale) echo "home: ${HG_HOME:-unknown}; this boot: $hg_stale" ;;
  dormant/dormant) echo "home: $HG_HOME → dormant (reboot after choosing another launcher)" ;;
  dormant/active) echo "home: $HG_HOME → dormant from the next boot (this boot is active: reboot to hand gestures back to MiuiHome)" ;;
  active/active) echo "home: $HG_HOME → active" ;;
  active/dormant) echo "home: $HG_HOME → active from the next boot (this boot is dormant: reboot)" ;;
  *) echo "home: unknown (no HOME holder in roles.xml or from cmd role) → active" ;;
esac
[ -n "$HG_HOME_SRC" ] && echo "home from: $HG_HOME_SRC"
echo "== mount"
# mount-mode (post-fs-data, this boot): "REQ MODE ID VC", the description, the boot id.
if [ "$hg_fresh" = 1 ]; then
  hg_mode_now="$(sed -n 2p "$HG_DATA/mount-mode" 2>/dev/null)"
  hg_nr="no record this boot (post-fs-data never ran, or the boot-loop protection tripped)"
  echo "mount mode: ${hg_mode_now:-$hg_nr}"
else
  hg_mode_now=""; echo "mount mode: $hg_stale"
fi
if [ -n "$hg_off" ]; then
  echo "next boot: nothing mounted (module disabled or marked for removal)"
else
  hg_mount_resolve "$MODDIR" "${MOUNT:-}"
  [ "$HG_MOUNT_DESC" = "$hg_mode_now" ] || echo "next boot: $HG_MOUNT_DESC"
fi
[ -f "$HG_DATA/metamodule-missed" ] && echo "metamodule-missed: $(cat "$HG_DATA/metamodule-missed") (did not mount the app; auto uses self until it changes or setconf.sh MOUNT auto)"
if [ "$hg_fresh" = 1 ]; then
  echo "mount: $(cat "$HG_DATA/mount-status" 2>/dev/null ||
    echo "no record (post-fs-data never ran?)")"
else
  echo "mount: $hg_stale"
fi
hg_apk=missing; [ -e "$HG_ROOT/system/priv-app/HyperGestures" ] && hg_apk=yes
hg_ovl=missing; [ -f "$HG_ROOT/product/overlay/$HG_OVERLAY_APK" ] && hg_ovl=yes
if [ "$hg_apk$hg_ovl" = yesyes ]; then
  echo "mounted: yes (APK and overlay)"
else
  echo "mounted: NO (APK /system/priv-app/HyperGestures: $hg_apk, overlay /product/overlay/$HG_OVERLAY_APK: $hg_ovl)"
fi
# The notification hooks' APK comes with the app's system/priv-app tree; LSPosed loads it only
# where the owner enabled the module.
hg_ntf=missing; [ -e "$HG_ROOT/system/priv-app/$HG_NOTIF_DIR" ] && hg_ntf=yes
echo "notification hooks: /system/priv-app/$HG_NOTIF_DIR: $hg_ntf" \
  "(LSPosed module com.hypergestures.notif)"
echo "== health"
echo "health: $(cat "$HG_DATA/health-status" 2>/dev/null || echo "no record (service.sh never got to it)")"
hg_firmware_policy
echo "unfinished boots in a row: $(cat "$HG_DATA/boot-attempts" 2>/dev/null || echo 0) (at $HG_BOOT_TRIES the module disables itself)"
echo "== provider"
if hg_sysui_dump | hg_provider_connected; then echo "bound: yes"; else echo "bound: NO"; fi
# The app's own word (hg_app_fact), which the checks go by first.
for hg_k in "$HG_FACT_BOUND" "$HG_FACT_MONITOR"; do
  if hg_app_fact "$hg_k"; then echo "$hg_k: yes (pid $HG_FACT_PID, this boot)"
  else echo "$hg_k: no valid value for this boot (the checks go by the dumps)"; fi
done
cat "$HG_DATA/sysui-dump" 2>/dev/null || echo "sysui dump: no record"
echo "== overlay"
$HG_CMD_OVERLAY list android 2>/dev/null | grep -F com.hypergestures.overlay || echo "com.hypergestures.overlay: not listed"
echo "lookup: $($HG_CMD_OVERLAY lookup android android:string/config_recentsComponentName 2>&1)"
echo "== permissions"
$HG_CMD_DUMPSYS package "$HG_PKG" | grep -E "MONITOR_INPUT|MANAGE_ACTIVITY_TASKS|STATUS_BAR_SERVICE|ACCESS_CONTEXTUAL_SEARCH" | head -8
echo "== nav"
echo "force_fsg_nav_bar=$($HG_CMD_SETTINGS get global force_fsg_nav_bar)"
$HG_CMD_OVERLAY list | grep navbar
echo "== MiuiHome"
$HG_CMD_PM list packages -d | grep -q '^package:com.miui.home$' && echo disabled || echo enabled
echo "background: $($HG_CMD_APPOPS get com.miui.home RUN_ANY_IN_BACKGROUND 2>&1)"
echo "== battery"
_act_battery
echo "== undo"
if [ -x "$HG_GUARD" ]; then echo "guard: installed ($HG_GUARD)"; else echo "guard: MISSING ($HG_GUARD)"; fi
if [ -f "$HG_DATA/original-nav.txt" ]; then
  echo "recorded original nav: FSG=$(hg_recorded FSG) NAVBAR=$(hg_recorded NAVBAR)"
else
  echo "recorded original nav: none (nothing changed yet)"
fi
[ -f "$HG_DATA/restore-gave-up" ] && echo "restore: GAVE UP after $(cat "$HG_DATA/restore-attempts" 2>/dev/null) failed boots - restore by hand (runbook section 5)"
echo "== log (last 20)"
tail -n 20 "$HG_DATA/log.txt" 2>/dev/null
