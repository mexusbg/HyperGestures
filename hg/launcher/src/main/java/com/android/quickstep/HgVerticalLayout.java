// HyperGestures: MIUI's "vertical" recents layout (Settings.Global task_stack_view_layout_style
// = 0, MiuiHome's default; TaskStackViewsAlgorithmVertical + TaskVerticalLayoutConfig,
// docs/research/2026-10-09-miuihome-recents-features.md section 6c), as pure maths so it is
// testable.
//
// Two columns of cards that scroll up and down, the newest top-left, in rows (0 top-left, 1
// top-right, 2 second row left, ...). RecentsView keeps its horizontal PagedView underneath, as for
// the stack (HgStackLayout): every card keeps its natural page in the strip, and this class maps a
// card's place in the strip to its place in the grid. The strip's scroll is the grid's vertical
// scroll, pixel for pixel (RecentsView turns vertical drags into strip drags, see scrollTranspose),
// so flings, the scroll bounds and the swipe up's attach keep working. A card's slot is its page
// plus the translations the gesture animations give it. After a dismiss AOSP's reflow moves each
// older card one page toward the newer end; here that page is the way from the card's slot to the
// previous one (a right-column card left along its row, a left-column card up to the right end of
// the row above), and the removal's progress eases the scroll into the shorter range and a card
// left alone into the centre, so nothing jumps once the dismissed card is removed. The task size
// is slot 0's thumbnail at the top of the grid, where the swipe up lands.
//
// Sizes follow the horizontal layout (owner, 0.3.9.28: "Vertical: bigger cards", "Look like
// horizontal"): two columns as wide as fit with horizontal's gap g (GAP of the width) outside and
// between them, the window's shape (as the horizontal card), the same header (HgTaskHeader) and
// corners. Rows are a header plus g apart; the first row starts g below the status bar (the
// memory line is at the bottom, HgRamIndicator). Cards scrolled above that edge fade (headerAlpha,
// cardAlpha) and RecentsView clips the grid at the status bar, so nothing draws under the clock.
//
// [V] MiuiHome's constants (HyperOS 3 smali, portrait phone); [G] our choices.
package com.android.quickstep;

public final class HgVerticalLayout {
    /** [V] The horizontal layout's gap between cards, as a fraction of the window width. */
    public static final float GAP = 0.026f;
    /** [V] Bottom padding, in card heights, with more than FEW tasks (none otherwise). */
    public static final float BOTTOM_RATIO = 0.4f;
    /** [V] The task count up to which there is no bottom padding. */
    public static final int FEW = 2;
    /** [V] Cards this far (a fraction of the window height) off screen still load (preload). */
    public static final float PRELOAD = 0.2f;
    /** [V] A card scrolled a whole card height above the top edge keeps 1 - 1/3 of its alpha. */
    public static final float FADE_DEPTH = 3f;

    private final int mWindowWidth;
    private final int mWindowHeight;
    private final int mTopInset;
    private final int mCardWidth;
    private final int mThumbHeight;
    private final float mHeader;
    private final float mCardHeight;
    private final float mRowPitch;
    private final int mLeft;
    private final float mColumnStep;
    private final int mTaskTop;
    private final float mPageSize;
    private final boolean mOlderToLeft;
    private int mCount;
    // The progress (0 to 1) of a dismissed card's removal from the mCount cards (RecentsView's
    // hgTrackRemoval): blends in the layout of the cards that stay (a lone card's centring).
    private float mRemoval;

    /**
     * @param windowWidth the recents window's width
     * @param windowHeight the recents window's height
     * @param topInset the status bar's height (the top inset)
     * @param header the header's height in px (HgTaskHeader.heightPx)
     * @param pageSize the distance between two cards' natural places in the strip
     * @param olderToLeft whether older cards are on the left of the strip (RecentsView's isRtl(),
     *     true for left-to-right languages)
     */
    public HgVerticalLayout(int windowWidth, int windowHeight, int topInset, float header,
            float pageSize, boolean olderToLeft) {
        mWindowWidth = windowWidth;
        mWindowHeight = windowHeight;
        mTopInset = topInset;
        mCardWidth = cardWidth(windowWidth);
        mThumbHeight = thumbnailHeight(windowWidth, windowHeight);
        mHeader = header;
        float gap = GAP * windowWidth;
        mCardHeight = mThumbHeight + header;
        mRowPitch = mCardHeight + gap;
        mLeft = cardLeft(windowWidth);
        mColumnStep = mCardWidth + gap;
        mTaskTop = thumbnailTop(windowWidth, topInset, header);
        mPageSize = pageSize;
        mOlderToLeft = olderToLeft;
    }

