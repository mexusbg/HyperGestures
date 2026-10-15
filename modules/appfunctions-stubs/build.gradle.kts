// Compile-only stubs of the com.android.extensions.appfunctions shared library (Soong
// com.android.extensions.appfunctions.stubs, build map §1d, §1f), which Soong puts on
// workspace-functions' `libs` "to resolve optional deps from the support library": androidx.appfunctions
// references these classes (ExtensionsAppFunctionService, ExtensionAppFunctionManagerApi, …).
// Neither the Android SDK nor AOSP prebuilts/sdk @ android-17.0.0_r1 ships them, so they are
// hand-written under hg/ from frameworks/base/libs/appfunctions/api/current.txt (compile-fix
// priority 2: a replacement for a Soong-generated artifact).
//
// Consume ONLY with compileOnly(project(":appfunctions-stubs")). The classes must never be packaged:
// the device supplies the real library as an optional <uses-library> (declared by the
// androidx.appfunctions AAR manifest). These are not framework.jar classes, so the boot-classpath
// shadowing check would not catch them.
plugins { id("com.android.library") }

aospLibrary("aosp.appfunctions.stubs") {
    java = listOf("hg/appfunctions-stubs/src/main/java")
}
