package com.android.quickstep;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Which chip takes a tap as its header (HgTaskHeader.takesTap): the chip's own snapshot of the
 * setting decides, so a card inflated with the header keeps App info on a tap, and one inflated
 * without it never takes the tap, whatever the setting says now.
 */
public class HgTaskHeaderTest {
    @Test public void liftedVisibleHeader_takesTap() {
        assertTrue(HgTaskHeader.takesTap(true, true, -40f));
    }

    /** A chip inflated as AOSP's chip (no header) is not a header, even lifted. */
    @Test public void notAHeader_neverTakesTap() {
        assertFalse(HgTaskHeader.takesTap(false, true, -40f));
    }

    /** A header still on its card (landscape, a split's second app) gets its touch there. */
    @Test public void headerOnItsCard_isLeftToTheCard() {
        assertFalse(HgTaskHeader.takesTap(true, true, 0f));
        assertFalse(HgTaskHeader.takesTap(true, true, 3f));
    }

    @Test public void hiddenHeader_takesNoTap() {
        assertFalse(HgTaskHeader.takesTap(true, false, -40f));
    }
}
