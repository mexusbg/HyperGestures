// Launcher3 Launcher3ResLib: res/ plus AndroidManifest-common.xml (package= stripped by an
// aosp-patch) (build map §1a, §3.7, §6). A resource-only library, so that :launcher can put
// quickstep/res (QuickstepResLib) on top of it: quickstep/res overrides 136 of these resources, and
// AGP rejects duplicate resources within one source set, but lets an app's resources override a
// library's.
//
// Dependencies: Launcher3ResLib's static_libs, minus the ones that are sources of :launcher
// (LauncherPluginLib, the launcher protos, launcher-testing-shared, launcher-dagger-qualifiers,
// launcher-executor-qualifiers, launcher-executors-module): a library cannot depend on the app.
// This module compiles no code, so what matters is that aapt2 sees the resources and attrs its
// res/ references (material, constraintlayout, recyclerview, cardview, preference, dynamiccolors in
// :wmshell-shared, iconloader, widget picker, ...); the code-only libraries are listed for parity
// with Soong and are declared by :launcher again, which compiles against them. compose ui-tooling is
// left to :launcher (debug only: its AAR manifest exports a PreviewActivity).
plugins {
    id("com.android.library")
}

aospLibrary("com.android.launcher3.res") {
    res = listOf("aosp/launcher3/res")
    manifest = "aosp/launcher3/AndroidManifest-common.xml"
}

dependencies {
    implementation(project(":wmshell-shared")) // dynamiccolors res, WindowManager-Shell-shared
    implementation(project(":iconloader")) // iconloader_base
    implementation(project(":animationlib"))
    implementation(project(":fls-core")) // view_capture, contextualeducationlib, msdl, cuebarlib
    implementation(project(":compose-libs")) // mechanics
    implementation(project(":flags")) // aconfig libs, SystemUI-statsd (SysUiStatsLog stub)
    implementation(project(":widgetpicker")) // widget_picker_component
    implementation(project(":workspace-functions"))
    implementation(project(":sysui-libs")) // PluginCoreLib (via LauncherPluginLib)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.dynamicanimation)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.slice.view)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.window)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.common.java8)
    implementation(libs.androidx.lifecycle.extensions)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.dagger)
    implementation(libs.javax.inject)
    implementation(libs.compose.runtime)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.navigation.compose)
}
