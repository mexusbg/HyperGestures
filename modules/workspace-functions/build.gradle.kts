// Launcher3 modules/appfunctions: Soong `workspace-functions` (build map §1a, §6), its 10 Kotlin
// files, with the androidx.appfunctions KSP compiler (Soong
// androidx.appfunctions_appfunctions-compiler-plugin, processor AppFunctionCompiler, no options).
// The manifest holds only `package=`, so the empty manifest is used. 18 Launcher files reference
// it (LauncherApplication, LauncherBaseAppComponent, …), so it is vendored rather than dropped
// (build map §7 item 18).
//
// Soong's `libs: com.android.extensions.appfunctions.stubs` is :appfunctions-stubs, compile-only.
// These sources compile without it (they touch no androidx member that exposes extension types),
// and a throwaway minified app over this module passed R8 without it too; it is kept for parity
// with Soong and for any consumer that touches the androidx members exposing extension types.
// Soong's `optional_uses_libs: com.android.extensions.appfunctions` needs nothing here: the
// androidx.appfunctions AAR manifest already declares that <uses-library required="false">.
plugins {
    id("com.android.library")
    id("com.google.devtools.ksp") // androidx.appfunctions compiler
}

aospLibrary("com.android.launcher3.appfunctions") {
    java = listOf("aosp/launcher3/modules/appfunctions/src")
}

dependencies {
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appfunctions)
    implementation(libs.androidx.appfunctions.service)
    implementation(libs.androidx.appcompat)
    compileOnly(project(":appfunctions-stubs"))
    ksp(libs.androidx.appfunctions.compiler)
}
