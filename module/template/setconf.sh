#!/system/bin/sh
# Sets one HyperGestures setting, whether or not /data/adb/hypergestures/config has a line for it
# yet (creates the config from config.default if there is none). Takes effect at the next boot.
#   adb shell su -c "sh /data/adb/modules/hypergestures/setconf.sh NAV_MODE threebutton"
# Settings (see config.default): NAV_MODE gestural|fsg|threebutton|keep, DISABLE_MIUIHOME 0|1,
# WATCHDOG 0|1 (0 also stops a running watchdog at its next check), STRICT_FIRMWARE 0|1,
# IGNORE_FINGERPRINT 0|1, MOUNT auto|metamodule|self (sets or removes the module's skip_mount flag
# for the mode it resolves to now, and lets auto try the metamodule again; reboot to apply),
# HEALTH_TEST fail|off (fail: the next boot's health check fails once and rolls back, then the key
# removes itself). Without arguments it prints the config.
MODDIR="${0%/*}"
. "$MODDIR/common/functions.sh"
if [ "$#" -eq 0 ]; then
  if [ -f "$HG_DATA/config" ]; then cat "$HG_DATA/config"; else echo "no $HG_DATA/config yet: the defaults in $MODDIR/config.default apply"; fi
  exit 0
fi
hg_setconf "$MODDIR" "$@"
