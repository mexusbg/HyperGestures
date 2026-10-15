// HyperGestures: MIUI's "stack" recents layout (Settings.Global task_stack_view_layout_style = 2,
// MiuiHome's TaskStackViewsAlgorithmStack + TaskStackLayoutConfig; docs/research/
// 2026-10-09-miuihome-recents-features.md section 6e), as pure maths so it is testable.
//
// Owner, 0.3.9.30: "copy MIUI fully". Every place below was read off the owner's recording of
// stock MiuiHome's stack (1200 x 2608 portrait, at rest, +-0.01 of the width W or height H):
// - The focused card (the strip's current page) is the stack's task size (BaseContainerInterface:
//   cardWidth, cardLeft, cardTop): CARD_WIDTH of the window, its shape, its left edge at
//   CARD_LEFT (not centred: it ends at 0.895 W), its top at CARD_TOP.
// - One page newer: the same size, on top of the focused card, its left edge at NEWER_EDGE,
//   running off the right edge. Two pages newer: just off the screen.
// - One page older: behind, left edge OLDER_EDGES[0], OLDER_SCALES[0] of the focused card's size;
//   two pages older: OLDER_EDGES[1] (a sliver at the screen edge), OLDER_SCALES[1]. Deeper cards
//   lie exactly behind the two-older card (same place and size).
// - In between, everything moves linearly between these rest places; newer cards are drawn on
//   top (RecentsView.getChildDrawingOrder), and so get the touches first.
// - Every card keeps its header above it, drawn with its card (so a newer card covers it): an
//   older card's header shows only within its visible strip (headerStrip; TaskView clips it at
//   the newer card's edge), so the one-older card shows its icon and its name is cut.
// Edges are measured from the screen edge on the older side (the left in left-to-right
// languages) and mirrored on the other side for right-to-left ones. Cards scale about their
// centres (TaskView's pivot).
//
// RecentsView keeps its horizontal PagedView: every card still has its natural page and scrolls
// with the finger; this class maps a card's natural place to its place in the deck. Depth is
// measured in pages from the card's natural place, including the translations the dismiss and
// gesture animations give the card, so a reflow after a dismiss or the gesture's adjacent-card
// offset glides through the deck instead of sliding by whole pages.
//
// Constants marked [video] are measured as above; [V] are MiuiHome's (HyperOS 3 smali); [G] ours.
package com.android.quickstep;

public final class HgStackLayout {
    /**
     * [video] The focused card's width, of the window's (MiuiHome's base scale ~0.7); measured
     * 0.67 W and 0.675 H tall at the window's shape: 0.675 fits both within the measuring error.
     */
    public static final float CARD_WIDTH = 0.675f;
    /** [video] The focused card's left edge (right edge in right-to-left languages), of W. */
    public static final float CARD_LEFT = 0.22f;
    /** [video] The focused card's top, of the window's height (its bottom: 0.857 H). */
    public static final float CARD_TOP = 0.182f;
    /** [video] One page newer: the card's older-side edge, of W (its far side runs off screen). */
    public static final float NEWER_EDGE = 0.66f;
    /** Two pages newer: the card's older-side edge on the far screen edge, just off screen. */
    public static final float OFF_EDGE = 1f;
    /** [video] One and two pages older: the card's older-side edge, of W. */
    private static final float[] OLDER_EDGES = {0.05f, 0f};
    /** [video] One and two pages older: the card's size, of the focused card's. */
    private static final float[] OLDER_SCALES = {0.975f, 0.95f};
    /** Cards that peek out behind the focused one; deeper ones lie hidden behind the last. */
    public static final int DECK_DEPTH = 2;
    /** [V] The deepest dim of a card behind the front one. */
    public static final float MAX_DIM = 0.23f;
    /** [V] The dim curve's exponent; [G] used as an ease-out over DIM_DEPTH steps. */
    public static final float DIM_EXPONENT = 2.5f;
    /** [G] Depth at which a card reaches MAX_DIM: the deck's last visible card. */
    public static final float DIM_DEPTH = DECK_DEPTH;
    /**
     * Cards this deep or less reach the screen (recents loads their thumbnails, flies them out on
     * Clear all, lets them be touched): the deck, and the card hidden behind its last one, which
     * comes out from there as the deck scrolls forward. Deeper cards stay hidden.
     */
    public static final int VISIBLE_BEHIND = DECK_DEPTH + 1;
    /**
     * Newer cards this many pages away or less reach the screen: the one on top of the focused
     * card at rest, and the next, which slides in from the far edge while the strip scrolls.
     */
    public static final int VISIBLE_NEWER = 2;

    private final float mPageSize;
    private final float mWindowWidth;
    private final float mCardWidth;
    /** The focused card's centre, measured from the older-side screen edge. */
    private final float mCentre;
    /** The focused card's older-side edge, of W (CARD_LEFT on the task size's screen). */
    private final float mFocusEdge;
    private final boolean mOlderToLeft;

