// The notification icon fix (com.hypergestures.notif, ported from HyperNotificationFix) as its
// own small APK: the LSPosed module. LSPosed's daemon copies EVERY classes*.dex of a module's APK
// into shared memory and loads it into each process it hooks (FileSystem.loadModule in
// JingMatrix/Vector reads classes.dex, classes2.dex, ... until one is missing; there is no way to
// name a dex or a part of one). As part of the main app that was its whole ~7.8 MB dex, in
// SystemUI and the AOD. Here it is only the hooks (hg/notif) and what they share with the main
// app's settings page (hg/notif-shared: NotifKeys, the two Settings.Global keys).
//
// The module installs it next to the main app, as /system/priv-app/HyperGesturesNotif (the same
// system/priv-app tree the main app is mounted with: tools/hgtools/package_module.py), with an
// allowlist generated like the main app's (it requests no permissions). No components: it never
// runs a process of its own. R8 keeps the hooks whole (hg/notif/proguard.flags).
plugins { id("com.android.application") }

aospApp("com.hypergestures.notif", "com.hypergestures.notif") {
    java = listOf("hg/notif/src/main/java", "hg/notif-shared/src/main/java")
    res = listOf("hg/notif/src/main/res")
    manifest = "hg/notif/src/main/AndroidManifest.xml"
}

android {
    defaultConfig {
        // tools/build-module.sh passes the module's version, as for :launcher.
        versionCode = hgVersion("versionCode").toInt()
        versionName = hgVersion("version")
    }
    signingConfigs {
        create("dev") { hgSigning(project) }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                rootProject.file("hg/notif/proguard.flags"),
            )
            signingConfig = signingConfigs.getByName("dev")
        }
        debug {
            signingConfig = signingConfigs.getByName("dev")
        }
    }
    sourceSets {
        // Java resources: META-INF/xposed/ (java_init.list, module.prop, scope.list), which makes
        // the APK a modern (libxposed API 102) LSPosed module. LSPosed reads them from the APK
        // root.
        getByName("main").resources.directories.add(
            rootProject.file("hg/notif/src/main/resources").absolutePath)
    }
    lint.checkReleaseBuilds = false
}

// Nothing here is Kotlin: keep AGP 9's built-in Kotlin from adding kotlin-stdlib to what is
// dexed (only the *RuntimeClasspath configurations; as overlay/build.gradle.kts does).
configurations.matching { it.name.endsWith("RuntimeClasspath") }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

dependencies {
    // libxposed API stubs; LSPosed supplies the real classes. Never packaged.
    compileOnly(project(":xposed-stubs"))
    testImplementation(libs.junit)
}
