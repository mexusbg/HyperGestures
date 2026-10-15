import com.android.build.api.dsl.AndroidSourceDirectorySet
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File

/*
 * Conventions for the Gradle modules that compile vendored AOSP sources (build map §6).
 *
 * A module script applies its plugins by bare id (they live on buildSrc's classpath), then calls
 * aospLibrary/aospApp; plain `android { }` / `dependencies { }` blocks may follow for anything
 * module-specific:
 *
 *     plugins { id("com.android.library") }
 *     aospLibrary("com.android.app.animation") {
 *         java = listOf("aosp/libs-systemui/animationlib/src")
 *         res = listOf("aosp/libs-systemui/animationlib/res")
 *     }
 *     dependencies { implementation(libs.androidx.core.ktx) }
 *
 * Dependencies on other AOSP modules are declared per module, and `implementation` does not
 * leak: a module whose sources use a dependency's types must declare that dependency itself.
 * In particular every module that calls generated aconfig `Flags` declares `:flags` (all do
 * today; `:launcher` must too). Where a module's public API exposes another module's types,
 * it uses `api(...)` instead.
 *
 * Every module with resources (or a vendored manifest) also gets `checkPrivateRes`, run before
 * `preBuild`: it fails the build on private framework resource references (`@*android:…`,
 * `@androidprv:…`), which aapt2 would link to SDK-private IDs that are wrong on the device
 * (tools/hgtools/res_preprocess.py, allowlist tools/hgtools/private-refs-allowlist.txt). It runs
 * **Python 3.11+**: `python` on PATH, or `-Phg.python=<exe>` / the `HG_PYTHON` environment
 * variable.
 */

/** Source roots of one Gradle module, relative to the repository root (they point into aosp/). */
class AospSources {
    /** Java and Kotlin roots (both `java` and `kotlin` source dirs are set to these). */
    var java: List<String> = emptyList()
    var res: List<String> = emptyList()
    /** Non-empty turns on `buildFeatures.aidl`. */
    var aidl: List<String> = emptyList()
    var assets: List<String> = emptyList()
    /**
     * Null uses the shared empty manifest. A vendored manifest should not carry `package=`: the
     * namespace comes from the DSL, and AGP warns about the attribute (it is ignored).
     */
    var manifest: String? = null
}

/** compileSdk = minSdk = targetSdk: the build targets Android 17 devices only (build map §3.8). */
const val AOSP_SDK = 37

/**
 * Shared `<manifest/>` for modules with nothing to declare. A checked-in file rather than one
 * generated under build/: `gradlew clean assemble…` deletes build/ after configuration, which would
 * remove a manifest written at configuration time before AGP reads it.
 */
private const val EMPTY_MANIFEST = "hg/manifest/AndroidManifest.xml"

/** APEX system APIs missing from the device framework.jar (build map §1f). */
private val MODULE_LIB_STUBS = listOf(
    "prebuilts/sdk/37.0/module-lib/framework-statsd.jar",
    "prebuilts/sdk/37.0/module-lib/framework-configinfrastructure.jar",
)

private fun Project.roots(paths: List<String>): List<File> = paths.map { rootProject.file(it) }

/** Drops AGP's default `src/main/<kind>` directory and uses exactly [dirs]. */
private fun AndroidSourceDirectorySet.replaceWith(dirs: List<File>) {
    directories.clear()
    directories.addAll(dirs.map { it.absolutePath })
}

/** A `com.android.library` module over vendored sources. Apply the plugin first. */
fun Project.aospLibrary(namespace: String, block: AospSources.() -> Unit) {
    val sources = AospSources().apply(block)
    extensions.configure(LibraryExtension::class.java) {
        aospCommon(this@aospLibrary, namespace, sources)
        testOptions.targetSdk = AOSP_SDK
    }
    aospCompile()
    checkPrivateRes(sources)
}

