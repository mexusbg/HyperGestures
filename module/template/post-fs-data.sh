#!/system/bin/sh
# KernelSU post-fs-data (runs only while the module is enabled, before zygote): counts the boot for
# the boot-loop protection (3 boots in a row that never reach boot_completed disable the module);
# resolves MOUNT (auto: mixed with an active metamodule, else self) and keeps skip_mount in step;
# mounts the recents overlay (overlay/HyperGesturesOverlay.apk) on /product/overlay itself, and in
# self mode the system/ tree too (in mixed mode the metamodule mounts system/); drops the package
# cache only when the APKs changed; deletes 0.3.5-alpha's leftovers. See hg_post_fs_data_main in
# common/functions.sh.
MODDIR="${0%/*}"
. "$MODDIR/common/functions.sh"
hg_post_fs_data_main "$MODDIR"
