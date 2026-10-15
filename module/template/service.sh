#!/system/bin/sh
# KernelSU late_start service (runs only while the module is enabled). After boot: installs the
# disable-safe guard, waits for SystemUI to bind our provider, and only then stops MiuiHome and
# applies NAV_MODE; then runs the health check (rolls back and disables the module if it fails)
# and the watchdog. See hg_service_main in common/functions.sh.
MODDIR="${0%/*}"
. "$MODDIR/common/functions.sh"
hg_service_main "$MODDIR"
hg_mirror_sync now # the app's log copy (Save logs) as this boot ends its setup or watchdog
