# Building HyperGestures

This page is for building the module yourself. To install and use HyperGestures, see the
[README](README.md).

## How it works

- **KernelSU module** (`module/template/`): mounts the app as a privileged system app, points
  SystemUI's recents component at it through a resource overlay, switches the navigation mode,
  and checks health at boot.
- **App** `com.hypergestures` (`hg/`, `modules/launcher/`): an AOSP Launcher3 QuickStep fork
  (`android-17.0.0_r1`) running without a home screen of its own. SystemUI binds its
  `TouchInteractionService`, as it would MiuiHome's.
- **Notification hooks** `com.hypergestures.notif` (`hg/notif/`, `modules/notif/`): a small,
  separate LSPosed module APK.
- **Overlay** (`overlay/`): a static resource overlay for SystemUI's recents component.

The module and the app exchange state only through `Settings.Global`. Root never reads or
writes the app's data folders.

Every change to the vendored AOSP sources is marked with a `// HyperGestures:` comment that
says why.

## Requirements

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

## Build

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
   git tag v0.4.0-beta
   git push origin master v0.4.0-beta
   ```

CI checks that the tag matches `version.properties`, builds, and publishes a GitHub release with
the module zip. Versions with `-alpha` or `-beta` are marked as pre-releases.
