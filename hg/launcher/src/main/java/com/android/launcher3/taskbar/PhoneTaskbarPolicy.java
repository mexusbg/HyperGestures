// HyperGestures: whether TaskbarManagerImpl may create a taskbar window on a display, extracted
// so it is testable.
//
// AOSP 17 Launcher builds a taskbar on phones too: TaskbarManagerImpl.recreateTaskbarForDisplay
// only skips it when SystemUI sets SYSUI_STATE_NAVIGATION_BAR_DISABLED, and otherwise adds a
// TYPE_NAVIGATION_BAR window. HyperOS SystemUI draws NavigationBar0 itself and WindowManager allows
// one navigation-bar window per display, so our add fails and SafeViewManager retries forever (and
// could take the slot if SystemUI ever re-adds its bar). The phone taskbar is therefore gated on
// com.android.wm.shell.enable_taskbar_on_phones, which flags/values/project-overrides.txt pins to
// false (spec §6.1: TaskbarManager stays constructed, the taskbar inactive).
package com.android.launcher3.taskbar;

public final class PhoneTaskbarPolicy {
    private PhoneTaskbarPolicy() {}

    /**
     * @param isLargeScreenTaskbar the display's device profile has a taskbar (large screen, or a
     *     tiny-taskbar/bubble-bar phone profile): recreateTaskbarForDisplay's own check
     * @param enableTaskbarOnPhones {@code com.android.wm.shell.Flags.enableTaskbarOnPhones()}
     * @return true if no taskbar window may be created on the display
     */
    public static boolean skipTaskbarWindow(boolean isLargeScreenTaskbar,
            boolean enableTaskbarOnPhones) {
        return !isLargeScreenTaskbar && !enableTaskbarOnPhones;
    }
}
