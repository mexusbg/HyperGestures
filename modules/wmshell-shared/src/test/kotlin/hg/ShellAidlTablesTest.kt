package hg

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Data-driven: every WM Shell interface in prebuilts/device/hyperos4-shell-aidl-tables.txt (the
 * device's own Stub tables) that this module compiles must agree with it code for code. A failure means the
 * vendored AOSP 17 AIDL differs from the device: never edit the table to match; the AIDL needs a
 * ruling (an aosp-patch). Interfaces only HyperOS has (multitasking, sosc, miuidesktopmode) are not
 * compiled here, and methods only HyperOS has are skipped by name.
 */
class ShellAidlTablesTest {
    private val table = File(requireNotNull(System.getProperty("hg.shellAidlTables")) { "hg.shellAidlTables not set" })
    private val tables = AidlTables.parse(table)

    @Test fun everyCompiledInterfaceMatchesTheDevice() {
        val checked = AidlTables.check(tables)
        assertEquals(
            "compiled interfaces checked against the device table",
            setOf(
                "com.android.wm.shell.back.IBackAnimation",
                "com.android.wm.shell.bubbles.IBubbles",
                "com.android.wm.shell.bubbles.IBubblesListener",
                "com.android.wm.shell.common.pip.IPip",
                "com.android.wm.shell.common.pip.IPipAnimationListener",
                "com.android.wm.shell.desktopmode.api.IDesktopMode",
                "com.android.wm.shell.desktopmode.IDesktopTaskListener",
                "com.android.wm.shell.desktopmode.IMoveToDesktopCallback",
                "com.android.wm.shell.draganddrop.IDragAndDrop",
                "com.android.wm.shell.onehanded.IOneHanded",
                "com.android.wm.shell.recents.IRecentsAnimationController",
                "com.android.wm.shell.recents.IRecentsAnimationRunner",
                "com.android.wm.shell.recents.IRecentTasks",
                "com.android.wm.shell.recents.IRecentTasksListener",
                "com.android.wm.shell.shared.IFocusTransitionListener",
                "com.android.wm.shell.shared.IHomeTransitionListener",
                "com.android.wm.shell.shared.IOverviewOverlayLeashInvalidationCallback",
                "com.android.wm.shell.shared.IShellTransitions",
                "com.android.wm.shell.splitscreen.ISplitScreen",
                "com.android.wm.shell.splitscreen.ISplitScreenListener",
                "com.android.wm.shell.splitscreen.ISplitSelectListener",
                "com.android.wm.shell.startingsurface.IStartingWindow",
                "com.android.wm.shell.startingsurface.IStartingWindowListener",
            ),
            checked.matched.keys,
        )
        assertEquals("wire mismatches", emptyList<String>(), checked.problems)
        checked.matched.forEach { (iface, n) -> assertTrue("$iface: no method matched by name", n > 0) }
    }

    /** The implicit-order interfaces (no `= N` in the AIDL) must match every device row. */
    @Test fun implicitOrderInterfacesMatchEveryAospRow() {
        val checked = AidlTables.check(tables)
        assertEquals(6, checked.matched["com.android.wm.shell.recents.IRecentsAnimationController"])
        assertEquals(7, checked.matched["com.android.wm.shell.recents.IRecentTasksListener"])
    }
}
