package com.android.launcher3.taskbar;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PhoneTaskbarPolicyTest {
    @Test public void phone_noTaskbarWindowUnlessFlagOn() {
        assertTrue(PhoneTaskbarPolicy.skipTaskbarWindow(false, false));
        assertFalse(PhoneTaskbarPolicy.skipTaskbarWindow(false, true));
    }

    @Test public void largeScreen_taskbarAsUpstream() {
        assertFalse(PhoneTaskbarPolicy.skipTaskbarWindow(true, false));
        assertFalse(PhoneTaskbarPolicy.skipTaskbarWindow(true, true));
    }

    /** flags/values/project-overrides.txt pins enable_taskbar_on_phones=false for HyperOS. */
    @Test public void thisBuild_phoneGetsNoTaskbarWindow() {
        assertFalse(com.android.wm.shell.Flags.enableTaskbarOnPhones());
        assertTrue(PhoneTaskbarPolicy.skipTaskbarWindow(
                false, com.android.wm.shell.Flags.enableTaskbarOnPhones()));
    }
}
