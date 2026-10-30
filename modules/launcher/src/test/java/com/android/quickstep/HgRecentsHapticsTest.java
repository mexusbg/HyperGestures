package com.android.quickstep;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HgRecentsHapticsTest {
    /** MiuiHome's HapticFeedbackCompat: 213 on haptic v2 (our phone), 90 on linear v1. */
    @Test public void clearAllEffect() {
        assertEquals(213, HgRecentsHaptics.clearAllEffect("2.0"));
        assertEquals(90, HgRecentsHaptics.clearAllEffect("1.0"));
        assertEquals(90, HgRecentsHaptics.clearAllEffect(""));
    }

    /** Lock 206, unlock 207 on v2; nothing of MiuiHome's elsewhere (the CONFIRM tick plays). */
    @Test public void lockEffect() {
        assertEquals(206, HgRecentsHaptics.lockEffect("2.0", true));
        assertEquals(207, HgRecentsHaptics.lockEffect("2.0", false));
        assertEquals(-1, HgRecentsHaptics.lockEffect("1.0", true));
        assertEquals(-1, HgRecentsHaptics.lockEffect("", false));
    }
}
