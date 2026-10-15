// Compile-only stubs of the libxposed API (https://github.com/libxposed/api, Apache-2.0), API 102:
// the surface com.hypergestures.notif (the notification icon fix ported from HyperNotificationFix)
// calls. Hand-written in HyperNotificationFix's stubs/ and copied unchanged to hg/xposed-stubs.
//
// Consume ONLY with compileOnly(project(":xposed-stubs")). The classes must never be packaged:
// LSPosed provides the real ones in the hooked process, and refuses a module that bundles its own
// copy ("The Xposed API classes are compiled into <module>"). These are not framework.jar classes,
// so the boot-classpath shadowing check would not catch them.
plugins { id("com.android.library") }

aospLibrary("io.github.libxposed.api.stubs") {
    java = listOf("hg/xposed-stubs/src/main/java")
}
