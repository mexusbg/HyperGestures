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
    /** Buttons sit next to the card's slid edge (gap 30), the arc's middle farthest from it. */
    @Test public void buttonsAnchorToTheCardEdge() {
        // left: card edge at 400, 168 px buttons, arc 66: ends at 400-30-84, middle 66 further
        assertEquals(286f, HgTaskActionsView.buttonCenterX(true, 400, 30, 168, 66, 0f), 0.001f);
        assertEquals(220f, HgTaskActionsView.buttonCenterX(true, 400, 30, 168, 66, 1f), 0.001f);
        // right: mirrored
        assertEquals(914f, HgTaskActionsView.buttonCenterX(false, 800, 30, 168, 66, 0f), 0.001f);
        assertEquals(980f, HgTaskActionsView.buttonCenterX(false, 800, 30, 168, 66, 1f), 0.001f);
    }

    /** A card peeking in under the left column slides left until it clears it (pad 36). */
    @Test public void neighbourUnderTheLeftColumnSlidesAway() {
        // column 100..300 x 900..1700; older stack card 0..500 behind the held card
        assertEquals(-436f, HgTaskActionsView.neighbourShift(true, 0, 500, 300, 2300,
                100, 300, 900, 1700, 36), 0.001f);
    }

    @Test public void neighbourUnderTheRightColumnSlidesAway() {
        assertEquals(436f, HgTaskActionsView.neighbourShift(false, 700, 1200, 300, 2300,
                900, 1100, 900, 1700, 36), 0.001f);
    }

    /** Cards clear of the column stay put: to the side, beyond it, or above/below it. */
    @Test public void neighboursClearOfTheColumnStay() {
        // already left of the column
        assertEquals(0f, HgTaskActionsView.neighbourShift(true, -600, 50, 300, 2300,
                100, 300, 900, 1700, 36), 0.001f);
        // on the card's other side (the held card's right neighbour)
        assertEquals(0f, HgTaskActionsView.neighbourShift(true, 700, 1200, 300, 2300,
                100, 300, 900, 1700, 36), 0.001f);
        // another row of the grid, above the column
        assertEquals(0f, HgTaskActionsView.neighbourShift(true, 0, 500, 100, 850,
                100, 300, 900, 1700, 36), 0.001f);
    }
    /** The side with no other card under the buttons wins, wherever the finger is. */
    @Test public void sideWithoutNeighboursWins() {
        // first card of the stack: older cards on the left only
        assertEquals(false, HgTaskActionsView.buttonsOnLeft(true, false, true));
        assertEquals(false, HgTaskActionsView.buttonsOnLeft(false, false, true));
        // the grid's left column: nothing on its left
        assertEquals(true, HgTaskActionsView.buttonsOnLeft(false, true, false));
    }

    /** Both sides free, or both taken: the finger's side, as before. */
    @Test public void tieFollowsTheFinger() {
        assertEquals(true, HgTaskActionsView.buttonsOnLeft(true, true, true));
        assertEquals(false, HgTaskActionsView.buttonsOnLeft(false, true, true));
        assertEquals(true, HgTaskActionsView.buttonsOnLeft(true, false, false));
        assertEquals(false, HgTaskActionsView.buttonsOnLeft(false, false, false));
    }
    /** Stack (owner, 0.4.0.7): the first card's buttons on the right, every other card's left. */
    @Test public void stackFirstCardRightOthersLeft() {
        assertEquals(false, HgTaskActionsView.stackButtonsOnLeft(true, true));
        assertEquals(true, HgTaskActionsView.stackButtonsOnLeft(false, true));
    }

    /** Right-to-left: the stack is mirrored (older cards on the right), so are the sides. */
    @Test public void stackSidesMirrorRightToLeft() {
        assertEquals(true, HgTaskActionsView.stackButtonsOnLeft(true, false));
        assertEquals(false, HgTaskActionsView.stackButtonsOnLeft(false, false));
    }
}
