plugins { `kotlin-dsl` }

// Every Gradle plugin the modules apply lives HERE, on buildSrc's classpath, not in the root
// build script's `plugins {}` block. AospModule.kt configures the Android extension through
// AGP's API, so buildSrc must see AGP's classes at runtime; and AGP, the Kotlin Gradle plugin
// (which AGP 9's built-in Kotlin drives) and the Kotlin compiler-plugin / KSP / protobuf plugins
// must all share one classloader, or they cannot see each other's types. (Phase 0 put only KGP
// here, which split KGP from AGP across classloaders: "ClassNotFoundException:
// com/android/build/gradle/api/BaseVariant".) Consequence: module scripts request these plugins
// by bare id — `plugins { id("com.android.library") }` — because Gradle refuses a version on a
// plugin that is already on the classpath.
dependencies {
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.compose.gradle.plugin)
    implementation(libs.ksp.gradle.plugin)
    implementation(libs.protobuf.gradle.plugin)
}
