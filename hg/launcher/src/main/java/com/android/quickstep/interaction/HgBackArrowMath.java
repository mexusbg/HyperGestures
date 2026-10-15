// HyperGestures: the numbers of stock HyperOS's back indicator (MiuiHome's GestureBackArrowView,
// GesturesBackTouchProcessor and BackGestureUtils.laterFriction), pure so they are testable.
// HgMiuiBackArrow draws with them. Travel is raw px, not dp, as in MiuiHome (its tests ignore
// density); the stock feel on the 1200 px wide phone.
package com.android.quickstep.interaction;

final class HgBackArrowMath {
    private HgBackArrowMath() {}

    /** The bulge's full width (scale 1) and height, dp: MiuiHome's 84 x 858 px xxhdpi bitmap. */
    static final float BULGE_WIDTH_DP = 28f;
    static final float BULGE_HEIGHT_DP = 286f;
    /** The Gaussian's sigma, dp (137 px at xxhdpi, measured from the bitmap's alpha coverage). */
    static final float BULGE_SIGMA_DP = 45.7f;

    /** The chevron's outer size and stroke, dp (the 20 x 39 px xxhdpi arrow bitmap). */
    static final float CHEVRON_WIDTH_DP = 6.7f;
    static final float CHEVRON_HEIGHT_DP = 13f;
    static final float CHEVRON_STROKE_DP = 2.1f;

    /** The bulge is anchored this far above the touch-down (raw px), not following the finger. */
    static final float ANCHOR_ABOVE_DOWN_PX = 20f;

    /** laterFriction's input is the sideways travel over this, raw px. */
    static final float STRETCH_TRAVEL_PX = 360f;
    static final float FRICTION_START = 0.8f;
    static final float FRICTION_RANGE = 2f;
    static final float FRICTION_FACTOR = 0.5f;
    /** The stretch's cap: FRICTION_START + FRICTION_FACTOR x FRICTION_RANGE / 3 (1.133). */
    static final float MAX_SCALE = FRICTION_START + FRICTION_FACTOR * FRICTION_RANGE / 3f;

    /**
     * Past this sideways travel the chevron shows (isShowBackArrow, d > 180) and a slow release
     * goes back. The same 180 px as the ready tick, so the arrow and the tick agree.
     */
    static final float ARM_TRAVEL_PX = HgBackHapticTimer.READY_TRAVEL_PX;
    /** The chevron is drawn only while the bulge is wider than this scale. */
    static final float ARROW_MIN_SCALE = 0.1f;

    static final int ARROW_ALPHA_MAX = 255;
    /** Arm: the chevron fades in over 100 ms, disarm: out over 50 ms; Decelerate(1.5). */
    static final long ARM_FADE_MS = 100;
    static final long DISARM_FADE_MS = 50;
    static final float FADE_DECELERATE = 1.5f;
    /** Release (back or cancel): the bulge collapses to the edge in 100 ms (Decelerate). */
    static final long COLLAPSE_MS = 100;

    /**
     * BackGestureUtils.laterFriction: x itself up to start, then start + factor x range x
     * (t - t^2 + t^3 / 3) with t = min((x - start) / range, 1), so it caps at
     * start + factor x range / 3.
     */
    static float laterFriction(float x, float start, float range, float factor) {
        if (x < start) return x;
        float t = Math.min((x - start) / range, 1f);
        return start + factor * range * (t - t * t + t * t * t / 3f);
    }

    /**
     * The bulge's width scale for travelPx sideways (inward) from the touch-down:
     * laterFriction(max(d, 0) / 360, 0.8, 2, 0.5). Linear (d / 360) up to 288 px, 1.133 from
     * 1008 px.
     */
    static float stretchScale(float travelPx) {
        return laterFriction(Math.max(travelPx, 0f) / STRETCH_TRAVEL_PX, FRICTION_START,
                FRICTION_RANGE, FRICTION_FACTOR);
    }

    /** The bulge's drawn width in px at scale: only the width stretches, never the height. */
    static float bulgeWidthPx(float density, float scale) {
        return BULGE_WIDTH_DP * density * scale;
    }

    /**
     * The bulge's width from the edge at row y, full scale: a Gaussian, peak maxWidth at the
     * vertical centre of height, sigma as given (all px).
     */
    static float bulgeProfile(float y, float height, float maxWidth, float sigma) {
        float d = y - height / 2f;
        return (float) (maxWidth * Math.exp(-(d * d) / (2f * sigma * sigma)));
    }

    /** Travel away from the edge: dx for the left edge, -dx for the right. */
    static float inwardTravel(boolean leftEdge, float dx) {
        return leftEdge ? dx : -dx;
    }

    /** The chevron shows: past 180 px while a release would go back (AOSP's trigger state). */
    static boolean isArmed(float inwardTravelPx, boolean triggerBack) {
        return triggerBack && inwardTravelPx > ARM_TRAVEL_PX;
    }

    /** The chevron's alpha target (0 or 255) and how long the fade there takes. */
    static int arrowTargetAlpha(boolean armed) {
        return armed ? ARROW_ALPHA_MAX : 0;
    }

    static long arrowFadeMs(boolean armed) {
        return armed ? ARM_FADE_MS : DISARM_FADE_MS;
    }

    /** The arrow bitmap's own alpha: white at ~90 % (#E6FFFFFF). */
    static final int ARROW_PAINT_ALPHA_MAX = 0xE6;

    /** The paint alpha for arrow alpha 0..255: the bitmap's alpha times it. */
    static int arrowPaintAlpha(int alpha) {
        int a = Math.max(0, Math.min(ARROW_ALPHA_MAX, alpha));
        return Math.round(a * (float) ARROW_PAINT_ALPHA_MAX / ARROW_ALPHA_MAX);
    }

    /** Whether the chevron is drawn: visible and the bulge wide enough to hold it. */
    static boolean drawsArrow(int alpha, float scale) {
        return alpha > 0 && scale > ARROW_MIN_SCALE;
    }
}
