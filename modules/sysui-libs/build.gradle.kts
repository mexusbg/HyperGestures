// Code-only frameworks/base SystemUI libraries merged into one module (build map §1b, §6):
// PluginCoreLib, PluginAnnotationLib, SystemUILogLib, SystemUILogCoreLib, SystemUICommon,
// SystemUI-shared-utils, SystemUIUnfoldLib, plus SystemUIPluginLib's checked-in
// PluginProtector.kt stub (plugin/src holds only that file), which replaces the dropped
// PluginAnnotationProcessor (build map §3.3). None of them owns resources or a manifest entry.
plugins {
    id("com.android.library")
    id("com.google.devtools.ksp") // unfold (Dagger)
}

val sysui = "aosp/frameworks-base/packages/SystemUI"

aospLibrary("com.android.systemui.libs") {
    java = listOf(
        "$sysui/plugin_core/src",
        "$sysui/plugin_core/annotations/src",
        "$sysui/log/src",
        "$sysui/log/core/src",
        "$sysui/common/src",
        "$sysui/utils/src",
        "$sysui/unfold/src",
        "$sysui/plugin/src",
    )
    aidl = listOf("$sysui/unfold/src") // IUnfoldAnimation, IUnfoldTransitionListener
}

android {
    // PluginCoreLib/PluginAnnotationLib export their R8 rules to every client (Soong
    // `export_proguard_flags_files: true`): plugin annotations must survive at runtime.
    defaultConfig.consumerProguardFiles(rootProject.file("$sysui/plugin_core/proguard.flags"))
}

// Soong's SystemUI-shared-utils `-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi` (build map
// §3.3); applying it to the merged module only silences opt-in requirements.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
}

dependencies {
    implementation(project(":flags")) // utils: com.android.systemui.Flags
    implementation(project(":fls-core")) // utils: view_capture; common: tracinglib
    implementation(libs.androidx.core)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.dynamicanimation)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.errorprone.annotations)
    implementation(libs.dagger)
    implementation(libs.javax.inject)
    ksp(libs.dagger.compiler)
}
