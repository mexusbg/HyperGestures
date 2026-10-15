#!/system/bin/sh
# HyperGestures disable-safe guard. The module's service.sh installs this file as
# /data/adb/service.d/hypergestures-guard.sh, and KernelSU runs every script there at each boot's
# late_start service whether or not the module is enabled (KernelSU safe mode runs no scripts at
# all, this one included). While the module is enabled it does nothing. When the module is
# disabled or marked for removal it restores MiuiHome, the original navigation and HOME
# (com.miui.home) once; when the module is gone it restores, then deletes /data/adb/hypergestures
# and itself.
# The logic is hg_guard_run in the copy of functions.sh that service.sh keeps next to the state.
: "${HG_DATA:=/data/adb/hypergestures}"
: "${HG_MODDIR:=/data/adb/modules/hypergestures}"
if [ ! -f "$HG_DATA/functions.sh" ]; then
  # No state left to restore from; once the module is gone too, nothing needs this guard.
  [ -d "$HG_MODDIR" ] || rm -f "$0"
  exit 0
fi
. "$HG_DATA/functions.sh"
if [ -n "${HG_SYNC:-}" ]; then # tests: run in the foreground
  hg_guard_run "$HG_MODDIR"
  exit 0
fi
# Waits for boot_completed, so never block KernelSU's script runner.
( hg_guard_run "$HG_MODDIR" ) </dev/null >/dev/null 2>&1 &
exit 0
