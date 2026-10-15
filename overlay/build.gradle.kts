plugins { id("com.android.application") }

android {
    namespace = "com.hypergestures.overlay"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.hypergestures.overlay"
        minSdk = 37
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }
    signingConfigs {
        create("dev") { hgSigning(project) }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("dev")
        }
    }
}

// A resource-only RRO (hasCode=false). AGP 9's built-in Kotlin adds kotlin-stdlib to every
// application module, which would ship a ~2 MB classes.dex in the overlay; keep it out of what
// gets packaged (only the *RuntimeClasspath configurations — lint's own classpath needs it).
configurations.matching { it.name.endsWith("RuntimeClasspath") }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}
// What is left to dex is only the generated R class, which a hasCode=false overlay never loads:
// skip dexing so the APK carries no classes.dex at all.
tasks.matching { t -> listOf("dexBuilder", "mergeExtDex", "mergeLibDex", "mergeDex").any { t.name.startsWith(it) } }
    .configureEach { enabled = false }
