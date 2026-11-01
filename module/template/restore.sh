#!/system/bin/sh
# Undo HyperGestures' changes now, while the phone is up. Run it BEFORE disabling or
# uninstalling the module:
#   adb shell su -c "sh /data/adb/modules/hypergestures/restore.sh"
# Releases MiuiHome's background restriction and enables it, puts the navbar overlay and
# force_fsg_nav_bar back to what they were before the module first changed them
# (/data/adb/hypergestures/original-nav.txt), gives HOME back to MiuiHome (com.miui.home), and
# marks "restored" (with this boot's id) so service.sh sets nothing up, its health check stops and
# the watchdog stops re-applying gestural navigation until the next boot. The marker goes down
# BEFORE the restore, so a watchdog or setup step running meanwhile sees it and leaves (or puts
# back) what this restores; a failed restore removes it again.
MODDIR="${0%/*}"
. "$MODDIR/common/functions.sh"
if [ -f "$HG_DATA/original-nav.txt" ]; then
  echo "recorded original nav: FSG=$(hg_recorded FSG) NAVBAR=$(hg_recorded NAVBAR)"
else
  echo "no recorded nav state: the module never changed navigation"
fi
mkdir -p "$HG_DATA" && hg_boot_id > "$HG_DATA/restored"
rc=0
hg_restore_all || rc=1
hg_restore_home || rc=1
if [ "$rc" != 0 ]; then
  rm -f "$HG_DATA/restored"
  echo "restore FAILED: see $HG_DATA/log.txt and the README (Safety and recovery)" >&2
  exit 1
fi
echo "now: HOME=$($HG_CMD_ROLE get-role-holders android.app.role.HOME 2>/dev/null)"
echo "now: force_fsg_nav_bar=$($HG_CMD_SETTINGS get global force_fsg_nav_bar 2>/dev/null)"
$HG_CMD_OVERLAY list 2>/dev/null | grep navbar
echo "restored. Now disable or uninstall the module in KernelSU, then reboot."
echo "(Rebooting with the module still enabled makes service.sh apply its changes again.)"