    /**
     * @param pageSize the distance between two cards' natural places (card width + page spacing)
     * @param windowWidth the recents window's width
     * @param cardLeft the focused card's left edge (the task size's)
     * @param cardWidth the focused card's width (the task size's)
     * @param olderToLeft whether older cards are on the left (RecentsView's isRtl(), true for
     *     left-to-right languages: the newest card is the rightmost)
     */
    public HgStackLayout(float pageSize, float windowWidth, float cardLeft, float cardWidth,
            boolean olderToLeft) {
        mPageSize = pageSize;
        mWindowWidth = windowWidth;
        mCardWidth = cardWidth;
        mOlderToLeft = olderToLeft;
        float edge = olderToLeft ? cardLeft : windowWidth - cardLeft - cardWidth;
        mCentre = edge + cardWidth / 2f;
        mFocusEdge = windowWidth > 0f ? edge / windowWidth : 0f;
    }

    /** Whether older cards are on the left (left-to-right languages). */
    public boolean isOlderToLeft() {
        return mOlderToLeft;
    }

    /** The focused card's width for a {@code windowWidth} wide window (the stack's task size). */
    public static int cardWidth(int windowWidth) {
        return Math.round(CARD_WIDTH * windowWidth);
    }

    /** The focused card's height: the window's shape, as MiuiHome's. */
    public static int cardHeight(int windowWidth, int windowHeight) {
        return windowWidth <= 0 ? 0
                : Math.round(cardWidth(windowWidth) * (float) windowHeight / windowWidth);
    }

    /** The focused card's left edge: CARD_LEFT in from the older side, mirrored for RTL. */
    public static int cardLeft(int windowWidth, boolean olderToLeft) {
        int edge = Math.round(CARD_LEFT * windowWidth);
        return olderToLeft ? edge : windowWidth - edge - cardWidth(windowWidth);
    }

    /** The focused card's top edge. */
    public static int cardTop(int windowHeight) {
        return Math.round(CARD_TOP * windowHeight);
    }

    /** One (1) or two (2) pages older: the card's rest edge, of W ([video]). */
    public static float olderEdge(int pages) {
        return OLDER_EDGES[pages - 1];
    }

    /** One (1) or two (2) pages older: the card's rest scale ([video]). */
    public static float olderScale(int pages) {
        return OLDER_SCALES[pages - 1];
    }

    /**
     * The card's depth in the deck: 0 in focus, 1 one card behind, negative for newer cards on
     * top. {@code offset} is the card's screen x offset from the focused card's place in the
     * natural layout (page scroll offset plus the card's own translations).
     */
    public float depth(float offset) {
        if (mPageSize <= 0f) return 0f;
        return (mOlderToLeft ? -offset : offset) / mPageSize;
    }

    /**
     * The card's older-side edge in the deck, of W from the older-side screen edge: the rest
     * places, linear in between, beyond two pages newer further off screen, deeper than the deck
     * at the deck's last place. Never past the next newer card's (on narrow screens).
     */
    public float edge(float depth) {
        if (depth <= -2f) return OFF_EDGE + (-2f - depth) * (OFF_EDGE - NEWER_EDGE);
        if (depth <= -1f) return lerp(NEWER_EDGE, OFF_EDGE, -1f - depth);
        if (depth <= 0f) return lerp(mFocusEdge, NEWER_EDGE, -depth);
        float one = Math.min(OLDER_EDGES[0], mFocusEdge);
        float two = Math.min(OLDER_EDGES[1], one);
        if (depth <= 1f) return lerp(mFocusEdge, one, depth);
        if (depth <= DECK_DEPTH) return lerp(one, two, depth - 1f);
        return two;
    }

    /** The card's scale relative to the focused card; the deck's last card's beyond it. */
    public static float scale(float depth) {
        if (depth <= 0f) return 1f;
        if (depth <= 1f) return lerp(1f, OLDER_SCALES[0], depth);
        if (depth <= DECK_DEPTH) return lerp(OLDER_SCALES[0], OLDER_SCALES[1], depth - 1f);
        return OLDER_SCALES[1];
    }

    /** Screen x translation from the card's natural place to its place in the deck. */
    public float translationX(float depth) {
        // In distances from the older-side screen edge: the deck's centre minus the natural one.
        float deck = edge(depth) * mWindowWidth + scale(depth) * mCardWidth / 2f;
        float natural = mCentre - depth * mPageSize;
        float older = deck - natural;
        return mOlderToLeft ? older : -older;
    }

    /**
     * How much of the card shows past the next newer card (the one a page newer, drawn on top),
     * in the card's own unscaled px from its older-side edge: its header is clipped there.
     * About a sixth of the width one page older, a sliver two pages older, nothing deeper.
     */
    public float headerStrip(float depth) {
        float strip = (edge(depth - 1f) - edge(depth)) * mWindowWidth / scale(depth);
        return Math.max(0f, strip);
    }

    /** The black scrim's alpha over the card's thumbnail: MiuiHome's curve by depth. */
    public static float dim(float depth) {
        if (depth <= 0f) return 0f;
        float t = Math.min(1f, depth / DIM_DEPTH);
        return MAX_DIM * (1f - (float) Math.pow(1f - t, DIM_EXPONENT));
    }

    private static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }
}
