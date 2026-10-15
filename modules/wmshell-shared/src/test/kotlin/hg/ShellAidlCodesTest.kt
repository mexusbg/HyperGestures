package hg

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the WM Shell AIDL transaction codes to HyperOS 4's (docs/research/hyperos4-systemui-quickstep.md
 * §4, hyperos4-shell-aidl-tables.txt). A failure means the vendored AOSP 17 AIDL differs from the
 * device: never edit an expected value here; the AIDL needs a ruling (an aosp-patch to match).
 */
class ShellAidlCodesTest {
    private fun code(stub: String, method: String): Int =
        Class.forName("$stub\$Stub").getDeclaredField("TRANSACTION_$method")
            .apply { isAccessible = true }.getInt(null)

    @Test fun recentTasks() {
        val s = "com.android.wm.shell.recents.IRecentTasks"
        assertEquals(2, code(s, "registerRecentTasksListener"))
        assertEquals(3, code(s, "unregisterRecentTasksListener"))
        assertEquals(4, code(s, "getRecentTasks"))
        assertEquals(5, code(s, "getRunningTasks"))
        assertEquals(6, code(s, "startRecentsTransition"))
    }

    @Test fun animationRunner() {
        val s = "com.android.wm.shell.recents.IRecentsAnimationRunner"
        assertEquals(2, code(s, "onAnimationCanceled"))
        assertEquals(3, code(s, "onAnimationStart"))
        assertEquals(4, code(s, "onTasksAppeared"))
    }

    @Test fun animationControllerImplicitOrder() {
        val s = "com.android.wm.shell.recents.IRecentsAnimationController"
        assertEquals(1, code(s, "setFinishTaskTransaction"))
        assertEquals(2, code(s, "finish"))
        assertEquals(3, code(s, "setInputConsumerEnabled"))
        assertEquals(4, code(s, "setWillFinishToHome"))
        assertEquals(5, code(s, "detachNavigationBarFromApp"))
        assertEquals(6, code(s, "handOffAnimation"))
    }

    @Test fun recentTasksListenerImplicitOrder() {
        val s = "com.android.wm.shell.recents.IRecentTasksListener"
        assertEquals(1, code(s, "onRecentTasksChanged"))
        assertEquals(2, code(s, "onRunningTaskAppeared"))
        assertEquals(3, code(s, "onRunningTaskVanished"))
        assertEquals(4, code(s, "onRunningTaskChanged"))
        assertEquals(5, code(s, "onTaskMovedToFront"))
        assertEquals(6, code(s, "onTaskInfoChanged"))
        assertEquals(7, code(s, "onVisibleTasksChanged"))
    }

    @Test fun backAnimation() {
        val s = "com.android.wm.shell.back.IBackAnimation"
        assertEquals(1, code(s, "setBackToLauncherCallback"))
        assertEquals(2, code(s, "clearBackToLauncherCallback"))
        assertEquals(3, code(s, "customizeStatusBarAppearance"))
    }
}
