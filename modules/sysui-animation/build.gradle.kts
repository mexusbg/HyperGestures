// frameworks/base PlatformAnimationLib: packages/SystemUI/animation/{src,res} (build map §1b).
// Its SurfaceEffectsViewLib dependency is dropped: nothing imports com.android.systemui.surfaceeffects.
// animation/lib (PlatformAnimationLib-core/-server/-aidl) is outside the Launcher3QuickStep closure.
plugins { id("com.android.library") }

val animation = "aosp/frameworks-base/packages/SystemUI/animation"

aospLibrary("com.android.systemui.animation") {
    java = listOf("$animation/src")
    res = listOf("$animation/res")
    // The manifest holds only package="com.android.systemui.animation": the empty one is used.
}

dependencies {
    implementation(project(":flags")) // com.android.systemui.Flags
    implementation(project(":animationlib"))
    implementation(project(":wmshell-shared"))
    implementation(libs.androidx.core.animation)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines.android)
}
