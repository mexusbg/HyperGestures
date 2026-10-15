import com.google.protobuf.gradle.proto
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

// Launcher3 Launcher3QuickStep (app) + Launcher3QuickStepLib + QuickstepResLib, recents-only
// (build map §1a, §3, §6; spec §6.1). applicationId com.hypergestures; the Java/Kotlin packages stay
// AOSP's. The recents component is com.hypergestures/com.android.quickstep.RecentsActivity.
//
// - Sources: Launcher3QuickStepLib's filegroups (src, quickstep/src, quickstep/dagger), the
//   protolog sources compiled as plain Java (build map §3.2), LauncherPluginLib (src_plugins),
//   launcher-testing-shared (shared/src), launcher-dagger-qualifiers (dagger/src), the concurrent
//   module (executor qualifiers + ExecutorsModule), com.android.extensions.computercontrol (a
//   QuickstepResLib static lib, not on the device boot classpath), and hg/launcher (BuildConfig,
//   which replaces Soong's launcher-build-config genrule, OverviewTargetPolicy and PhoneTaskbarPolicy).
// - Resources: quickstep/res here, over Launcher3's res/ in :launcher-res, so quickstep's 136
//   overrides win (build map §3.7); plus hg/launcher/src/main/res (our own, hg_-prefixed names).
// - Manifest: quickstep/AndroidManifest.xml (package= stripped by an aosp-patch), merged with
//   :launcher-res's AndroidManifest-common.xml. quickstep/AndroidManifest-launcher.xml (the
//   QuickstepLauncher HOME activity) is deliberately NOT used: HyperGestures ships no HOME activity
//   (OverviewComponentObserver is patched to cope). hg/launcher/src/main/AndroidManifest.xml adds
//   the recents-only extras; AGP takes one manifest per source set, so it is the manifest of both
//   build-type source sets, which merge over main.
// - Protos: protos/ and quickstep/protos_overrides/ (lite). protos_overrides/ is left out: its
//   launcher_atom_extension.proto duplicates the quickstep override's class (build map §3.1).
// - R8: AGP's proguard-android-optimize.txt (standing in for Soong's own base flags), AOSP's
//   proguard.flags, PluginCoreLib's (also exported by :sysui-libs), and hg/launcher/proguard.flags
//   (-dontobfuscate; -dontwarn for the libcore classes the device stub refers to); resources are
//   shrunk as Soong does (shrink_resources: true).
// - Not an LSPosed module: the notification icon fix's hooks are their own small APK (:notif,
//   com.hypergestures.notif), since LSPosed copies every classes*.dex of a module's APK into each
//   process it hooks. This APK compiles only what the two share (hg/notif-shared: NotifKeys, the
//   Settings.Global keys the settings page and the hooks talk through).
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp") // Dagger
    id("com.google.protobuf")
}

val l3 = "aosp/launcher3"
val hgManifest = rootProject.file("hg/launcher/src/main/AndroidManifest.xml")

aospApp("com.android.launcher3", "com.hypergestures") {
    java = listOf(
        "$l3/src",
        "$l3/quickstep/src",
        "$l3/quickstep/dagger",
        "$l3/quickstep/src_protolog",
        "$l3/src_plugins",
        "$l3/shared/src",
        "$l3/dagger/src",
        "$l3/modules/concurrent/src",
        "aosp/frameworks-base/libs/computercontrol/src",
        "hg/launcher/src/main/java",
        "hg/notif-shared/src/main/java",
    )
    res = listOf("$l3/quickstep/res", "hg/launcher/src/main/res")
    manifest = "$l3/quickstep/AndroidManifest.xml"
}

android {
    defaultConfig {
        // tools/build-module.sh may pass these, so the APK and module.prop carry the same version.
        versionCode = hgVersion("versionCode").toInt()
        versionName = hgVersion("version")
    }
    buildFeatures.compose = true
    signingConfigs {
        create("dev") { hgSigning(project) }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                rootProject.file("$l3/proguard.flags"),
                rootProject.file("aosp/frameworks-base/packages/SystemUI/plugin_core/proguard.flags"),
                rootProject.file("hg/launcher/proguard.flags"),
            )
            signingConfig = signingConfigs.getByName("dev")
        }
        debug {
            signingConfig = signingConfigs.getByName("dev")
        }
    }
    sourceSets {
        getByName("debug").manifest.srcFile(hgManifest)
        getByName("release").manifest.srcFile(hgManifest)
        getByName("main").proto {
            setSrcDirs(listOf("$l3/protos", "$l3/quickstep/protos_overrides").map(rootProject::file))
        }
    }
    // Soong does not gate the build on lint; lintVital over ~308k LOC of vendored code would only
    // re-report upstream's own baseline (aosp/launcher3/lint-baseline.xml).
    lint.checkReleaseBuilds = false
}

