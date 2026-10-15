// frameworks/base PlatformComposeCore (packages/SystemUI/compose/core/src) and
// frameworks/libs/systemui mechanics (mechanics/src), both Compose, merged (build map §1b, §1c, §6).
// PlatformMotionTestingComposeValues is dropped (only compose tests import it). Neither library
// owns resources; both manifests hold only `package=`, so the empty manifest is used.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

aospLibrary("com.android.compose.core") {
    java = listOf(
        "aosp/frameworks-base/packages/SystemUI/compose/core/src",
        "aosp/libs-systemui/mechanics/src",
    )
}

android {
    buildFeatures.compose = true
}

dependencies {
    implementation(project(":flags")) // com.android.systemui.Flags
    implementation(project(":fls-core")) // tracinglib
    implementation(project(":animationlib")) // InterpolatorsAndroidX
    implementation(project(":sysui-animation"))
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.util)
    implementation(libs.compose.foundation)
    implementation(libs.compose.foundation.layout)
    implementation(libs.compose.animation.core)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.windowsizeclass)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.savedstate)
    implementation(libs.androidx.window)
    implementation(libs.androidx.window.core)
    implementation(libs.androidx.core.animation)
    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines.android)
}
