// frameworks/base WindowManager-Shell-shared, WindowManager-Shell-shared-aidls and
// WindowManager-Shell-aidls (build map §1b), plus frameworks/libs/systemui dynamiccolors' res (§1c).
//
// WindowManager-Shell-shared-AOSP is not built: its two files (BubbleFlagHelper, EntryPoint) are a
// subset of Shell/shared/src and would be duplicate classes.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.parcelize")
}

val shell = "aosp/frameworks-base/libs/WindowManager/Shell"

aospLibrary("com.android.wm.shell.shared") {
    java = listOf("$shell/shared/src")
    res = listOf("$shell/shared/res", "aosp/libs-systemui/dynamiccolors/res")
    // Shell/src holds nothing but the 20 Shell AIDLs, so it is an AIDL root only (never a java
    // root). Shell/shared/src is both: Soong's `aidl.local_include_dirs: ["shared/src/"]`.
    aidl = listOf("$shell/shared/src", "$shell/src")
    // The manifest holds only package="com.android.wm.shell.shared": the empty one is used.
}

dependencies {
    implementation(project(":flags")) // com.android.wm.shell.Flags, com.android.window.flags.Flags
    implementation(project(":fls-core")) // usertypelib (com.android.users)
    compileOnly(project(":fw-aidl-stubs"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.core.animation)
    implementation(libs.androidx.dynamicanimation)
    implementation(libs.androidx.annotation)
    implementation(libs.javax.inject)
    testImplementation(libs.junit)
}

// Data-driven AIDL code tests against the device's own Shell Stub tables (ShellAidlTablesTest).
aidlTableTests("hg.shellAidlTables", "prebuilts/device/hyperos4-shell-aidl-tables.txt")