// The HyperOS build this APK was made for (tools/framework-stub/device-build.txt `fingerprint=`, the
// build the device stub and every pinned device value come from; DeviceFramework.kt already fails
// the build if the stub disagrees), as com.hypergestures.HgBuildConfig.EXPECTED_FINGERPRINT.
// HgSoScSplitScreen uses HyperOS's code-number-pinned ISoScSplitScreen only on that build.
// A generated source rather than buildConfigField: buildConfig is off in every module (AospModule.kt),
// and :launcher's namespace would generate com.android.launcher3.BuildConfig, which hg/launcher
// already defines by hand.
abstract class GenHgBuildConfig : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val deviceBuild: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val file = deviceBuild.get().asFile
        val fingerprint = file.readLines().map { it.trim() }
            .firstOrNull { it.startsWith("fingerprint=") }?.removePrefix("fingerprint=")
            ?.takeIf { it.isNotEmpty() }
            ?: throw GradleException("$file has no fingerprint= line")
        val literal = fingerprint.replace("\\", "\\\\").replace("\"", "\\\"")
        val out = outputDir.get().asFile.resolve("com/hypergestures/HgBuildConfig.java")
        out.parentFile.mkdirs()
        out.writeText(
            """
            |// Generated by modules/launcher/build.gradle.kts (GenHgBuildConfig) — do not edit.
            |package com.hypergestures;
            |
            |public final class HgBuildConfig {
            |    private HgBuildConfig() {}
            |
            |    /** ro.build.fingerprint of the HyperOS build this APK was built against. */
            |    public static final String EXPECTED_FINGERPRINT = "$literal";
            |}
            |""".trimMargin()
        )
    }
}

val genHgBuildConfig = tasks.register("genHgBuildConfig", GenHgBuildConfig::class.java) {
    deviceBuild.set(rootProject.file("tools/framework-stub/device-build.txt"))
    outputDir.set(layout.buildDirectory.dir("generated/source/hgBuildConfig"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(genHgBuildConfig, GenHgBuildConfig::outputDir)
    }
}

protobuf {
    protoc { artifact = libs.protoc.get().toString() }
    generateProtoTasks {
        all().configureEach {
            builtins { create("java") { option("lite") } }
        }
    }
}

dependencies {
    // AOSP-source modules (build map §6): every one of them.
    implementation(project(":launcher-res")) // Launcher3ResLib
    implementation(project(":flags")) // aconfig libs, SystemUI-statsd (SysUiStatsLog stub)
    implementation(project(":fls-core")) // displaylib, view_capture, msdl, tracinglib, cuebar, ...
    implementation(project(":animationlib"))
    implementation(project(":iconloader")) // iconloader_base
    implementation(project(":wmshell-shared")) // WindowManager-Shell-shared, dynamiccolors
    implementation(project(":sysui-libs")) // PluginCoreLib (LauncherPluginLib), unfold, log, ...
    implementation(project(":sysui-animation")) // PlatformAnimationLib
    implementation(project(":compose-libs")) // PlatformComposeCore, mechanics
    implementation(project(":sysui-shared")) // SystemUISharedLib
    implementation(project(":settingstheme")) // SettingsLibSettingsTheme
    implementation(project(":widgetpicker")) // widget_picker_component
    implementation(project(":ace")) // personalcontext_ace_client, personalcontext_ace_common
    implementation(project(":workspace-functions"))

    // Maven (build map §1e): Launcher3ResLib + QuickstepResLib + Launcher3QuickStepLib static
    // libs, plus what the sources import directly that Soong gets transitively.
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.animation)
    implementation(libs.androidx.dynamicanimation)
    implementation(libs.androidx.concurrent.futures)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.slice.view)
    implementation(libs.androidx.slice.core) // SliceItem: slice-view only has it at runtime
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.window)
    implementation(libs.androidx.window.core) // WindowSizeClass: window only has it at runtime
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.savedstate)
    implementation(libs.androidx.lifecycle.common.java8)
    implementation(libs.androidx.lifecycle.extensions)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.graphics.shapes)
    implementation(libs.androidx.appfunctions)
    implementation(libs.androidx.appfunctions.service)
    implementation(libs.material)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.util)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.foundation.layout)
    implementation(libs.compose.animation.core)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.lottie)
    implementation(libs.dagger)
    implementation(libs.javax.inject)
    implementation(libs.guava) // launcher-executors-module
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.protobuf.javalite)
    implementation(libs.errorprone.annotations)
    compileOnly(libs.jsr305) // javax.annotation.concurrent.ThreadSafe (no Soong module)
    ksp(libs.dagger.compiler)

    // Soong links compose ui-tooling into the app; nothing here uses it beyond the @Preview
    // annotations (ui-tooling-preview), and its AAR adds an exported PreviewActivity to the manifest,
    // so it is kept out of release builds.
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}

