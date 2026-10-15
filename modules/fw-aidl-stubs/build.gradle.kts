// Import-only AIDL declarations for the framework types that the vendored Shell/SystemUI AIDLs use
// (build map §3.4). The classes themselves are in the device framework.jar, so this module must
// never contribute code:
// - consumers depend on it with compileOnly(project(":fw-aidl-stubs")); AGP still hands the
//   packaged .aidl files to their AIDL compiler as imports;
// - javac is given no sources, so the empty interfaces the AIDL tool generates for the
//   `interface I… {}` declarations never become classes that could shadow the device's.
plugins { id("com.android.library") }

val aidlRoot = "modules/fw-aidl-stubs/src/main/aidl"

aospLibrary("aosp.fwaidl") {
    aidl = listOf(aidlRoot)
}

android {
    // Every declaration is exported to consumers.
    aidlPackagedList += fileTree(rootProject.file(aidlRoot)) { include("**/*.aidl") }
        .map { it.relativeTo(rootProject.file(aidlRoot)).invariantSeparatorsPath }
        .sorted()
}

tasks.withType<JavaCompile>().configureEach { exclude("**") }