    /** A card's (and the task size's) width: two columns and three gaps across the window. */
    public static int cardWidth(int windowWidth) {
        return Math.round((windowWidth - 3f * GAP * windowWidth) / 2f);
    }

    /** A card's thumbnail height: the window's shape, as the horizontal layout's card. */
    public static int thumbnailHeight(int windowWidth, int windowHeight) {
        return windowWidth <= 0 ? 0
                : Math.round(cardWidth(windowWidth) * (float) windowHeight / windowWidth);
    }

    /** The left column's left edge: one gap in. */
    public static int cardLeft(int windowWidth) {
        return Math.round(GAP * windowWidth);
    }

    /**
     * The first row's thumbnail top (the task size's top): a gap below the status bar, then the
     * header.
     */
    public static int thumbnailTop(int windowWidth, int topInset, float header) {
        return Math.round(topInset + GAP * windowWidth + header);
    }

    /** The number of cards, for the paddings, the centring of a lone card and the scroll range. */
    public void setTaskCount(int count) {
        mCount = count;
    }

    /**
     * The progress of a dismissed card's removal, 0 (none pending) to 1 (about to be removed):
     * the cards that stay ease into their layout without it (a lone card's centring) meanwhile.
     */
    public void setRemoval(float progress) {
        mRemoval = Math.max(0f, Math.min(1f, progress));
    }

    public float getRemoval() {
        return mRemoval;
    }

    /**
     * How far a card is centred, as a lone card is ([V]): 1 for the only card; while the other of
     * two is being removed, the removal's progress for the one that stays. None for a card that
     * is {@code leaving} (being dismissed), even when it is the last one: it flies out from where
     * it was (TaskView keeps the centring it had as it started to leave).
     */
    public float centring(boolean leaving) {
        if (leaving) return 0f;
        if (mCount == 1) return 1f;
        return mCount == 2 ? mRemoval : 0f;
    }

    /**
     * The reflow's progress (slots toward the previous slot) for a strip translation: AOSP's
     * reflow after a dismiss moves each older card one page toward the newer end.
     */
    public float reflow(float stripTranslation) {
        return -depth(stripTranslation);
    }

    /**
     * The dismissed card's travel after which the reflow starts (signed as {@code finalPosition},
     * the dismiss's end): AOSP's upward dismiss starts it once the card has moved its own height,
     * which a sideways dismiss (about half the window) never reaches, so it would never start. A
     * card that leaves sideways has left its slot once it has moved its own width
     * ({@code cardWidth}); never past the dismiss's end.
     */
    public static float reflowThreshold(float cardWidth, float finalPosition) {
        return Math.copySign(Math.min(Math.abs(cardWidth), Math.abs(finalPosition)), finalPosition);
    }

    /**
     * The way (px: {@code out[0]} x, {@code out[1]} y) from where a reflowing card is drawn,
     * {@code reflow} of the way from {@code slot} to the previous slot and {@code centring} of the
     * way to a lone card's place, to where the reflow leaves it: {@code end} of the way (1 after
     * a dismiss), centred if it is the one of two that stays. For the dismiss swipe's hit test,
     * which AOSP widens sideways by the reflow's page (its strip move); here it follows the slot
     * path instead.
     */
    public void reflowRemaining(float slot, float reflow, float end, float centring, float[] out) {
        float endCentring = mCount == 2 ? 1f : centring;
        out[0] = slotX(slot, end, endCentring) - slotX(slot, reflow, centring);
        out[1] = slotY(slot, end) - slotY(slot, reflow);
    }

    /**
     * Whether ({@code x}, {@code y}) hits a reflowing card: on the rect it is drawn at
     * ({@code left} to {@code right}, {@code top} to {@code bottom}; right and bottom excluded, as
     * android.graphics.Rect.contains), or on that rect moved by ({@code dx}, {@code dy}) to where
     * the reflow leaves it (reflowRemaining). Never the strip's sideways page.
     */
    public static boolean reflowHit(float left, float top, float right, float bottom, float dx,
            float dy, float x, float y) {
        return contains(left, top, right, bottom, x, y)
                || contains(left + dx, top + dy, right + dx, bottom + dy, x, y);
    }

    private static boolean contains(float left, float top, float right, float bottom, float x,
            float y) {
        return left < right && top < bottom && x >= left && x < right && y >= top && y < bottom;
    }

    /**
     * The card's depth in the strip: pages from the scroll position, positive for older cards.
     * {@code offset} is the card's screen x offset from the task size's place in the strip (page
     * scroll offset plus the card's own translations), as for HgStackLayout.
     */
    public float depth(float offset) {
        if (mPageSize <= 0f) return 0f;
        return (mOlderToLeft ? -offset : offset) / mPageSize;
    }

