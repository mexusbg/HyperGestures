// frameworks/base SettingsLibSettingsTheme: packages/SettingsLib/SettingsTheme/{src,res} (build map
// §1b). Launcher uses its R (com.android.settingslib.widget.theme.R) and `@style/Switch.SettingsLib`.
// Its aconfig lib (aconfig_settingstheme_exported_flags_java_lib) is generated into :flags. The
// manifest holds only `package=` and a `uses-sdk minSdkVersion=21` (AGP rejects minSdk in a
// manifest; ours comes from the DSL), so the empty manifest is used.
plugins { id("com.android.library") }

val theme = "aosp/frameworks-base/packages/SettingsLib/SettingsTheme"

aospLibrary("com.android.settingslib.widget.theme") {
    java = listOf("$theme/src")
    res = listOf("$theme/res")
}

dependencies {
    implementation(project(":flags")) // com.android.settingslib.widget.theme.flags.Flags
    implementation(libs.androidx.preference)
    implementation(libs.material)
    // Imported directly by the sources; Soong gets them transitively through material/preference.
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)
}
