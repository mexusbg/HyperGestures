package com.android.quickstep;

import static com.android.quickstep.HgTaskMenuPolicy.Entry.APP_INFO;
import static com.android.quickstep.HgTaskMenuPolicy.Entry.CLOSE;
import static com.android.quickstep.HgTaskMenuPolicy.Entry.FLOATING_WINDOW;
import static com.android.quickstep.HgTaskMenuPolicy.Entry.LOCK;
import static com.android.quickstep.HgTaskMenuPolicy.Entry.SPLIT_SCREEN;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class HgTaskMenuPolicyTest {
    /** HyperOS recents' menu, in its order, and nothing else. */
    @Test public void menu_isHyperOsOrder() {
        assertEquals(Arrays.asList(APP_INFO, LOCK, SPLIT_SCREEN, FLOATING_WINDOW, CLOSE),
                HgTaskMenuPolicy.MENU);
        assertEquals(HgTaskMenuPolicy.Entry.values().length, HgTaskMenuPolicy.MENU.size());
    }

    /** Close comes last, from AOSP's TaskViewShortFactory, so it is not a per-task factory. */
    @Test public void perTask_isMenuWithoutClose() {
        assertEquals(HgTaskMenuPolicy.MENU.subList(0, 4), HgTaskMenuPolicy.PER_TASK);
        assertFalse(HgTaskMenuPolicy.PER_TASK.contains(CLOSE));
    }

    @Test public void floatingWindow_needsSystemAndAppSupport_singleTaskCard() {
        assertTrue(HgTaskMenuPolicy.showFloatingWindow(true, true, false));
        assertFalse(HgTaskMenuPolicy.showFloatingWindow(false, true, false));
        assertFalse(HgTaskMenuPolicy.showFloatingWindow(true, false, false));
        assertFalse(HgTaskMenuPolicy.showFloatingWindow(true, true, true));
    }

    /** Split screen needs a second card to pick; alone in recents it is not offered. */
    @Test public void splitScreenNeedsAnotherCard() {
        org.junit.Assert.assertFalse(HgTaskMenuPolicy.showSplitScreen(0));
        org.junit.Assert.assertTrue(HgTaskMenuPolicy.showSplitScreen(1));
    }
}
