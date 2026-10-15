// frameworks/base SystemUISharedLib: packages/SystemUI/shared/{src,res}, including its 6 AIDLs
// (ILauncherProxy, ISystemUiProxy, …) (build map §1b). Of its static_libs these are dropped as
// unreferenced (build map §1b): BiometricsSharedLib, SystemUISharedLib-Keyguard, SystemUIFlagsLib,
// SystemUIPluginLib (except PluginProtector.kt, in :sysui-libs) and am_flags_lib. SysUiStatsLog
// (SystemUI-statsd) is the stub in :flags. The manifest holds only `package=`: the empty one is used.
plugins {
    id("com.android.library")
    id("com.google.devtools.ksp") // Dagger
}

val shared = "aosp/frameworks-base/packages/SystemUI/shared"

aospLibrary("com.android.systemui.shared") {
    java = listOf("$shared/src")
    res = listOf("$shared/res")
    aidl = listOf("$shared/src")
}

dependencies {
    implementation(project(":flags")) // com.android.systemui.Flags, …; SysUiStatsLog
    implementation(project(":fls-core")) // tracinglib, view_capture, msdl
    implementation(project(":animationlib"))
    implementation(project(":sysui-animation"))
    implementation(project(":sysui-libs")) // plugins, log, unfold, utils, Dumpable
    implementation(project(":wmshell-shared"))
    compileOnly(project(":fw-aidl-stubs"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.dagger)
    implementation(libs.javax.inject)
    ksp(libs.dagger.compiler)
    testImplementation(libs.junit)
}

// Data-driven AIDL code tests against the device's ILauncherProxy/ISystemUiProxy codes
// (SysuiAidlTablesTest).
aidlTableTests("hg.sysuiAidlTables", "prebuilts/device/hyperos4-sysui-aidl-tables.txt")
