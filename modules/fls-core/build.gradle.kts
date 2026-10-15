// Code-only frameworks/libs/systemui libraries merged into one module (build map §6):
// compilelib (release variant), tracinglib-platform, usertypelib, view_capture,
// contextualeducationlib, msdl, displaylib, cuebarlib.
//
// None of them owns resources; their manifests carry only `package=` except msdl's, whose one
// entry (uses-permission VIBRATE) the app manifests already declare
// (AndroidManifest-common.xml, quickstep/AndroidManifest.xml), so the empty manifest is used.
// tracinglib's Soong `-Xexplicit-api=strict` is a source-style check only; it is not enabled here
// because it would apply to the other merged libraries too.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.parcelize") // usertypelib, cuebarlib
    id("com.google.devtools.ksp") // displaylib (Dagger)
}

aospLibrary("com.android.fls") {
    java = listOf(
        "aosp/libs-systemui/compilelib/src-release",
        "aosp/libs-systemui/tracinglib/core/src",
        "aosp/libs-systemui/usertypelib/src",
        "aosp/libs-systemui/viewcapturelib/src",
        "aosp/libs-systemui/contextualeducationlib/src",
        "aosp/libs-systemui/msdllib/src",
        "aosp/libs-systemui/displaylib/src",
        "aosp/libs-systemui/cuebarlib/src",
    )
}

dependencies {
    implementation(project(":flags")) // tracinglib: com.android.systemui.Flags
    implementation(libs.androidx.core)
    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.dagger)
    implementation(libs.javax.inject)
    ksp(libs.dagger.compiler)
}
