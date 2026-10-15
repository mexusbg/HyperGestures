package com.android.quickstep.interaction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HgBackArrowMathTest {
    private static final float EPS = 1e-4f;

    /** laterFriction is the identity below its start. */
    @Test public void frictionLinearBelowStart() {
        assertEquals(0f, HgBackArrowMath.laterFriction(0f, 0.8f, 2f, 0.5f), EPS);
        assertEquals(0.5f, HgBackArrowMath.laterFriction(0.5f, 0.8f, 2f, 0.5f), EPS);
        assertEquals(0.8f, HgBackArrowMath.laterFriction(0.8f, 0.8f, 2f, 0.5f), EPS);
    }

    /** Past its start: start + factor x range x (t - t^2 + t^3 / 3), t capped at 1. */
    @Test public void frictionCurveAndCap() {
        // x = 1.0: t = 0.1 -> 0.8 + 1 x (0.1 - 0.01 + 0.001 / 3)
        assertEquals(0.8903333f, HgBackArrowMath.laterFriction(1f, 0.8f, 2f, 0.5f), EPS);
        // x = 2.8: t = 1 -> 0.8 + 1/3
        assertEquals(1.1333333f, HgBackArrowMath.laterFriction(2.8f, 0.8f, 2f, 0.5f), EPS);
        assertEquals(1.1333333f, HgBackArrowMath.laterFriction(50f, 0.8f, 2f, 0.5f), EPS);
        assertEquals(1.1333333f, HgBackArrowMath.MAX_SCALE, EPS);
    }

    /** The spec's samples: d = 180 -> 0.5, 288 -> 0.8, 360 -> 0.89, capped from 1008 px. */
    @Test public void stretchScaleSamples() {
        assertEquals(0f, HgBackArrowMath.stretchScale(0f), EPS);
        assertEquals(0.5f, HgBackArrowMath.stretchScale(180f), EPS);
        assertEquals(0.8f, HgBackArrowMath.stretchScale(288f), EPS);
        assertEquals(0.8903333f, HgBackArrowMath.stretchScale(360f), EPS);
        // 540 px: t = 0.35 -> 0.8 + 0.35 - 0.1225 + 0.042875 / 3
        assertEquals(1.0417917f, HgBackArrowMath.stretchScale(540f), EPS);
        assertEquals(HgBackArrowMath.MAX_SCALE, HgBackArrowMath.stretchScale(1008f), EPS);
        assertEquals(HgBackArrowMath.MAX_SCALE, HgBackArrowMath.stretchScale(3000f), EPS);
    }

    /** Travel toward the edge (negative) leaves the bulge flat. */
    @Test public void stretchScaleNeverNegative() {
        assertEquals(0f, HgBackArrowMath.stretchScale(-50f), EPS);
    }

    /** The stretch only grows with travel, and never past the cap. */
    @Test public void stretchScaleMonotonic() {
        float last = -1;
        for (float d = 0; d <= 1500; d += 0.5f) {
            float s = HgBackArrowMath.stretchScale(d);
            assertTrue("d=" + d, s >= last);
            assertTrue("d=" + d, s <= HgBackArrowMath.MAX_SCALE + EPS);
            last = s;
        }
    }

    /** Only the width stretches: 28 dp x scale (84 px at 480 dpi, density 3). */
    @Test public void bulgeWidth() {
        assertEquals(84f, HgBackArrowMath.bulgeWidthPx(3f, 1f), EPS);
        assertEquals(42f, HgBackArrowMath.bulgeWidthPx(3f, 0.5f), EPS);
        assertEquals(84f * HgBackArrowMath.MAX_SCALE,
                HgBackArrowMath.bulgeWidthPx(3f, HgBackArrowMath.stretchScale(2000f)), 1e-3f);
    }

    /** The Gaussian: peak at the centre, symmetric, the bitmap's measured rows at xxhdpi. */
    @Test public void bulgeProfileMatchesTheBitmap() {
        float h = 858, w = 84, sigma = 137;
        assertEquals(84f, HgBackArrowMath.bulgeProfile(429, h, w, sigma), EPS);
        assertEquals(HgBackArrowMath.bulgeProfile(429 - 100, h, w, sigma),
                HgBackArrowMath.bulgeProfile(429 + 100, h, w, sigma), EPS);
        // Measured coverage: row 297 -> 53.2 px, 198 -> 20.2, 99 -> 4.4 (within a pixel).
        assertEquals(53.2f, HgBackArrowMath.bulgeProfile(297, h, w, sigma), 1f);
        assertEquals(20.2f, HgBackArrowMath.bulgeProfile(198, h, w, sigma), 1f);
        assertEquals(4.4f, HgBackArrowMath.bulgeProfile(99, h, w, sigma), 1f);
        // The dp constants give the same shape at density 3.
        assertEquals(sigma, HgBackArrowMath.BULGE_SIGMA_DP * 3, 0.2f);
        assertEquals(h, HgBackArrowMath.BULGE_HEIGHT_DP * 3, EPS);
        assertEquals(w, HgBackArrowMath.BULGE_WIDTH_DP * 3, EPS);
    }

    /** Inward travel: rightward from the left edge, leftward from the right edge. */
    @Test public void inwardTravelBothEdges() {
        assertEquals(200f, HgBackArrowMath.inwardTravel(true, 200f), EPS);
        assertEquals(-200f, HgBackArrowMath.inwardTravel(true, -200f), EPS);
        assertEquals(200f, HgBackArrowMath.inwardTravel(false, -200f), EPS);
        assertEquals(-200f, HgBackArrowMath.inwardTravel(false, 200f), EPS);
    }

    /** The chevron arms past 180 px (strict), only while AOSP's state would trigger back. */
    @Test public void armedPast180WhileTriggering() {
        assertFalse(HgBackArrowMath.isArmed(180f, true));
        assertTrue(HgBackArrowMath.isArmed(180.5f, true));
        assertFalse(HgBackArrowMath.isArmed(500f, false));
        assertEquals(HgBackHapticTimer.READY_TRAVEL_PX, HgBackArrowMath.ARM_TRAVEL_PX, 0f);
    }

    /** Arm: to 255 in 100 ms; disarm: to 0 in 50 ms; the release collapse takes 100 ms. */
    @Test public void arrowFade() {
        assertEquals(255, HgBackArrowMath.arrowTargetAlpha(true));
        assertEquals(0, HgBackArrowMath.arrowTargetAlpha(false));
        assertEquals(100, HgBackArrowMath.arrowFadeMs(true));
        assertEquals(50, HgBackArrowMath.arrowFadeMs(false));
        assertEquals(100, HgBackArrowMath.COLLAPSE_MS);
        assertEquals(1.5f, HgBackArrowMath.FADE_DECELERATE, 0f);
    }

    /** The chevron is white at ~90 % (#E6FFFFFF = 230) when fully shown. */
    @Test public void arrowPaintAlpha() {
        assertEquals(230, HgBackArrowMath.arrowPaintAlpha(255));
        assertEquals(0, HgBackArrowMath.arrowPaintAlpha(0));
        assertEquals(115, HgBackArrowMath.arrowPaintAlpha(128));
        assertEquals(230, HgBackArrowMath.arrowPaintAlpha(400));
        assertEquals(0, HgBackArrowMath.arrowPaintAlpha(-3));
    }

    /** Drawn only while visible and the bulge is wider than scale 0.1. */
    @Test public void arrowDrawnOnlyInAWideBulge() {
        assertFalse(HgBackArrowMath.drawsArrow(0, 1f));
        assertFalse(HgBackArrowMath.drawsArrow(255, 0.1f));
        assertTrue(HgBackArrowMath.drawsArrow(255, 0.11f));
        assertTrue(HgBackArrowMath.drawsArrow(1, 0.5f));
    }
}
