package com.android.quickstep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The stack layout's rest places, read off the owner's stock MiuiHome recording (1200 x 2608). */
public class HgStackLayoutTest {
    private static final float EPS = 1e-4f;
    /** The recording's measuring error, in px of the width. */
    private static final float MEASURED = 0.01f * 1200;
    private static final int W = 1200;
    private static final int H = 2608;
    private static final float CARD_W = HgStackLayout.cardWidth(W);
    private static final float CARD_LEFT = HgStackLayout.cardLeft(W, true);
    private static final float PAGE = CARD_W + 40; // card width + page spacing

    private final HgStackLayout mLtr =
            new HgStackLayout(PAGE, W, CARD_LEFT, CARD_W, /* olderToLeft= */ true);
    private final HgStackLayout mRtl = new HgStackLayout(PAGE, W,
            HgStackLayout.cardLeft(W, false), CARD_W, /* olderToLeft= */ false);

    /** The focused card: 0.67 W wide, left edge 0.22 W, right 0.89 W; top 0.182 H, bottom 0.857. */
    @Test public void focusedCardIsTheTaskSize() {
        assertEquals(0.67f * W, CARD_W, MEASURED);
        assertEquals(0.22f * W, CARD_LEFT, MEASURED);
        assertEquals(0.89f * W, CARD_LEFT + CARD_W, MEASURED);
        float top = HgStackLayout.cardTop(H);
        assertEquals(0.182f * H, top, 0.01f * H);
        assertEquals(0.857f * H, top + HgStackLayout.cardHeight(W, H), 0.01f * H);
        // The window's shape.
        assertEquals((float) H / W, HgStackLayout.cardHeight(W, H) / CARD_W, 0.01f);
        // In focus: in its natural place, its size, undimmed.
        assertEquals(0f, mLtr.translationX(0f), EPS);
        assertEquals(1f, HgStackLayout.scale(0f), EPS);
        assertEquals(0f, HgStackLayout.dim(0f), EPS);
        // Right-to-left: mirrored.
        assertEquals(W - CARD_LEFT - CARD_W, HgStackLayout.cardLeft(W, false), 1f);
        assertEquals(0f, mRtl.translationX(0f), EPS);
    }

    @Test public void depthFromOffset() {
        assertEquals(0f, mLtr.depth(0f), EPS);
        assertEquals(1f, mLtr.depth(-PAGE), EPS);
        assertEquals(-1f, mLtr.depth(PAGE), EPS);
        assertEquals(2.5f, mLtr.depth(-2.5f * PAGE), EPS);
        assertEquals(1f, mRtl.depth(PAGE), EPS);
        assertEquals(0f, new HgStackLayout(0f, W, CARD_LEFT, CARD_W, true).depth(-100f), EPS);
    }

    /** One page newer: the same size, on top, left edge ~0.66 W, running off the right edge. */
    @Test public void oneNewerOnTopRunningOffTheEdge() {
        assertEquals(0.66f * W, left(mLtr, -1f), MEASURED);
        assertTrue(right(mLtr, -1f) > W);
        assertEquals(1f, HgStackLayout.scale(-1f), EPS);
        assertEquals(0f, HgStackLayout.dim(-1f), EPS);
        // Two and more pages newer: off the screen.
        assertTrue(left(mLtr, -2f) >= W - 0.5f);
        assertTrue(left(mLtr, -3f) > W);
    }

    /** One page older: left edge ~0.05 W (a ~0.17 W strip), 0.975 of the size, a little dim. */
    @Test public void oneOlderBehind() {
        assertEquals(0.05f * W, left(mLtr, 1f), MEASURED);
        assertEquals(0.17f * W, left(mLtr, 0f) - left(mLtr, 1f), MEASURED);
        assertEquals(0.975f, HgStackLayout.scale(1f), EPS);
        assertTrue(HgStackLayout.dim(1f) > 0f);
        assertTrue(HgStackLayout.dim(1f) < HgStackLayout.MAX_DIM);
    }

    /** Two pages older: left edge ~0 (a ~0.05 W sliver), 0.95 of the size, dimmer. */
    @Test public void twoOlderAtTheEdge() {
        assertEquals(0f, left(mLtr, 2f), MEASURED);
        assertEquals(0.05f * W, left(mLtr, 1f) - left(mLtr, 2f), MEASURED);
        assertEquals(0.95f, HgStackLayout.scale(2f), EPS);
        assertEquals(HgStackLayout.MAX_DIM, HgStackLayout.dim(2f), EPS);
        assertTrue(HgStackLayout.dim(2f) > HgStackLayout.dim(1f));
    }

    /** Deeper cards lie exactly behind the two-older card: same place, size and dim. */
    @Test public void deeperCardsHideBehindTheDeck() {
        int last = HgStackLayout.DECK_DEPTH;
        for (float depth : new float[] {last + 0.25f, last + 1f, last + 4f}) {
            assertEquals(left(mLtr, last), left(mLtr, depth), 0.01f);
            assertEquals(right(mLtr, last), right(mLtr, depth), 0.01f);
            assertEquals(HgStackLayout.scale(last), HgStackLayout.scale(depth), EPS);
            assertEquals(HgStackLayout.dim(last), HgStackLayout.dim(depth), EPS);
        }
        // Its header is entirely covered once the card in front of it is at the deck's end too.
        assertEquals(0f, mLtr.headerStrip(last + 1f), 0.01f);
        assertEquals(HgStackLayout.DECK_DEPTH + 1, HgStackLayout.VISIBLE_BEHIND);
    }

