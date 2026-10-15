// frameworks/libs/systemui iconloader_base (build map §1c). `src_full_lib` (the separate
// "iconloader" Soong module) is not in the Launcher3QuickStep closure.
plugins { id("com.android.library") }

aospLibrary("com.android.launcher3.icons") {
    java = listOf("aosp/libs-systemui/iconloaderlib/src")
    res = listOf("aosp/libs-systemui/iconloaderlib/res")
}

dependencies {
    implementation(project(":flags"))
    implementation(project(":fls-core")) // usertypelib
    implementation(libs.androidx.core)
}
