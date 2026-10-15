package com.android.quickstep;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HgTaskActionsLayoutTest {
    /** Buttons on the left: the card slides right until its left edge clears them. */
    @Test public void shiftClearsTheButtonsOnTheLeft() {
        // card 180..1020 of 1200, buttons need 342 px from the left edge
        assertEquals(162f, HgTaskActionsView.shiftFor(true, 180, 1020, 1200, 342, 108), 0.001f);
    }

    /** Buttons on the right: the card slides left, the same distance by symmetry. */
    @Test public void shiftClearsTheButtonsOnTheRight() {
        assertEquals(-162f, HgTaskActionsView.shiftFor(false, 180, 1020, 1200, 342, 108), 0.001f);
    }

    /** A card already clear of the buttons still slides the minimum, for a visible response. */
    @Test public void shiftIsAtLeastTheMinimum() {
        assertEquals(108f, HgTaskActionsView.shiftFor(true, 600, 1100, 1200, 342, 108), 0.001f);
    }

    /** The arc bulges in the middle (MIUI's screenshots), not at the ends. */
    @Test public void arcBulgesInTheMiddle() {
        assertEquals(0f, HgTaskActionsView.arcFraction(0, 4), 0.001f);
        assertEquals(0f, HgTaskActionsView.arcFraction(3, 4), 0.001f);
        assertEquals(8f / 9f, HgTaskActionsView.arcFraction(1, 4), 0.001f);
        assertEquals(1f, HgTaskActionsView.arcFraction(1, 3), 0.001f);
        assertEquals(1f, HgTaskActionsView.arcFraction(0, 1), 0.001f);
    }

    /** The column centres on the card, but never runs past the insets. */
    @Test public void columnStaysInsideTheInsets() {
        // 4 buttons, 210 px apart, 168 px tall: 399 px from the centre to an outer edge
        assertEquals(1300f, HgTaskActionsView.clampColumnCenter(1300, 4, 210, 168, 100, 2500), 0.001f);
        assertEquals(499f, HgTaskActionsView.clampColumnCenter(200, 4, 210, 168, 100, 2500), 0.001f);
        assertEquals(2101f, HgTaskActionsView.clampColumnCenter(2400, 4, 210, 168, 100, 2500), 0.001f);
    }
}