    /**
     * The card's slot in the grid (0 top-left, 1 top-right, ...; fractional while it moves) for a
     * {@code depth} and the grid's {@code scrollY} (the strip's scroll from the newest card, px).
     */
    public float slot(float depth, float scrollY) {
        return mPageSize <= 0f ? depth : depth + scrollY / mPageSize;
    }

    /** A slot's x, from the task size's left edge; a lone card is centred ([V]). */
    public float slotX(float slot) {
        return slotX(slot, 0f, centring(false));
    }

    /**
     * The x of a card {@code reflow} of the way from {@code slot} to the previous slot, on the
     * straight line between them (beyond them for a spring's overshoot, never round a corner),
     * and {@code centring} of the way to a lone card's centred place.
     */
    public float slotX(float slot, float reflow, float centring) {
        float from = gridX(slot);
        float x = from + (gridX(slot - 1f) - from) * reflow;
        return x + ((mWindowWidth - mCardWidth) / 2f - mLeft - x) * centring;
    }

    /** As slotX, the y of a card {@code reflow} of the way from {@code slot} to the previous. */
    public float slotY(float slot, float reflow) {
        float from = slotY(slot);
        return from + (slotY(slot - 1f) - from) * reflow;
    }

    private float gridX(float slot) {
        int lo = (int) Math.floor(slot);
        float f = slot - lo;
        return columnX(lo) * (1f - f) + columnX(lo + 1) * f;
    }

    /** A slot's y, from the task size's top edge, before scrolling. */
    public float slotY(float slot) {
        int lo = (int) Math.floor(slot);
        float f = slot - lo;
        return rowY(lo) * (1f - f) + rowY(lo + 1) * f;
    }

    private float columnX(int slot) {
        return Math.floorMod(slot, 2) * mColumnStep;
    }

    private float rowY(int slot) {
        return Math.floorDiv(slot, 2) * mRowPitch;
    }


    /** Screen x translation from the card's natural place in the strip to its slot. */
    public float translationX(float depth, float scrollY) {
        float natural = (mOlderToLeft ? -depth : depth) * mPageSize;
        return slotX(slot(depth, scrollY)) - natural;
    }

    /** Screen y translation from the card's natural place (the task size's top) to its slot. */
    public float translationY(float depth, float scrollY) {
        return translationY(depth, scrollY, scrollY);
    }

    /**
     * As translationY, but the grid drawn at {@code shownScrollY}: the swipe up freezes the grid
     * while the finger's sideways travel scrolls the strip (each card keeps its slot).
     */
    public float translationY(float depth, float scrollY, float shownScrollY) {
        return slotY(slot(depth, scrollY)) - shownScrollY;
    }

    /**
     * Screen x translation from the card's place in the strip to where it is drawn: {@code depth}
     * without the reflow (its page and the gesture moves), {@code reflow} of the way to the
     * previous slot (slotX), {@code centring} of the way to a lone card's place. Its place in the
     * strip includes the reflow's own page move ({@code reflow} pages toward the newer end).
     */
    public float translationX(float depth, float scrollY, float reflow, float centring) {
        float strip = depth - reflow;
        float natural = (mOlderToLeft ? -strip : strip) * mPageSize;
        return slotX(slot(depth, scrollY), reflow, centring) - natural;
    }

    /** As translationX, the y translation, the grid drawn at {@code shownScrollY}. */
    public float translationY(float depth, float scrollY, float shownScrollY, float reflow) {
        return slotY(slot(depth, scrollY), reflow) - shownScrollY;
    }

    /**
     * How far the grid scrolls: until the last card's bottom plus the bottom padding meets the
     * window's bottom ([V] calculateMaxScrollP), 0 when everything fits.
     */
    public int maxScrollY() {
        return maxScrollY(mCount);
    }

    /** As maxScrollY, for {@code count} cards. */
    public int maxScrollY(int count) {
        if (count <= 0) return 0;
        int lastRow = (count - 1) / 2;
        float bottom = mTaskTop + lastRow * mRowPitch + mThumbHeight
                + (count > FEW ? BOTTOM_RATIO * mCardHeight : 0f);
        return Math.max(0, Math.round(bottom - mWindowHeight));
    }

    /**
     * The grid's scroll once a dismissed card is removed and {@code remaining} cards stay: where
     * it is, unless the shorter grid (a row fewer when the last row empties) ends above it.
     */
    public float scrollAfterRemoval(float scrollY, int remaining) {
        return Math.min(scrollY, maxScrollY(remaining));
    }

    /** The top of a slot's card (its header) on screen. */
    public float cardTop(float slot, float scrollY) {
        return mTaskTop - mHeader + slotY(slot) - scrollY;
    }

