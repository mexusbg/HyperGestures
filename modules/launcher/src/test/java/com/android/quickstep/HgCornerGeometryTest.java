package com.android.quickstep;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HgCornerGeometryTest {
    private static final float W = 1200;
    private static final float H = 2608;
    private static final float D = 3f; // density
    private static final float SB = 100f; // status bar
    private static final float E = 0.01f;

    // --- approach and hold ([V] MiuiHome's distance rule) -------------------------------------

    @Test public void approachFromFarToNear() {
        assertEquals(0f, HgCornerGeometry.approach(W, 0.8f * H, W, H), E);
        assertEquals(0.5f, HgCornerGeometry.approach(W, (0.8f + 0.363f) / 2 * H, W, H), E);
        assertEquals(1f, HgCornerGeometry.approach(W, 0.363f * H, W, H), E);
    }

    /** Floored at 0 farther out (the bottom of the screen), and stays 1 nearer the corner. */
    @Test public void approachIsClamped() {
        assertEquals(0f, HgCornerGeometry.approach(W / 2, H, W, H), 0f);
        assertEquals(1f, HgCornerGeometry.approach(W - 10, 10, W, H), 0f);
        assertEquals(0f, HgCornerGeometry.approach(10, 10, W, 0), 0f);
    }

    /** The split corner measures to the top-left corner: the floating rule mirrored. */
    @Test public void splitApproachIsMirrored() {
        assertEquals(HgCornerGeometry.approach(W - 300, 900, W, H),
                HgCornerGeometry.approach(300, 900, 0, H), 1e-5f);
    }

    /** MiuiHome holds within 0.363 H of the corner: 947 px on a 2608 px screen. */
    @Test public void holdReachOnThePhone() {
        assertEquals(1f, HgCornerGeometry.approach(W, 946, W, H), 0f);
        assertTrue(HgCornerGeometry.approach(W, 960, W, H) < 1f);
    }

    @Test public void circleAlphaRampsOverTheFirstFifth() {
        assertEquals(0f, HgCornerGeometry.circleAlpha(0f), 0f);
        assertEquals(0.5f, HgCornerGeometry.circleAlpha(0.1f), E);
        assertEquals(1f, HgCornerGeometry.circleAlpha(0.2f), E);
        assertEquals(1f, HgCornerGeometry.circleAlpha(1f), 0f);
    }

    /** Holding needs a full approach and a slow finger (2 dp/ms), unless already holding. */
    @Test public void holdNeedsSlowFingerToEnter() {
        assertFalse(HgCornerGeometry.holds(0.99f, false, 0f, D));
        assertTrue(HgCornerGeometry.holds(1f, false, 5.9f, D));
        assertFalse(HgCornerGeometry.holds(1f, false, 6.1f, D));
        assertTrue(HgCornerGeometry.holds(1f, true, 20f, D));
        assertFalse(HgCornerGeometry.holds(0.9f, true, 0f, D));
    }

    @Test public void heldCornerPerSide() {
        assertEquals(1, HgCornerGeometry.heldCorner(1100, 300, W, H, true, true, 0, 0f, D));
        assertEquals(-1, HgCornerGeometry.heldCorner(100, 300, W, H, true, true, 0, 0f, D));
        assertEquals(0, HgCornerGeometry.heldCorner(600, 1800, W, H, true, true, 0, 0f, D));
    }

    /** Near the top middle both corners are within reach: the nearer one holds. */
    @Test public void heldCornerNearTheMiddleIsTheNearer() {
        assertEquals(-1, HgCornerGeometry.heldCorner(500, 200, W, H, true, true, 0, 0f, D));
        assertEquals(1, HgCornerGeometry.heldCorner(700, 200, W, H, true, true, 0, 0f, D));
        // Only the split corner is offered: it holds even from the right half.
        assertEquals(-1, HgCornerGeometry.heldCorner(700, 200, W, H, true, false, 0, 0f, D));
    }

    /** Jitter across the centre line near the top never switches the held corner. */
    @Test public void centreJitterKeepsTheHeldCorner() {
        for (float x = 590; x <= 610; x += 5) {
            assertEquals(-1, HgCornerGeometry.heldCorner(x, 200, W, H, true, true, -1, 0f, D));
            assertEquals(1, HgCornerGeometry.heldCorner(x, 200, W, H, true, true, 1, 0f, D));
        }
        // Outside the band too, both qualifying: the held corner stays.
        assertEquals(-1, HgCornerGeometry.heldCorner(750, 200, W, H, true, true, -1, 0f, D));
        assertEquals(1, HgCornerGeometry.heldCorner(450, 200, W, H, true, true, 1, 0f, D));
    }

    /** A slow straight swipe up the middle (within 8% W of the centre) never arms. */
    @Test public void straightSwipeInTheCentreBandNeverArms() {
        for (float y = H; y >= 0; y -= 50) {
            for (float x : new float[] {504, 600, 696}) {
                assertEquals(0,
                        HgCornerGeometry.heldCorner(x, y, W, H, true, true, 0, 0f, D));
            }
        }
    }

    /** Outside the band, with both in reach and none held, the nearer corner wins. */
    @Test public void nearerWinsOutsideTheBand() {
        assertEquals(-1, HgCornerGeometry.heldCorner(450, 200, W, H, true, true, 0, 0f, D));
        assertEquals(1, HgCornerGeometry.heldCorner(750, 200, W, H, true, true, 0, 0f, D));
    }

    /** A held corner is let go once its own hold rule fails, even inside the band. */
    @Test public void heldCornerLetGoWhenItsRuleFails() {
        assertEquals(0, HgCornerGeometry.heldCorner(600, 1200, W, H, true, true, -1, 0f, D));
        // Split no longer qualifies (not offered): the other corner may take over.
        assertEquals(1, HgCornerGeometry.heldCorner(1100, 300, W, H, false, true, -1, 0f, D));
    }

    /** A corner whose action can't be offered never holds; a fast finger doesn't enter. */
    @Test public void heldCornerNeedsItsActionAndASlowFinger() {
        assertEquals(0, HgCornerGeometry.heldCorner(1100, 300, W, H, true, false, 0, 0f, D));
        assertEquals(0, HgCornerGeometry.heldCorner(100, 300, W, H, false, true, 0, 0f, D));
        assertEquals(0, HgCornerGeometry.heldCorner(1100, 300, W, H, true, true, 0, 9f, D));
        assertEquals(1, HgCornerGeometry.heldCorner(1100, 300, W, H, true, true, 1, 9f, D));
    }

    // --- the circle ([V]) ---------------------------------------------------------------------

    @Test public void idleCircleSitsInTheCorner() {
        float[] r = new float[4];
        HgCornerGeometry.circle(true, 0f, W, H, D, SB, r);
        assertArrayEquals(new float[] {W - 90 - 96, 90 + SB, W - 90, 90 + SB + 96}, r, E);
        HgCornerGeometry.circle(false, 0f, W, H, D, SB, r);
        assertArrayEquals(new float[] {90, 90 + SB, 90 + 96, 90 + SB + 96}, r, E);
    }

    /** Fully approached: 74 dp, slid 0.08 min(W, H) toward the bottom centre. */
    @Test public void approachedCircleGrowsAndSlides() {
        float diag = (float) Math.hypot(W / 2, H);
        float dx = 96f * (W / 2) / diag;
        float dy = 96f * H / diag;
        float[] r = new float[4];
        HgCornerGeometry.circle(true, 1f, W, H, D, SB, r);
        assertArrayEquals(new float[] {W - 90 - dx - 222, 90 + SB + dy, W - 90 - dx,
                90 + SB + dy + 222}, r, E);
        HgCornerGeometry.circle(false, 1f, W, H, D, SB, r);
        assertArrayEquals(new float[] {90 + dx, 90 + SB + dy, 90 + dx + 222,
                90 + SB + dy + 222}, r, E);
    }

    @Test public void glyphGrowsTenToTwentyDp() {
        assertEquals(30f, HgCornerGeometry.glyphSize(0f, D), E);
        assertEquals(60f, HgCornerGeometry.glyphSize(1f, D), E);
    }

    // --- the targets held over ----------------------------------------------------------------

    @Test public void floatingFrameIsTheCardOutsetTwelveDp() {
        float[] r = new float[4];
        HgCornerGeometry.frame(new float[] {100, 200, 500, 1000}, D, r);
        assertArrayEquals(new float[] {64, 164, 536, 1036}, r, E);
    }

    @Test public void splitPanelCoversTheTopHalf() {
        float[] r = new float[4];
        HgCornerGeometry.panel(W, H, r);
        assertArrayEquals(new float[] {0.052f * W, 0.058f * H, 0.938f * W, 0.494f * H}, r, E);
    }

    @Test public void splitCardSitsInThePanelsTopLeft() {
        float[] r = new float[4];
        HgCornerGeometry.splitCard(W, H, D, r);
        float left = 0.052f * W + 36;
        float top = 0.058f * H + 36;
        assertArrayEquals(new float[] {left, top, left + 0.21f * W, top + 0.21f * H}, r, E);
        float[] panel = new float[4];
        HgCornerGeometry.panel(W, H, panel);
        assertTrue(r[2] < panel[2] && r[3] < panel[3]);
    }

    // --- the card's transform -----------------------------------------------------------------

    @Test public void cardUntouchedAtRest() {
        float[] out = new float[3];
        HgCornerGeometry.cardTransform(100, 500, 700, 1800, 0f, 100, 200, 352, 750, 0f, out);
        assertArrayEquals(new float[] {1f, 0f, 0f}, out, 1e-5f);
    }

    /** [V] Held over the floating target the card ends 0.8 of its size, about its centre. */
    @Test public void floatingShrinksToEightTenths() {
        float[] out = new float[3];
        HgCornerGeometry.cardTransform(100, 500, 700, 1800, 1f, 0, 0, 0, 0, 0f, out);
        assertArrayEquals(new float[] {0.8f, 0f, 0f}, out, 1e-5f);
        HgCornerGeometry.cardTransform(100, 500, 700, 1800, 0.5f, 0, 0, 0, 0, 0f, out);
        assertEquals(0.9f, out[0], 1e-5f);
    }

    /** Held over the split target the card is fitted and centred in it. */
    @Test public void splitFitsTheCardIntoTheTarget() {
        float[] out = new float[3];
        HgCornerGeometry.cardTransform(100, 500, 700, 1800, 0f, 100, 200, 352, 750, 1f, out);
        assertEquals(0.42f, out[0], 1e-4f); // min(252 / 600, 550 / 1300)
        assertEquals(226f - 400f, out[1], E);
        assertEquals(475f - 1150f, out[2], E);
        HgCornerGeometry.cardTransform(100, 500, 700, 1800, 0f, 100, 200, 352, 750, 0.5f, out);
        assertEquals(0.71f, out[0], 1e-4f);
        assertEquals((226f - 400f) / 2, out[1], E);
    }

    /** Both at once (moving between targets) still end exactly in the split target: no jump. */
    @Test public void splitEndsInTheTargetWhateverTheFloatingShrink() {
        float[] a = new float[3];
        float[] b = new float[3];
        HgCornerGeometry.cardTransform(100, 500, 700, 1800, 0f, 100, 200, 352, 750, 1f, a);
        HgCornerGeometry.cardTransform(100, 500, 700, 1800, 1f, 100, 200, 352, 750, 1f, b);
        assertArrayEquals(a, b, 1e-4f);
    }

    /** No split target yet (empty): the split progress is ignored. */
    @Test public void emptySplitTargetIsIgnored() {
        float[] out = new float[3];
        HgCornerGeometry.cardTransform(100, 500, 700, 1800, 0f, 0, 0, 0, 0, 1f, out);
        assertArrayEquals(new float[] {1f, 0f, 0f}, out, 1e-5f);
    }

    /** Overshooting end animations stay within the shrink's range. */
    @Test public void progressIsClamped() {
        float[] out = new float[3];
        HgCornerGeometry.cardTransform(100, 500, 700, 1800, -0.2f, 0, 0, 0, 0, 0f, out);
        assertEquals(1f, out[0], 1e-5f);
        HgCornerGeometry.cardTransform(100, 500, 700, 1800, 1.3f, 0, 0, 0, 0, 0f, out);
        assertEquals(0.8f, out[0], 1e-5f);
    }

    @Test public void lerpRect() {
        float[] out = new float[4];
        HgCornerGeometry.lerp(new float[] {0, 0, 10, 10}, new float[] {10, 20, 30, 50}, 0.5f,
                out);
        assertArrayEquals(new float[] {5, 10, 20, 30}, out, 1e-5f);
    }
}
