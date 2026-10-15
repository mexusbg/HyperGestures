package com.android.quickstep;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Which quick switches end the gesture's recents animation before they launch (HgQuickSwitch). */
public class HgQuickSwitchTest {
    private static final int FULLSCREEN = HgSystemSplitLaunch.WINDOWING_MODE_FULLSCREEN;
    private static final int FREEFORM = HgSystemSplitLaunch.WINDOWING_MODE_FREEFORM;
    private static final int MULTI_WINDOW = HgSystemSplitLaunch.WINDOWING_MODE_MULTI_WINDOW;

    @Test public void fullscreenCard_keepsAospLaunch() {
        assertEquals(HgQuickSwitch.ROUTE_AOSP,
                HgQuickSwitch.route(false, new int[] {FULLSCREEN}, true));
        assertEquals(HgQuickSwitch.ROUTE_AOSP,
                HgQuickSwitch.route(false, new int[] {FULLSCREEN}, false));
    }

    /** A split pair's card ends the animation first only when pairs start through SoSc. */
    @Test public void pairCard_onHyperOsSplit_endsRecentsFirst() {
        assertEquals(HgQuickSwitch.ROUTE_SPLIT,
                HgQuickSwitch.route(true, new int[] {MULTI_WINDOW, MULTI_WINDOW}, true));
        assertEquals(HgQuickSwitch.ROUTE_AOSP,
                HgQuickSwitch.route(true, new int[] {MULTI_WINDOW, MULTI_WINDOW}, false));
    }

    @Test public void floatingCard_endsRecentsFirst() {
        assertEquals(HgQuickSwitch.ROUTE_FLOATING,
                HgQuickSwitch.route(false, new int[] {FREEFORM}, false));
        assertEquals(HgQuickSwitch.ROUTE_FLOATING,
                HgQuickSwitch.route(false, new int[] {FREEFORM}, true));
    }

    /** A single card of a split stage (finding 5, deferred) keeps AOSP's launch here. */
    @Test public void splitStageSingleCard_keepsAospLaunch() {
        assertEquals(HgQuickSwitch.ROUTE_AOSP,
                HgQuickSwitch.route(false, new int[] {MULTI_WINDOW}, true));
    }
}