// Build checks (Task 8, spec §5.1/§6.1/§10; privateResIdCheck: private-resource-ids.md): all run
// as Exec tasks over the release outputs and finalize assembleRelease (they fail the build;
// assembleRelease itself already succeeded, so a finalizer is the natural place — the checks
// don't gate whether the APK gets built, only whether the invocation as a whole is green). Same
// `hg.python`/HG_PYTHON convention as checkPrivateRes (AospModule.kt).
val python = providers.gradleProperty("hg.python")
    .orElse(providers.environmentVariable("HG_PYTHON"))
    .orElse("python")

// The compileSdk platform's android.jar, from AGP (not a hard-coded platforms/android-NN path).
val androidJar = androidComponents.sdkComponents.bootClasspath.map { jars ->
    jars.map { it.asFile }.firstOrNull { it.name == "android.jar" }
        ?: error("no android.jar on the SDK boot classpath: $jars")
}
val mergedManifestRelease =
    layout.buildDirectory.file("intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml")
val releaseApk = layout.buildDirectory.file("outputs/apk/release/launcher-release.apk")

val shadowCheck = tasks.register("shadowCheck", Exec::class.java) {
    group = "verification"
    description = "Fails if the release APK bundles a class shadowing the device/SDK boot classpath (spec §5.1)."
    dependsOn("assembleRelease")
    inputs.file(releaseApk).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("prebuilts/device/framework-classes.txt")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(androidJar).withPathSensitivity(PathSensitivity.RELATIVE)
    workingDir = rootProject.rootDir
    executable = python.get()
    args(
        "-m", "tools.hgtools.shadow_check",
        "--apk", releaseApk.get().asFile.absolutePath,
        "--framework-classes", rootProject.file("prebuilts/device/framework-classes.txt").absolutePath,
    )
    argumentProviders.add(CommandLineArgumentProvider { listOf("--android-jar", androidJar.get().absolutePath) })
}

// Checks both the merged manifest and the one packaged in the APK (aapt2 dump xmltree; aapt2 is
// found the way the Gradle build finds the SDK, tools/hgtools/sdk.py).
val manifestContract = tasks.register("manifestContract", Exec::class.java) {
    group = "verification"
    description = "Fails on manifest surfaces this recents-only app must not expose (spec §6.1)."
    dependsOn("assembleRelease")
    inputs.file(mergedManifestRelease).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(releaseApk).withPathSensitivity(PathSensitivity.RELATIVE)
    workingDir = rootProject.rootDir
    executable = python.get()
    args(
        "-m", "tools.hgtools.manifest_contract", mergedManifestRelease.get().asFile.absolutePath,
        "--apk", releaseApk.get().asFile.absolutePath,
    )
}

// javac/kotlinc inline com.android.internal.R constants as the device stub's numbering, which can
// move on an OTA: fails on any private framework resource ID left in the release dex that is not
// allowlisted, and on an allowlist entry whose ID no longer matches prebuilts/device (or, for the
// XML refs, the SDK android.jar). docs/research/private-resource-ids.md; aapt2 is found as
// manifestContract finds it.
val deviceFrameworkRes = rootProject.file("prebuilts/device/framework-res.apk")
val privateResIdsAllowlist = rootProject.file("tools/hgtools/private-res-ids-allowlist.txt")
val privateResIdCheck = tasks.register("privateResIdCheck", Exec::class.java) {
    group = "verification"
    description = "Fails if the release dex has firmware-specific private framework resource IDs."
    dependsOn("assembleRelease")
    inputs.file(releaseApk).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(deviceFrameworkRes).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(privateResIdsAllowlist).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(androidJar).withPathSensitivity(PathSensitivity.RELATIVE)
    workingDir = rootProject.rootDir
    executable = python.get()
    args(
        "-m", "tools.hgtools.private_res_ids", "--check", releaseApk.get().asFile.absolutePath,
        "--device-res", deviceFrameworkRes.absolutePath,
        "--allowlist", privateResIdsAllowlist.absolutePath,
    )
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("--sdk-platform", androidJar.get().parentFile.absolutePath)
    })
}

// tasks.named("assembleRelease") would throw at configuration time: AGP registers variant tasks
// lazily, sometimes after this script body runs (same reason useDeviceFramework() above hooks
// compile tasks with tasks.withType(...).configureEach{} rather than by name). tasks.matching
// reacts to tasks added later too (as AospModule.kt's checkPrivateRes wiring already relies on).
tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy(shadowCheck, manifestContract, privateResIdCheck)
}


// HgNotifyReceiverTest reads the module's functions.sh, which is outside the test's tracked
// inputs: declare it, so a change there reruns the unit tests instead of leaving them up to date.
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("module/template/common/functions.sh"))
        .withPropertyName("hgModuleFunctions")
}
