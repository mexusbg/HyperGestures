// Launcher3 modules/widgetpicker: all 11 widget_picker_* Soong modules merged (build map §1a, §6).
// Their srcs lists together cover exactly the 65 files under src/; only widget_picker_ui_components
// owns resources (res/, R package com.android.launcher3.widgetpicker). The manifest holds only
// `package=`, so the empty manifest is used. Launcher's LauncherWidgetPickerModule puts
// WidgetPickerComponent in its Dagger graph, so this is vendored rather than dropped (§7 item 18).
//
// Soong also lists androidx.compose.ui_ui-tooling as a static lib; nothing here imports it (it
// backs Studio previews/inspection only), so it is left to :launcher.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp") // Dagger (widget_picker_ui, widget_picker_ui_components)
}

val widgetPicker = "aosp/launcher3/modules/widgetpicker"

aospLibrary("com.android.launcher3.widgetpicker") {
    java = listOf("$widgetPicker/src")
    res = listOf("$widgetPicker/res")
}

android {
    buildFeatures.compose = true
}

dependencies {
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.foundation.layout)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.dagger)
    implementation(libs.javax.inject)
    ksp(libs.dagger.compiler)
}
