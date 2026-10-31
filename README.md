# HyperGestures

Stock-feeling HyperOS gestures and recents with the launcher you choose.

On HyperOS, full-screen gestures and the recents screen live inside MiuiHome, the system
launcher. Set Nova (or any other launcher) as home and HyperOS takes the gestures away or gives
you a broken recents screen. HyperGestures fixes that: it puts its own recents and gesture
provider, built from AOSP Launcher3 (`android-17.0.0_r1`), where MiuiHome's used to be. Your
launcher stays home; swipe up, quick switch, recents and back work and look like stock HyperOS.

> **Status: alpha.** It is in daily use on one phone (Xiaomi 17 Ultra, HyperOS 4.0). It needs
> root and changes how SystemUI runs. Read [Safety and recovery](#safety-and-recovery) before
> installing.

## Features

- **Gestures with any launcher:** swipe up for home, swipe up and hold for recents, swipe along
  the bottom to switch apps, edge swipes for back.
- **HyperOS-style recents:** horizontal, vertical (two-column grid) or stacked cards, matching
  MiuiHome's sizes and motion, with the memory line, clear-all, lock, split screen, floating
  window and App info actions, and frosted-glass blur where the phone supports it.
- **MIUI back arrow:** the HyperOS bulge and chevron and MiuiHome's rule for when a swipe counts
  as back. Vibration follows the system touch-feedback setting.
- **Corner gestures** with MiuiHome's hover animation (assistant, Circle to Search).
- **System extras that work with any home app, MiuiHome included:**
  - hide the gesture line;
  - open App info the HyperOS way from every app, so Back from App permissions returns to App
    info (switchable);
  - a notification icon fix (an optional LSPosed module, `com.hypergestures.notif`).
- **Stays out of the way:** while MiuiHome is home, the module goes dormant at the next boot and
  HyperOS runs as stock.
- **Battery first:** no polling, nothing running while idle.

Settings are in the HyperGestures app in your app list. The module's Action button in KernelSU
prints a status summary.

## Requirements

- **Phone:** Xiaomi 17 Ultra (`nezha`) on HyperOS 4.0 (Android 17). Each build is made for the
  firmware it was built against (see `tools/framework-stub/device-build.txt`). On another
  firmware build the module still sets up, posts a notification, and relies on the boot health
  check: if gestures don't come up, it rolls back to stock. Set `STRICT_FIRMWARE=1` in the
  module config to refuse other firmware outright.
- **Root:** KernelSU, with a module mount (a metamodule such as Hybrid Mount works).
- **Home:** a third-party launcher set as the default home app.
- **Optional:** LSPosed (libxposed API 102) for the notification icon fix. Enable
  "HyperGestures notification icons" for System UI and AOD, then reboot.

## Install

1. In HyperOS, keep the gesture line visible in the System launcher's settings. If it is hidden
   there, Circle to Search stops working. To hide the line, use HyperGestures' own "Hide gesture
   line" setting instead.
2. Set your launcher as the default home app.
3. Download `HyperGestures-<version>.zip` from [Releases](../../releases), install it in KernelSU
   and **reboot fully** (not a soft reboot).

Updating works the same way: install the new zip over the old one, then reboot.

## Switching home apps

- **From the System launcher (MiuiHome) to another launcher:** reboot after the switch. While
  MiuiHome is home, the module stays dormant and only turns on at the next boot.
- **Between third-party launchers:** once the module is active, switch any time, no reboot
  needed.
- **Back to the System launcher:** the app tells you to reboot. After the reboot the module is
  dormant and HyperOS runs its own gestures.

## Safety and recovery

The module checks itself at every boot. If the gesture provider doesn't come up, SystemUI crash
loops, or boot doesn't complete three times in a row, it rolls back on its own: it gives home
back to MiuiHome, restores the navigation mode and disables itself.

To remove it cleanly:

1. While the phone is up, run
   `adb shell su -c sh /data/adb/modules/hypergestures/restore.sh`.
2. Uninstall the module in KernelSU and reboot.

If the screen has no working navigation, use adb: run `restore.sh` as above, or disable the
module with `adb shell su -c touch /data/adb/modules/hypergestures/disable` and reboot. KernelSU
safe mode also boots without the module.

Before a firmware update (OTA), the safest route is to run `restore.sh` and disable the module,
update, then install a build made for the new firmware. Small OTAs often keep working, but
Xiaomi may change SystemUI internals the module depends on.

## How it works

- **KernelSU module** (`module/template/`): mounts the app as a privileged system app, points
  SystemUI's recents component at it through a resource overlay, switches the navigation mode,
  and checks health at boot.
- **App** `com.hypergestures` (`hg/`, `modules/launcher/`): an AOSP Launcher3 QuickStep fork
  running without a home screen of its own. SystemUI binds its `TouchInteractionService`, as it
  would MiuiHome's.
- **Notification hooks** `com.hypergestures.notif` (`hg/notif/`, `modules/notif/`): a small,
  separate LSPosed module APK.
- **Overlay** (`overlay/`): a static resource overlay for SystemUI's recents component.

The module and the app exchange state only through `Settings.Global`. Root never reads or
writes the app's data folders.

Every change to the vendored AOSP sources is marked with a `// HyperGestures:` comment that
says why.

## Building

You need:

- JDK 17+.
- The Android SDK with platform `android-37.0` (set `sdk.dir` in `local.properties`).
- Python 3.11+ as `python` (or set `HG_PYTHON`).
- The device files in `prebuilts/device/`: `framework.jar` (a compile-only stub),
  `framework-res.apk` and `framework-classes.txt`, made from the phone with
  `tools/framework-stub/make-framework-stub.sh`, and the firmware's binder tables
  `hyperos4-sysui-aidl-tables.txt` and `hyperos4-shell-aidl-tables.txt` (unit-test input). They
  come from Xiaomi's firmware, so they are git-ignored and never committed.
- A signing key. Local builds use `keystore/hypergestures.jks` (git-ignored; alias, store and
  key password `hypergestures`). Create one with:

  ```sh
  keytool -genkeypair -keystore keystore/hypergestures.jks -alias hypergestures \
    -keyalg RSA -keysize 2048 -validity 10000 -storepass hypergestures \
    -keypass hypergestures -dname "CN=dev"
  ```

  Or point `HG_KEYSTORE`, `HG_KEYSTORE_PASSWORD`, `HG_KEY_ALIAS` and `HG_KEY_PASSWORD` at
  another key.

Then run:

```sh
bash tools/build-module.sh
```

The script builds the APKs and runs every check: unit tests, the module's shell test suite and
the Python tool tests. It writes the module zip to `out/HyperGestures-<version>.zip`. On Windows
it runs the shell suite in WSL when WSL is available, which takes seconds instead of most of an
hour.

## CI and releases

GitHub Actions ([.github/workflows/build.yml](.github/workflows/build.yml)) builds and tests
every push and pull request, signs the APKs with the release key, and uploads the module zip as
a build artifact. The firmware files come from a separate private repository that CI reads with
a deploy key.

To publish a release:

1. Bump `version` and `versionCode` in [version.properties](version.properties) and commit.
2. Tag the commit `v<version>` and push the branch and the tag:

   ```sh
   git tag v0.3.9.47-alpha
   git push origin master v0.3.9.47-alpha
   ```

CI checks that the tag matches `version.properties`, builds, and publishes a GitHub release with
the module zip. Versions with `-alpha` or `-beta` are marked as pre-releases.

## Support

If HyperGestures is useful to you, you can support it at [ko-fi.com/mexus](https://ko-fi.com/mexus).

## License

HyperGestures is Copyright (c) 2026 [mexus](https://github.com/mexusbg) and licensed under the
[PolyForm Noncommercial License 1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0)
(full text in [LICENSE](LICENSE)). You may use, share and change it for any noncommercial
purpose; no commercial use. Whoever passes it on must pass on the license and its
`Required Notice:` line. The vendored AOSP sources under `aosp/`, the libxposed API stubs and a few
other files stay under the Apache License 2.0 ([LICENSE-APACHE-2.0](LICENSE-APACHE-2.0)).
[NOTICE](NOTICE) lists which parts are under which license, and the credits.

Xiaomi, MIUI, HyperOS and MiuiHome are trademarks of Xiaomi. HyperGestures is not affiliated
with or endorsed by Xiaomi.
