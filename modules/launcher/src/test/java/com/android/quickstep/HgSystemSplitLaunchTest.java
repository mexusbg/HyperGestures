package com.android.quickstep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.WindowConfiguration;

import org.junit.Test;

public class HgSystemSplitLaunchTest {
    /** The constants match the framework's (the stub jar carries the hidden fields). */
    @Test public void constants_matchFramework() {
        assertEquals(WindowConfiguration.WINDOWING_MODE_UNDEFINED,
                HgSystemSplitLaunch.WINDOWING_MODE_UNDEFINED);
        assertEquals(WindowConfiguration.WINDOWING_MODE_FULLSCREEN,
                HgSystemSplitLaunch.WINDOWING_MODE_FULLSCREEN);
        assertEquals(WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW,
                HgSystemSplitLaunch.WINDOWING_MODE_MULTI_WINDOW);
        assertEquals(WindowConfiguration.WINDOWING_MODE_FREEFORM,
                HgSystemSplitLaunch.WINDOWING_MODE_FREEFORM);
    }

    @Test public void fullscreenCard_isNotInSplit() {
        assertFalse(HgSystemSplitLaunch.inSystemSplit(new int[] {1}));
        assertFalse(HgSystemSplitLaunch.inSystemSplit(new int[] {}));
        assertFalse(HgSystemSplitLaunch.inSystemSplit(new int[] {5})); // freeform
    }

    @Test public void splitStageTask_isInSplit() {
        assertTrue(HgSystemSplitLaunch.inSystemSplit(new int[] {6}));
        assertTrue(HgSystemSplitLaunch.inSystemSplit(new int[] {1, 6}));
    }

    /** A split task keeps its mode (stays in its stage); any other card launches fullscreen. */
    @Test public void launchMode() {
        assertEquals(1, HgSystemSplitLaunch.launchWindowingMode(false));
        assertEquals(0, HgSystemSplitLaunch.launchWindowingMode(true));
    }

    /** Split and floating cards keep their mode; a floating card is not a split launch. */
    @Test public void floatingKeepsItsMode() {
        assertTrue(HgSystemSplitLaunch.keepsWindowingMode(new int[] {5}));
        assertTrue(HgSystemSplitLaunch.keepsWindowingMode(new int[] {6}));
        assertFalse(HgSystemSplitLaunch.keepsWindowingMode(new int[] {1}));
        assertFalse(HgSystemSplitLaunch.inSystemSplit(new int[] {5}));
    }

    /** In one-app split a single resizable card fills the empty half; pairs and others do not. */
    @Test public void oneAppSplitFill() {
        assertTrue(HgSystemSplitLaunch.fillsOneAppSplit(true, 1, true));
        assertFalse(HgSystemSplitLaunch.fillsOneAppSplit(false, 1, true));
        assertFalse(HgSystemSplitLaunch.fillsOneAppSplit(true, 1, false)); // not resizable
        assertFalse(HgSystemSplitLaunch.fillsOneAppSplit(true, 2, true)); // a pair card
    }
}