    /**
     * While scrolling everything moves linearly between the rest places, continuously: a newer
     * card slides from 0.66 W to 0.22 W as it comes into focus, the focused one to 0.05 W.
     */
    @Test public void scrollingInterpolatesBetweenRestPlaces() {
        assertEquals((0.66f * W + CARD_LEFT) / 2f, left(mLtr, -0.5f), MEASURED);
        assertEquals((CARD_LEFT + left(mLtr, 1f)) / 2f, left(mLtr, 0.5f), 0.5f);
        assertEquals((1f + HgStackLayout.scale(1f)) / 2f, HgStackLayout.scale(0.5f), EPS);
        float previousLeft = Float.MAX_VALUE;
        float previousScale = 2f;
        for (int i = -300; i <= 400; i++) {
            float depth = i / 100f;
            float l = left(mLtr, depth);
            float s = HgStackLayout.scale(depth);
            // Deeper is never further right, never bigger.
            assertTrue("depth " + depth, l <= previousLeft + 0.01f);
            assertTrue("depth " + depth, s <= previousScale + EPS);
            // Continuous: a hundredth of a page moves a card at most a few px.
            if (previousLeft != Float.MAX_VALUE) {
                assertTrue("depth " + depth, previousLeft - l < 0.01f * W);
            }
            previousLeft = l;
            previousScale = s;
        }
    }

    /**
     * Each card's header shows only within its visible strip, up to the newer card's edge: the
     * one-older card's ~0.17 W (its icon; the name is cut), the two-older's sliver.
     */
    @Test public void headerShowsWithinTheVisibleStrip() {
        assertEquals((left(mLtr, 0f) - left(mLtr, 1f)) / HgStackLayout.scale(1f),
                mLtr.headerStrip(1f), 0.01f);
        assertEquals((left(mLtr, 1f) - left(mLtr, 2f)) / HgStackLayout.scale(2f),
                mLtr.headerStrip(2f), 0.01f);
        // The focused card's header up to the newer card on top of it.
        assertEquals(left(mLtr, -1f) - left(mLtr, 0f), mLtr.headerStrip(0f), 0.01f);
        // Mid-scroll too, and never negative.
        for (int i = -200; i <= 400; i++) {
            float depth = i / 100f;
            float strip = mLtr.headerStrip(depth);
            assertTrue(strip >= 0f);
            assertEquals((left(mLtr, depth - 1f) - left(mLtr, depth))
                    / HgStackLayout.scale(depth), strip, 0.05f);
        }
        assertEquals(mLtr.headerStrip(1.5f), mRtl.headerStrip(1.5f), EPS);
    }

    /** Right-to-left: everything mirrored. */
    @Test public void rightToLeftMirrors() {
        for (int i = -250; i <= 350; i += 5) {
            float depth = i / 100f;
            assertEquals(W - left(mLtr, depth), right(mRtl, depth), 0.05f);
        }
        assertEquals(-mRtl.translationX(1f), mLtr.translationX(1f), 0.01f);
    }

    /** A screen too narrow for the older places keeps the deck in order. */
    @Test public void narrowFocusEdgeKeepsTheOrder() {
        HgStackLayout tight = new HgStackLayout(140, W, 20, 100, true);
        assertTrue(tight.edge(1f) <= tight.edge(0f));
        assertTrue(tight.edge(2f) <= tight.edge(1f));
    }

    @Test public void dimEasesOutToTheMaximum() {
        assertEquals(0f, HgStackLayout.dim(0f), EPS);
        float previous = 0f;
        for (float depth = 0.25f; depth <= 4f; depth += 0.25f) {
            float dim = HgStackLayout.dim(depth);
            assertTrue(dim >= previous);
            assertTrue(dim <= HgStackLayout.MAX_DIM + EPS);
            previous = dim;
        }
        assertEquals(HgStackLayout.MAX_DIM, HgStackLayout.dim(HgStackLayout.DIM_DEPTH), EPS);
        assertEquals(HgStackLayout.MAX_DIM, HgStackLayout.dim(HgStackLayout.DECK_DEPTH), EPS);
        assertEquals(HgStackLayout.MAX_DIM, HgStackLayout.dim(10f), EPS);
        // Ease-out: the first card behind already has most of the dim.
        assertTrue(HgStackLayout.dim(1f) > HgStackLayout.MAX_DIM / 2f);
    }

    /** The card's left edge on screen, its centre moved by the deck and scaled about it. */
    private static float left(HgStackLayout l, float depth) {
        return centre(l, depth) - HgStackLayout.scale(depth) * CARD_W / 2f;
    }

    private static float right(HgStackLayout l, float depth) {
        return centre(l, depth) + HgStackLayout.scale(depth) * CARD_W / 2f;
    }

    private static float centre(HgStackLayout l, float depth) {
        boolean olderToLeft = l.depth(-PAGE) > 0f;
        float cardLeft = HgStackLayout.cardLeft(W, olderToLeft);
        // The natural place: depth pages toward the older side.
        float natural = (olderToLeft ? -depth : depth) * PAGE;
        return cardLeft + CARD_W / 2f + natural + l.translationX(depth);
    }
}
