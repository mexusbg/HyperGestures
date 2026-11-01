# KernelSU's module installer sources this file (it is not run on its own) after extracting the
# module into $MODPATH (/data/adb/modules_update/hypergestures for an update), so: no `exit`, output
# through ui_print. It makes post-fs-data's mount-mode decision now (hg_mount_resolve on MOUNT from
# /data/adb/hypergestures/config; auto when unset) and sets or leaves out skip_mount for it: an
# update is installed without the previous version's skip_mount flag, and whether a metamodule
# reads the flag before or after our post-fs-data runs is unverified, so the first boot after an
# install must already have the right one. post-fs-data.sh keeps it in step from then on
# (hg_sync_skip_mount). It also deletes the APK stamp, so that boot drops the package cache and the
# overlay idmap (hg_drop_apk_caches). And it dates every extracted file to now (hg_redate_files):
# the zip's entries all carry the build's fixed date, and a metamodule that syncs system/ by size
# and mtime would keep the previous APK when the new one has the same size. And it notes the
# installed version for the app as Settings.Global hypergestures_module_update (the phone runs
# while it installs), so its About page names the update until the reboot (hg_publish_version).
command -v ui_print >/dev/null 2>&1 || ui_print() { echo "$1"; }
if [ -f "$MODPATH/common/functions.sh" ]; then
  . "$MODPATH/common/functions.sh"
  if hg_redate_files "$MODPATH"; then
    ui_print "- files dated to the install time, so a metamodule's sync cannot keep the previous APK"
  else
    ui_print "! some files could not be dated to the install time: the metamodule may keep the previous APK"
  fi
  rm -f "$HG_DATA/apk-stamp" "$HG_DATA/stale-boots" 2>/dev/null # stale-boots: hg_app_version_check
  # The status taken with ||, so an installer that runs with set -e never stops here.
  _hg_rc=0; hg_publish_version "$MODPATH" update || _hg_rc=$?
  case $_hg_rc in
    0) ui_print "- the app's About page names $_hg_vv ($_hg_vvc) as installed until the reboot" ;;
    1) ;; # module.prop names no version: nothing to note
    *) ui_print "! the app's About page can't name this update (see the log) until the reboot" ;;
  esac
  hg_mount_resolve "$MODPATH" "$(hg_config_mount)" # MOUNT as post-fs-data will read it
  if [ "$HG_MOUNT_MODE" = self ]; then
    if hg_sync_skip_mount "$MODPATH" self; then
      ui_print "- mount: $HG_MOUNT_DESC; skip_mount set, so no metamodule mounts the module's files too"
    else
      ui_print "! mount: $HG_MOUNT_DESC, but $MODPATH/skip_mount could not be created: a metamodule may mount the module's files at the next boot as well"
    fi
  elif hg_sync_skip_mount "$MODPATH" metamodule; then
    ui_print "- mount: $HG_MOUNT_DESC; the metamodule mounts system/, the module mounts the recents overlay"
  else
    ui_print "! mount: $HG_MOUNT_DESC, but $MODPATH/skip_mount could not be removed: the metamodule will not mount system/ at the next boot"
  fi
else
  ui_print "! $MODPATH/common/functions.sh is missing: skip_mount is left to the first boot's post-fs-data.sh"
fi
