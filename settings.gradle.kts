pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "HyperGestures"
include(":overlay")

// AOSP-source modules (build map §6). Their scripts live under modules/<name>/; the sources they
// compile stay in aosp/ (and flags/, hg/), referenced by path from buildSrc's AospModule.kt.
listOf(
    "flags", "fw-aidl-stubs", "animationlib", "fls-core", "iconloader",
    "wmshell-shared", "sysui-libs", "sysui-animation", "compose-libs", "sysui-shared",
    "settingstheme", "widgetpicker", "ace", "appfunctions-stubs",
    "workspace-functions", "xposed-stubs", "launcher-res", "launcher", "notif",
).forEach { name ->
    include(":$name")
    project(":$name").projectDir = file("modules/$name")
}