    /** The bottom of a slot's card (its thumbnail) on screen. */
    public float cardBottom(float slot, float scrollY) {
        return mTaskTop + slotY(slot) + mThumbHeight - scrollY;
    }

    /**
     * Whether a slot's card reaches the window below the status bar (RecentsView clips the grid
     * there), or comes within {@code margin} of it.
     */
    public boolean isVisible(float slot, float scrollY, float margin) {
        return cardBottom(slot, scrollY) > mTopInset - margin
                && cardTop(slot, scrollY) < mWindowHeight + margin;
    }

    /** Whether a slot's card is entirely on screen, below the status bar. */
    public boolean isFullyVisible(float slot, float scrollY) {
        return cardTop(slot, scrollY) >= mTopInset && cardBottom(slot, scrollY) <= mWindowHeight;
    }

    /** The top edge cards scroll past and fade above: the first row's top at rest. */
    public float topEdge() {
        return mTaskTop - mHeader;
    }

    /** How far a slot's card top (its header's) is above the top edge, px; 0 below it. */
    private float aboveTop(float slot, float scrollY) {
        return Math.max(0f, topEdge() - cardTop(slot, scrollY));
    }

    /**
     * A slot's header alpha: gone once it has scrolled its own height above the top edge, so no
     * readable header reaches the clock (RecentsView also clips the grid at the status bar).
     */
    public float headerAlpha(float slot, float scrollY) {
        if (mHeader <= 0f) return 1f;
        return 1f - Math.min(1f, aboveTop(slot, scrollY) / mHeader);
    }

    /**
     * A slot's card alpha ([V] MiuiHome's 1 - accel(p) / 3, p = how far above the top edge in
     * card heights, accel(p) = p squared): 1 below the top edge, 2/3 a card height above it.
     */
    public float cardAlpha(float slot, float scrollY) {
        if (mCardHeight <= 0f) return 1f;
        float p = Math.min(1f, aboveTop(slot, scrollY) / mCardHeight);
        return 1f - p * p / FADE_DEPTH;
    }

    /** The preload margin ([V] getPreloadHeight: a fifth of the window). */
    public float preloadMargin() {
        return PRELOAD * mWindowHeight;
    }

    /**
     * The side a slot's card leaves by when it is dismissed without a swipe (Clear all, the hold
     * button): -1 left, 1 right; by which half of the window its centre is in ([V]
     * dismissTaskViewAnim).
     */
    public int dismissSide(float slot) {
        float centre = mLeft + slotX(slot) + mCardWidth / 2f;
        return centre <= mWindowWidth / 2f ? -1 : 1;
    }

    /**
     * The actions row's (trash button's) bottom margin: centred in the bottom padding the cards
     * scroll clear of ([G]), at least {@code minMargin}.
     */
    public int actionsBottomMargin(int actionsHeight, int minMargin) {
        return Math.max(minMargin,
                Math.round((BOTTOM_RATIO * mCardHeight - actionsHeight) / 2f));
    }

    /**
     * Fills {@code out} (a row-major 3x3 matrix, android.graphics.Matrix.setValues) with the map
     * that turns a touch's travel from ({@code x0}, {@code y0}) sideways: x' = x0 + xPerY (y - y0),
     * y' = y0 + yPerX (x - x0). The touch-down point stays where it is, so hit tests still match.
     */
    public static void transpose(float x0, float y0, float xPerY, float yPerX, float[] out) {
        out[0] = 0f;
        out[1] = xPerY;
        out[2] = x0 - xPerY * y0;
        out[3] = yPerX;
        out[4] = 0f;
        out[5] = y0 - yPerX * x0;
        out[6] = 0f;
        out[7] = 0f;
        out[8] = 1f;
    }

    /**
     * The strip x travel per px of finger y travel that scrolls the grid with the finger: older
     * cards are below, so an upward drag must look like a drag toward the strip's newer end. The
     * same sign maps the strip's overscroll shift to the grid's.
     */
    public static float scrollSign(boolean olderToLeft) {
        return olderToLeft ? -1f : 1f;
    }

    /**
     * The dismiss swipe's map: the swipe detector watches y and calls up its dismiss direction,
     * so a sideways swipe toward {@code side} (-1 left, 1 right) must look like an upward one.
     * Fills {@code out} as transpose.
     */
    public static void dismissTranspose(float x0, float y0, int side, float[] out) {
        transpose(x0, y0, 1f, -side, out);
    }

    /** The task size's left edge in this window. */
    public int getLeft() {
        return mLeft;
    }

    /** The task size's top edge in this window. */
    public int getTaskTop() {
        return mTaskTop;
    }

    /** The status bar's height this layout keeps the grid below. */
    public int getTopInset() {
        return mTopInset;
    }
}
