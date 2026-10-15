package hg

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the SystemUI <-> launcher AIDL transaction codes to HyperOS 4's
 * (docs/research/hyperos4-systemui-quickstep.md §2–3). A failure means the vendored AOSP 17 AIDL
 * differs from the device: never edit an expected value here; the AIDL needs a ruling (an
 * aosp-patch to match).
 */
class SysuiAidlCodesTest {
    private fun code(stub: String, method: String): Int =
        Class.forName("$stub\$Stub").getDeclaredField("TRANSACTION_$method")
            .apply { isAccessible = true }.getInt(null)

    @Test fun launcherProxy() {
        val s = "com.android.systemui.shared.recents.ILauncherProxy"
        assertEquals(7, code(s, "onOverviewToggle"))
        assertEquals(8, code(s, "onOverviewShown"))
        assertEquals(9, code(s, "onOverviewHidden"))
        assertEquals(12, code(s, "onActiveNavBarRegionChanges"))
        assertEquals(13, code(s, "onInitialize"))
        assertEquals(14, code(s, "onAssistantAvailable"))
        assertEquals(17, code(s, "onSystemUiStateChanged"))
        assertEquals(36, code(s, "onUnbind"))
    }

    @Test fun systemUiProxy() {
        val s = "com.android.systemui.shared.recents.ISystemUiProxy"
        assertEquals(2, code(s, "startScreenPinning"))
        assertEquals(13, code(s, "onAssistantProgress"))
        assertEquals(14, code(s, "startAssistant"))
        assertEquals(19, code(s, "onAssistantGestureCompletion"))
        assertEquals(45, code(s, "onBackEvent"))
        assertEquals(61, code(s, "onKeyEvent"))
        assertEquals(62, code(s, "onOverviewShown"))
        assertEquals(63, code(s, "onOverviewHidden"))
    }
}
