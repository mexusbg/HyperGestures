// HyperGestures: the maths of the corner drag's targets (HgCornerDrag, HgCornerTargetsView) and of
// the card over them (TaskViewSimulator), as MiuiHome draws its small-window target (HyperOS 3
// MiuiHome 7.50 SmallWindowCrop, NavStubView.updateTopWindowCrop, GestureHomeCalculator), mirrored
// for the split corner HyperOS 4 adds top-left. Pure maths, so it is testable; rects are float[4]
// {left, top, right, bottom} in screen px.
//
// Constants marked [V] are MiuiHome's (read in code or resources); [G] are read from the owner's
// recording of HyperOS 4 (2026-10-10), where the binaries hold no numbers (Flutter AOT).
package com.android.quickstep;

public final class HgCornerGeometry {
    /** [V] The target starts to show with the finger this far from its corner (of the height)... */
    static final float FAR_FRACTION = 0.8f;
    /** ...and is fully grown, and holds, this near (portrait). */
    static final float NEAR_FRACTION = 0.363f;
    /** [V] The circle's alpha reaches 1 at this approach. */
    static final float ALPHA_RAMP = 0.2f;
    /** [V] small_window_show_item / small_window_hold_item: the circle's diameter, far to near. */
    static final float IDLE_DP = 32f;
    static final float HOLD_DP = 74f;
    /** [V] small_window_icon_show_item / _hold_item: the glyph's size, far to near. */
    static final float GLYPH_IDLE_DP = 10f;
    static final float GLYPH_HOLD_DP = 20f;
    /** [V] small_window_init_ver_right/top: the circle's outer corner, in from the corner. */
    static final float INSET_DP = 30f;
    /** [V] Approaching, the circle slides this much of min(W, H) toward the bottom centre. */
    static final float SLIDE_FRACTION = 0.08f;
    /** [V] Holding needs the finger slower than this (dp per ms), unless it already holds. */
    static final float HOLD_SPEED_DP_PER_MS = 2f;
    /** [V] small_window_app_init_stroke: the floating frame is the card's rect outset by this. */
    static final float FRAME_OUTSET_DP = 12f;
    /** [V] GestureHomeCalculator: held over the floating target, the card ends this small. */
    public static final float FLOATING_CARD_SCALE = 0.8f;
    /** [G] The split panel, as fractions of the screen (x 25..450, y 60..515 of 480 x 1043). */
    static final float PANEL_LEFT = 0.052f;
    static final float PANEL_RIGHT = 0.938f;
    static final float PANEL_TOP = 0.058f;
    static final float PANEL_BOTTOM = 0.494f;
    /** [G] The split panel's corner radius (about 40 px of 480). */
    static final float PANEL_RADIUS_DP = 32f;
    /** [G] Held over the split target, the card ends this fraction of the screen (100 x 220). */
    static final float SPLIT_CARD_FRACTION = 0.21f;
    /** [G] The card sits this far in from the panel's top-left corner (as the floating frame). */
    static final float SPLIT_CARD_INSET_DP = FRAME_OUTSET_DP;
    /** [G] The split glyph's centre below the panel's top edge, and its size, on the panel. */
    static final float PANEL_GLYPH_TOP_DP = 36f;
    static final float PANEL_GLYPH_DP = 24f;

    private HgCornerGeometry() {}

    /**
     * [V] MiuiHome's approach: 0 with the finger FAR_FRACTION of the height or more from the
     * corner at ({@code cornerX}, 0), rising to 1 at NEAR_FRACTION, and staying 1 nearer.
     */
    static float approach(float x, float y, float cornerX, float height) {
        if (height <= 0) return 0f;
        float dist = (float) Math.hypot(cornerX - x, y);
        float per = (dist - FAR_FRACTION * height) / ((NEAR_FRACTION - FAR_FRACTION) * height);
        return clamp01(per);
    }

    /** [V] The circle's alpha at {@code approach}. */
    static float circleAlpha(float approach) {
        return clamp01(approach / ALPHA_RAMP);
    }

    /**
     * [V] Whether the finger holds over a target: fully approached (within NEAR_FRACTION of its
     * corner) and either already holding or slower than HOLD_SPEED_DP_PER_MS.
     */
    static boolean holds(float approach, boolean holding, float speedPxPerMs, float density) {
        return approach >= 1f && (holding || speedPxPerMs < HOLD_SPEED_DP_PER_MS * density);
    }

    /**
     * Our rule: within this fraction of the width either side of the centre line no corner starts
     * a hold, so a straight swipe up stays a plain gesture (near the top middle both corners are
     * within NEAR_FRACTION); a corner already held stays held there.
     */
    static final float CENTER_BAND_FRACTION = 0.08f;

