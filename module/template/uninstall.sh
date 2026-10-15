#!/system/bin/sh
# KernelSU runs this at the NEXT boot's post-fs-data after the module was marked for removal,
# then deletes the module dir. system_server is not running yet, so stray files of 0.3.5-alpha's
# fabricated recents overlay (and its post-fs-data.d guard) are deleted right away. pm, cmd overlay
# and settings have no system services yet, so the undo runs in the background once boot completes
# (hg_restore_after_boot), and only after it succeeds are /data/adb/hypergestures and the guard
# removed. The functions are sourced now, before the module dir disappears.
MODDIR="${0%/*}"
. "$MODDIR/common/functions.sh"
hg_cleanup_fab_overlay
if [ -n "${HG_SYNC:-}" ]; then # tests: run in the foreground
  hg_restore_after_boot cleanup
  exit 0
fi
( trap '' HUP; hg_restore_after_boot cleanup ) </dev/null >/dev/null 2>&1 &
exit 0
