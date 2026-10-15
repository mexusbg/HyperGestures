// The 15 generated aconfig packages (read-only constants; build map §2) and the hand-written
// replacements for other Soong-generated classes that sit at the bottom of the graph.
plugins { id("com.android.library") }

aospLibrary("aosp.flags") {
    java = listOf(
        "flags/src/main/java", // tools/gen-flags.sh output
        "hg/stubs/src/main/java", // SysUiStatsLog (build map §3.6)
        "hg/common/src/main/java", // HgSysRes (docs/research/private-resource-ids.md)
    )
}