    /**
     * The corner the finger holds over: -1 split (left), 1 floating (right), 0 none. {@code held}
     * is the corner held so far (same encoding). A held corner stays held while its own hold rule
     * does (no switching on jitter where both qualify). Otherwise, outside the centre band, the
     * offered corner whose rule holds; the nearer one if both do.
     */
    static int heldCorner(float x, float y, float width, float height, boolean splitOffered,
            boolean floatingOffered, int held, float speedPxPerMs, float density) {
        boolean split = splitOffered && holds(approach(x, y, 0, height), held == -1,
                speedPxPerMs, density);
        boolean floating = floatingOffered && holds(approach(x, y, width, height), held == 1,
                speedPxPerMs, density);
        if (held == -1 && split) return -1;
        if (held == 1 && floating) return 1;
        if (Math.abs(x - width / 2f) <= CENTER_BAND_FRACTION * width) return 0;
        if (split && floating) return x < width / 2f ? -1 : 1;
        return split ? -1 : floating ? 1 : 0;
    }

    /**
     * [V] The circle at {@code approach}: its diameter grows from IDLE_DP to HOLD_DP; its outer
     * corner starts INSET_DP in from the screen's top corner (below the status bar) and slides
     * SLIDE_FRACTION of min(W, H) times the approach along the line from the corner to the bottom
     * centre. {@code right}: the floating corner; otherwise the split corner, mirrored.
     */
    static void circle(boolean right, float approach, float width, float height, float density,
            float statusBar, float[] out) {
        float size = lerp(IDLE_DP, HOLD_DP, approach) * density;
        float slide = approach * SLIDE_FRACTION * Math.min(width, height);
        float diagonal = (float) Math.hypot(width / 2f, height);
        float dx = diagonal > 0 ? slide * (width / 2f) / diagonal : 0f;
        float dy = diagonal > 0 ? slide * height / diagonal : 0f;
        float inset = INSET_DP * density;
        float top = inset + statusBar + dy;
        float left = right ? width - inset - dx - size : inset + dx;
        set(out, left, top, left + size, top + size);
    }

    /** [V] The glyph's size at {@code approach}, px. */
    static float glyphSize(float approach, float density) {
        return lerp(GLYPH_IDLE_DP, GLYPH_HOLD_DP, approach) * density;
    }

    /** [G] The split panel on a {@code width} x {@code height} screen. */
    static void panel(float width, float height, float[] out) {
        set(out, PANEL_LEFT * width, PANEL_TOP * height, PANEL_RIGHT * width,
                PANEL_BOTTOM * height);
    }

    /** [G] Where the card ends over the split panel: SPLIT_CARD_FRACTION, in its top-left. */
    static void splitCard(float width, float height, float density, float[] out) {
        float left = PANEL_LEFT * width + SPLIT_CARD_INSET_DP * density;
        float top = PANEL_TOP * height + SPLIT_CARD_INSET_DP * density;
        set(out, left, top, left + SPLIT_CARD_FRACTION * width,
                top + SPLIT_CARD_FRACTION * height);
    }

    /** [V] The floating frame around the card: its rect outset by FRAME_OUTSET_DP. */
    static void frame(float[] card, float density, float[] out) {
        float outset = FRAME_OUTSET_DP * density;
        set(out, card[0] - outset, card[1] - outset, card[2] + outset, card[3] + outset);
    }

    /**
     * The card's transform over the targets, applied after every other step: {scale about the
     * card's centre, then translation x, y}. First [V] the floating shrink, toward
     * FLOATING_CARD_SCALE by {@code floating}; then [G] by {@code split} toward the split target
     * {@code tl..tb}, the card fitted and centred in it (ignored while that is empty). The two mix
     * continuously, so moving between targets never jumps.
     */
    public static void cardTransform(float bl, float bt, float br, float bb, float floating,
            float tl, float tt, float tr, float tb, float split, float[] out) {
        float scale = lerp(1f, FLOATING_CARD_SCALE, clamp01(floating));
        float w = (br - bl) * scale;
        float h = (bb - bt) * scale;
        float s = clamp01(split);
        if (s <= 0f || w <= 0f || h <= 0f || tr <= tl || tb <= tt) {
            out[0] = scale;
            out[1] = 0f;
            out[2] = 0f;
            return;
        }
        float fit = Math.min((tr - tl) / w, (tb - tt) / h);
        out[0] = scale * lerp(1f, fit, s);
        out[1] = s * ((tl + tr) / 2f - (bl + br) / 2f);
        out[2] = s * ((tt + tb) / 2f - (bt + bb) / 2f);
    }

    /** {@code out} = {@code a} to {@code b} at {@code t}. */
    static void lerp(float[] a, float[] b, float t, float[] out) {
        for (int i = 0; i < 4; i++) out[i] = lerp(a[i], b[i], t);
    }

    static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    static float clamp01(float v) {
        return v < 0f ? 0f : v > 1f ? 1f : v;
    }

    private static void set(float[] out, float l, float t, float r, float b) {
        out[0] = l;
        out[1] = t;
        out[2] = r;
        out[3] = b;
    }
}