/** A `com.android.application` module over vendored sources. Apply the plugin first. */
fun Project.aospApp(namespace: String, applicationId: String, block: AospSources.() -> Unit) {
    val sources = AospSources().apply(block)
    extensions.configure(ApplicationExtension::class.java) {
        aospCommon(this@aospApp, namespace, sources)
        defaultConfig.applicationId = applicationId
        defaultConfig.targetSdk = AOSP_SDK
    }
    aospCompile()
    checkPrivateRes(sources)
}

private fun CommonExtension.aospCommon(project: Project, namespace: String, s: AospSources) {
    this.namespace = namespace
    compileSdk = AOSP_SDK
    defaultConfig.minSdk = AOSP_SDK
    sourceSets.getByName("main").apply {
        java.replaceWith(project.roots(s.java))
        kotlin.replaceWith(project.roots(s.java))
        res.replaceWith(project.roots(s.res))
        aidl.replaceWith(project.roots(s.aidl))
        assets.replaceWith(project.roots(s.assets))
        manifest.srcFile(project.rootProject.file(s.manifest ?: EMPTY_MANIFEST))
    }
    buildFeatures.aidl = s.aidl.isNotEmpty()
    buildFeatures.buildConfig = false
    // Java 17, not 21: the two Java 21 switch sites are patched (aosp/PATCHES.md).
    compileOptions.sourceCompatibility = JavaVersion.VERSION_17
    compileOptions.targetCompatibility = JavaVersion.VERSION_17
}

private fun Project.aospCompile() {
    tasks.withType(KotlinCompile::class.java).configureEach {
        // Soong's `-Xjvm-default=all`: Java classes implement Kotlin interfaces with default
        // methods.
        compilerOptions.jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
        // Explicit, matching compileOptions (class file version 61), rather than relying on
        // AGP deriving it from targetCompatibility.
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    useDeviceFramework()
    dependencies.add("compileOnly", files(MODULE_LIB_STUBS.map { rootProject.file(it) }))
}

/**
 * Registers `checkPrivateRes` over the module's res dirs and vendored manifest, wired before
 * `preBuild` (so before resource merging and linking). Up to date while the inputs are unchanged.
 */
private fun Project.checkPrivateRes(s: AospSources) {
    val xmlRoots = (s.res + listOfNotNull(s.manifest)).map { rootProject.file(it) }
    if (xmlRoots.isEmpty()) return
    val repo = rootProject.rootDir
    val allowlist = rootProject.file("tools/hgtools/private-refs-allowlist.txt")
    val stamp = layout.buildDirectory.file("hg/checkPrivateRes.ok")
    val python = providers.gradleProperty("hg.python")
        .orElse(providers.environmentVariable("HG_PYTHON"))
        .orElse("python")
    val check = tasks.register("checkPrivateRes", Exec::class.java) {
        group = "verification"
        description = "Fails on private framework resource references (SDK-private IDs)."
        inputs.files(xmlRoots).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.file(allowlist).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.file(File(repo, "tools/hgtools/res_preprocess.py"))
            .withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.file(stamp)
        workingDir = repo
        executable = python.get()
        args(listOf("-m", "tools.hgtools.res_preprocess", "--check-private") +
            xmlRoots.map { it.absolutePath } +
            listOf("--root", repo.absolutePath, "--allowlist", allowlist.absolutePath))
        doLast { stamp.get().asFile.writeText("ok\n") }
    }
    tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(check) }
}

/**
 * Data-driven AIDL transaction-code tests for a library module that compiles AIDL: adds the shared
 * device-table parser (hg/testing/aidl, `hg.AidlTables`) to the unit-test sources and hands the
 * device table [table] (repo-relative, under the git-ignored prebuilts/device: taken from the
 * firmware) to the tests as system property [property].
 */
fun Project.aidlTableTests(property: String, table: String) {
    extensions.configure(LibraryExtension::class.java) {
        sourceSets.getByName("test").kotlin.directories.add(rootProject.file("hg/testing/aidl").absolutePath)
    }
    val file = rootProject.file(table)
    tasks.withType(Test::class.java).configureEach {
        inputs.file(file).withPathSensitivity(PathSensitivity.RELATIVE)
        systemProperty(property, file.absolutePath)
    }
}
