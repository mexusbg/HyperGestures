// frameworks/libs/systemui animationlib (build map §1c).
plugins { id("com.android.library") }

aospLibrary("com.android.app.animation") {
    java = listOf("aosp/libs-systemui/animationlib/src")
    res = listOf("aosp/libs-systemui/animationlib/res")
    // Its AndroidManifest.xml holds only package="com.android.app.animation", which the
    // namespace supersedes (AGP warns on a source package=), so the empty manifest is used.
}

dependencies {
    implementation(libs.androidx.core.animation)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)
}
