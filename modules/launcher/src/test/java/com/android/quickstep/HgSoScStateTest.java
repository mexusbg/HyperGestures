package com.android.quickstep;

import static org.junit.Assert.assertEquals;

import com.android.wm.shell.shared.split.SplitScreenConstants;

import org.junit.Test;

/** The snap position given to the gesture's SplitBounds from HyperOS's stage sizes. */
public class HgSoScStateTest {
    /** The owner's top/bottom split: 1292 and 1292 px around a 24 px divider. */
    @Test public void evenSplitIsFiftyFifty() {
        assertEquals(SplitScreenConstants.SNAP_TO_2_50_50, HgSoScState.snapPosition(1292, 1292));
    }

    @Test public void thirdsAndTenths() {
        assertEquals(SplitScreenConstants.SNAP_TO_2_33_66, HgSoScState.snapPosition(860, 1724));
        assertEquals(SplitScreenConstants.SNAP_TO_2_66_33, HgSoScState.snapPosition(1724, 860));
        assertEquals(SplitScreenConstants.SNAP_TO_2_10_90, HgSoScState.snapPosition(260, 2324));
        assertEquals(SplitScreenConstants.SNAP_TO_2_90_10, HgSoScState.snapPosition(2324, 260));
    }

    /** States 0/1 are the one-app split; -1 (off) and 2 (both halves) are not. */
    @Test public void oneAppStates() {
        assertEquals(true, HgSoScState.isOneAppState(0));
        assertEquals(true, HgSoScState.isOneAppState(1));
        assertEquals(false, HgSoScState.isOneAppState(-1));
        assertEquals(false, HgSoScState.isOneAppState(HgSoScState.STATE_FULL));
    }

    @Test public void emptySizesDoNotDivideByZero() {
        assertEquals(SplitScreenConstants.SNAP_TO_2_10_90, HgSoScState.snapPosition(0, 0));
    }
}
