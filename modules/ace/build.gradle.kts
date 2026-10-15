// frameworks/libs/systemui personalcontext_ace_client + personalcontext_ace_common (+ the
// _embeddedscroll module, whose one file is already in common's glob) merged (build map §1c, §6).
// Two source roots, as upstream's own ace/{client,common}/build.gradle.kts use: ace/src/…/ace also
// holds visualizer/ (personalcontext_ace_visualizer, not in the closure), so it cannot be the root.
// Only the client owns resources (clientsdk/compat/res, R package com.android.personalcontext.ace.client).
// Both manifests hold only `package=`, so the empty manifest is used. A drop candidate together with
// the cuebar feature (build map §7 item 18), but vendored and compiled for now.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.parcelize")
}

val ace = "aosp/libs-systemui/ace/src/com/android/personalcontext/ace"

aospLibrary("com.android.personalcontext.ace.client") {
    java = listOf("$ace/client", "$ace/common")
    res = listOf("$ace/client/clientsdk/compat/res")
}

android {
    buildFeatures.compose = true
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appcompat)
    implementation(libs.compose.animation.core)
    implementation(libs.compose.foundation)
    implementation(libs.compose.foundation.layout)
    implementation(libs.compose.material3)
    implementation(libs.compose.runtime)
    implementation(libs.compose.runtime.saveable)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.geometry)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.text)
    implementation(libs.compose.ui.unit)
    implementation(libs.androidx.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.dynamicanimation)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.window)
    implementation(libs.kotlinx.coroutines.android)
}
