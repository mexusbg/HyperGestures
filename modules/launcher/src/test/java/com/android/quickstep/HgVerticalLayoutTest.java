package com.android.quickstep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HgVerticalLayoutTest {
    private static final float EPS = 1e-3f;
    private static final int W = 1080;
    private static final int H = 2400;
    private static final int TOP = 80; // status bar
    private static final float HEADER = 132f; // 36 dp chip row + 8 dp gap at density 3
    private static final float GAP = 0.026f * W; // the horizontal layout's gap, 28.08
    private static final int CARD_W = 498; // (W - 3 gaps) / 2
    private static final int THUMB_H = 1107; // CARD_W x H / W: the window's shape
    private static final float PAGE = CARD_W + 48; // card width + page spacing
    private static final float CARD_H = THUMB_H + HEADER;
    private static final float ROW = CARD_H + GAP;
    private static final float COLUMN = CARD_W + GAP;
    private static final int TASK_TOP = 240; // status bar + gap + header

    private static HgVerticalLayout layout(int count, boolean olderToLeft) {
        HgVerticalLayout l = new HgVerticalLayout(W, H, TOP, HEADER, PAGE, olderToLeft);
        l.setTaskCount(count);
        return l;
    }

    /** The task size: slot 0's thumbnail, as wide as two columns and three gaps allow. */
    @Test public void taskSize() {
        assertEquals(CARD_W, HgVerticalLayout.cardWidth(W));
        assertEquals(THUMB_H, HgVerticalLayout.thumbnailHeight(W, H));
        assertEquals(28, HgVerticalLayout.cardLeft(W));
        // About 46% of the width, the window's aspect ratio (as the horizontal card).
        assertEquals(0.461f, CARD_W / (float) W, 0.001f);
        assertEquals(H / (float) W, THUMB_H / (float) CARD_W, 0.002f);
        // A gap below the status bar, then the header.
        assertEquals(TASK_TOP, HgVerticalLayout.thumbnailTop(W, TOP, HEADER));
        HgVerticalLayout l = layout(6, true);
        assertEquals(TASK_TOP, l.getTaskTop());
        assertEquals(28, l.getLeft());
        assertEquals(TOP, l.getTopInset());
    }

    /** Two columns, three equal gaps, rows one card (header included) plus one gap apart. */
    @Test public void slotsFillRowByRow() {
        HgVerticalLayout l = layout(6, true);
        assertEquals(0f, l.slotX(0), EPS);
        assertEquals(COLUMN, l.slotX(1), EPS);
        assertEquals(0f, l.slotX(2), EPS);
        assertEquals(COLUMN, l.slotX(5), EPS);
        assertEquals(0f, l.slotY(0), EPS);
        assertEquals(0f, l.slotY(1), EPS);
        assertEquals(ROW, l.slotY(2), EPS);
        assertEquals(2 * ROW, l.slotY(5), EPS);
        // Right margin = middle gap = left margin (to rounding).
        float right = W - (28 + COLUMN + CARD_W);
        assertEquals(GAP, right, 1f);
        assertEquals(GAP, COLUMN - CARD_W, 1f);
        // Between a row's thumbnail and the next row's: the next header and a gap.
        assertEquals(HEADER + GAP, l.slotY(2) - THUMB_H, EPS);
    }

    /** A card at rest sits in its slot whatever the scroll; the grid scrolls with the strip. */
    @Test public void stripMapsToGrid() {
        for (boolean olderToLeft : new boolean[] {true, false}) {
            HgVerticalLayout l = layout(7, olderToLeft);
            for (float scrollY : new float[] {0f, 300f, 1500f}) {
                for (int i = 0; i < 7; i++) {
                    // The card's natural screen offset in the strip from the task size's place.
                    float offset = (olderToLeft ? -1 : 1) * (i * PAGE - scrollY);
                    float depth = l.depth(offset);
                    assertEquals(i, l.slot(depth, scrollY), EPS);
                    assertEquals(l.slotX(i), offset + l.translationX(depth, scrollY), 0.01f);
                    assertEquals(l.slotY(i) - scrollY, l.translationY(depth, scrollY), 0.01f);
                }
            }
        }
    }

    /** Frozen during the swipe up: a strip scroll keeps each card in its slot, on screen. */
    @Test public void frozenGridDoesNotMove() {
        HgVerticalLayout l = layout(6, true);
        for (int i = 0; i < 6; i++) {
            for (float scrollY : new float[] {0f, 250f, 700f}) {
                float offset = -(i * PAGE - scrollY);
                float depth = l.depth(offset);
                assertEquals(l.slotX(i), offset + l.translationX(depth, scrollY), 0.01f);
                assertEquals(l.slotY(i), l.translationY(depth, scrollY, 0f), 0.01f);
            }
        }
    }

    /** A reflowing card glides between slots (here right column, row 0 -> left column, row 1). */
    @Test public void fractionalSlotsInterpolate() {
        HgVerticalLayout l = layout(6, true);
        assertEquals(COLUMN / 2f, l.slotX(1.5f), EPS);
        assertEquals(ROW / 2f, l.slotY(1.5f), EPS);
        assertEquals(COLUMN / 4f, l.slotX(0.25f), EPS);
        assertEquals(0f, l.slotY(0.25f), EPS);
    }

    /**
     * Dismissing card i: AOSP's reflow moves every older card j one page toward the newer end,
     * which lands it on slot j - 1, in both strip directions; the newer cards stay.
     */
    @Test public void reflowMovesEachLaterCardBackOneSlot() {
        for (boolean olderToLeft : new boolean[] {true, false}) {
            HgVerticalLayout l = layout(7, olderToLeft);
            // AOSP's reflow gap: a page toward the newer end (positive when older is left).
            float gap = olderToLeft ? PAGE : -PAGE;
            assertEquals(1f, l.reflow(gap), EPS);
            assertEquals(0f, l.reflow(0f), EPS);
            for (int dismissed = 0; dismissed < 7; dismissed++) {
                for (int j = 0; j < 7; j++) {
                    if (j == dismissed) continue;
                    float reflow = j > dismissed ? l.reflow(gap) : 0f;
                    float depth = j; // scrolled to the top: depth = slot
                    // The card's strip place includes the reflow's page move.
                    float strip = (olderToLeft ? -1 : 1) * (depth - reflow) * PAGE;
                    int expected = j > dismissed ? j - 1 : j;
                    assertEquals(l.slotX(expected),
                            strip + l.translationX(depth, 0f, reflow, 0f), 0.01f);
                    assertEquals(l.slotY(expected),
                            l.translationY(depth, 0f, 0f, reflow), 0.01f);
                }
            }
        }
    }

    /** No reflow: the four-argument translations are the plain ones. */
    @Test public void noReflowIsThePlainLayout() {
        HgVerticalLayout l = layout(6, true);
        for (float depth = -0.5f; depth < 6f; depth += 0.25f) {
            assertEquals(l.translationX(depth, 300f), l.translationX(depth, 300f, 0f, 0f), 0.01f);
            assertEquals(l.translationY(depth, 300f, 120f),
                    l.translationY(depth, 300f, 120f, 0f), 0.01f);
        }
    }

    /** A right-column card moves left along its own row. */
    @Test public void rightColumnCardMovesLeftInItsRow() {
        HgVerticalLayout l = layout(6, true);
        assertEquals(COLUMN, l.slotX(3, 0f, 0f), EPS);
        assertEquals(COLUMN / 2f, l.slotX(3, 0.5f, 0f), EPS);
        assertEquals(0f, l.slotX(3, 1f, 0f), EPS);
        assertEquals(ROW, l.slotY(3, 0f), EPS);
        assertEquals(ROW, l.slotY(3, 0.5f), EPS);
        assertEquals(ROW, l.slotY(3, 1f), EPS);
    }

    /** A left-column card moves up and right, to the right column of the row above. */
    @Test public void leftColumnCardMovesUpRight() {
        HgVerticalLayout l = layout(6, true);
        assertEquals(0f, l.slotX(2, 0f, 0f), EPS);
        assertEquals(ROW, l.slotY(2, 0f), EPS);
        assertEquals(COLUMN / 2f, l.slotX(2, 0.5f, 0f), EPS);
        assertEquals(ROW / 2f, l.slotY(2, 0.5f), EPS);
        assertEquals(COLUMN, l.slotX(2, 1f, 0f), EPS);
        assertEquals(0f, l.slotY(2, 1f), EPS);
        // Within the move the path is the plain fractional slot's.
        assertEquals(l.slotX(1.25f), l.slotX(2, 0.75f, 0f), EPS);
        assertEquals(l.slotY(1.25f), l.slotY(2, 0.75f), EPS);
    }

    /** A spring's overshoot continues along the card's own line, never round a row's end. */
    @Test public void overshootStaysOnTheLine() {
        HgVerticalLayout l = layout(6, true);
        // Right column -> left: a little further left, same row (not up toward slot 1).
        assertEquals(-0.1f * COLUMN, l.slotX(3, 1.1f, 0f), 0.01f);
        assertEquals(ROW, l.slotY(3, 1.1f), EPS);
        // Left column -> up right: a little further up and right (not back left).
        assertEquals(1.1f * COLUMN, l.slotX(2, 1.1f, 0f), 0.01f);
        assertEquals(-0.1f * ROW, l.slotY(2, 1.1f), 0.01f);
    }

    /**
     * The dismiss swipe's hit test follows a reflowing card's slot path: the way left to the
     * previous slot is up and right from the left column, left along the row from the right.
     */
    @Test public void reflowRemainingFollowsTheSlotPath() {
        HgVerticalLayout l = layout(6, true);
        float[] way = new float[2];
        l.reflowRemaining(2f, 0f, 1f, 0f, way); // row 1 left -> row 0 right
        assertEquals(COLUMN, way[0], EPS);
        assertEquals(-ROW, way[1], EPS);
        l.reflowRemaining(2f, 0.25f, 1f, 0f, way);
        assertEquals(0.75f * COLUMN, way[0], EPS);
        assertEquals(-0.75f * ROW, way[1], EPS);
        l.reflowRemaining(3f, 0.5f, 1f, 0f, way); // row 1 right -> row 1 left
        assertEquals(-COLUMN / 2f, way[0], EPS);
        assertEquals(0f, way[1], EPS);
        l.reflowRemaining(3f, 1f, 1f, 0f, way); // arrived
        assertEquals(0f, way[0], EPS);
        assertEquals(0f, way[1], EPS);
        l.reflowRemaining(2f, 1.1f, 1f, 0f, way); // a spring's overshoot comes back on its line
        assertEquals(-0.1f * COLUMN, way[0], EPS);
        assertEquals(0.1f * ROW, way[1], EPS);
        // Of two cards, the one that stays ends at the lone card's centred place.
        float centred = (W - CARD_W) / 2f - 28;
        HgVerticalLayout two = layout(2, true);
        two.setRemoval(0.5f);
        two.reflowRemaining(1f, 0.5f, 1f, two.centring(false), way);
        assertEquals(centred - two.slotX(1f, 0.5f, 0.5f), way[0], EPS);
        assertEquals(0f, way[1], EPS);
    }

    /**
     * A swipe hits a reflowing card on the rect it is drawn at or where its reflow leaves it,
     * never a page sideways (AOSP's strip widening, which reaches a neighbour in the grid).
     */
    @Test public void reflowHitTakesTheDrawnRectAndItsEnd() {
        HgVerticalLayout l = layout(6, true);
        float[] way = new float[2];
        // Card in slot 2 (row 1, left), a quarter of the way up to slot 1 (row 0, right).
        l.reflowRemaining(2f, 0.25f, 1f, 0f, way);
        float left = 28 + 0.25f * COLUMN;
        float top = TASK_TOP + 0.75f * ROW;
        float right = left + CARD_W;
        float bottom = top + THUMB_H;
        // On the card as drawn.
        assertTrue(HgVerticalLayout.reflowHit(left, top, right, bottom, way[0], way[1],
                left + 10, top + 10));
        // In the closing gap it is heading for (slot 1, row 0 right).
        float slot1Left = 28 + COLUMN;
        assertTrue(HgVerticalLayout.reflowHit(left, top, right, bottom, way[0], way[1],
                slot1Left + 10, TASK_TOP + 10));
        // A page to the right in its own row (slot 3's card) or to the left: not this card.
        assertFalse(HgVerticalLayout.reflowHit(left, top, right, bottom, way[0], way[1],
                right + PAGE / 2f, bottom - 10));
        assertFalse(HgVerticalLayout.reflowHit(left, top, right, bottom, way[0], way[1],
                left - 10, bottom - 10));
        // Right and bottom edges excluded, as Rect.contains; an empty rect hits nothing.
        assertFalse(HgVerticalLayout.reflowHit(0, 0, 100, 100, 0, 0, 100, 50));
        assertFalse(HgVerticalLayout.reflowHit(0, 0, 100, 100, 0, 0, 50, 100));
        assertTrue(HgVerticalLayout.reflowHit(0, 0, 100, 100, 0, 0, 0, 0));
        assertFalse(HgVerticalLayout.reflowHit(0, 0, 0, 100, 0, 0, 0, 50));
    }

    /** The reflow starts once the card has moved its own width, never past the dismiss's end. */
    @Test public void reflowThresholdIsReached() {
        assertEquals(-CARD_W, HgVerticalLayout.reflowThreshold(CARD_W, -800f), EPS);
        assertEquals(CARD_W, HgVerticalLayout.reflowThreshold(CARD_W, 800f), EPS);
        assertEquals(-400f, HgVerticalLayout.reflowThreshold(CARD_W, -400f), EPS);
        // Each column's sideways dismiss: to its own edge, or across to the far one.
        float leftOut = HgVerticalLayout.cardLeft(W) + CARD_W; // left card, to the left
        float rightOut = W - (HgVerticalLayout.cardLeft(W) + COLUMN); // right card, right
        for (float length : new float[] {leftOut, rightOut, W - 28f, W - 28f - COLUMN}) {
            float threshold = HgVerticalLayout.reflowThreshold(CARD_W, -length);
            assertTrue(threshold <= 0f && threshold >= -length);
            // AOSP's upward threshold, the card's height, is out of a sideways dismiss's reach.
            assertTrue(THUMB_H > length);
        }
    }

    /** Dismissing a card shortens the scroll range when the last row empties. */
    @Test public void scrollRangeShrinksWhenTheLastRowEmpties() {
        HgVerticalLayout l = layout(5, true);
        int five = l.maxScrollY();
        assertEquals(five, l.maxScrollY(5));
        assertEquals(five - ROW, l.maxScrollY(4), 1f); // a row less (each rounded)
        // At the bottom: the grid eases up to the shorter range; above it, it stays.
        assertEquals(l.maxScrollY(4), l.scrollAfterRemoval(five, 4), EPS);
        assertEquals(100f, l.scrollAfterRemoval(100f, 4), EPS);
        // Six to five cards: the last row keeps a card, the range stays.
        HgVerticalLayout six = layout(6, true);
        assertEquals(six.maxScrollY(), six.maxScrollY(5));
        assertEquals(six.maxScrollY(), six.scrollAfterRemoval(six.maxScrollY(), 5), EPS);
        // Down to two cards nothing scrolls.
        assertEquals(0f, layout(3, true).scrollAfterRemoval(300f, 2), EPS);
    }

    /** Of two cards, the one that stays glides to the lone card's centre as the other leaves. */
    @Test public void loneCardCentresWithTheRemoval() {
        float centred = (W - CARD_W) / 2f - 28;
        HgVerticalLayout two = layout(2, true);
        assertEquals(0f, two.centring(false), EPS);
        two.setRemoval(0.5f);
        assertEquals(0.5f, two.centring(false), EPS);
        assertEquals(0f, two.centring(true), EPS); // the dismissed card leaves from its slot
        assertEquals(centred / 2f, two.slotX(0), EPS);
        assertEquals((COLUMN + centred) / 2f, two.slotX(1), EPS);
        // The newer card dismissed: the older one reflows into the lone card's place.
        two.setRemoval(1f);
        assertEquals(centred, two.slotX(1, 1f, two.centring(false)), EPS);
        two.setRemoval(5f);
        assertEquals(1f, two.getRemoval(), EPS);
        // Once removed, one card is centred whatever the removal says.
        HgVerticalLayout one = layout(1, true);
        assertEquals(1f, one.centring(false), EPS);
        // A leaving card is never centred by the layout, even as the last one (a card swiped
        // while the other of two was still being removed must not jump to the centre).
        assertEquals(0f, one.centring(true), EPS);
        assertEquals(centred, one.slotX(0), EPS);
        // More cards: no centring.
        HgVerticalLayout three = layout(3, true);
        three.setRemoval(1f);
        assertEquals(0f, three.centring(false), EPS);
        assertEquals(COLUMN, three.slotX(1), EPS);
    }

    /** Few tasks start at the top too; a lone card is centred across. */
    @Test public void fewTasks() {
        HgVerticalLayout two = layout(2, true);
        assertEquals(0f, two.slotY(0), EPS);
        assertEquals(COLUMN, two.slotX(1), EPS);
        HgVerticalLayout one = layout(1, true);
        assertEquals((W - CARD_W) / 2f - 28, one.slotX(0), EPS);
        assertEquals(0f, one.slotY(0), EPS);
        assertEquals(-1, one.dismissSide(0));
    }

    /** The scroll ends when the last card plus the bottom padding meets the window's bottom. */
    @Test public void scrollRange() {
        assertEquals(0, layout(0, true).maxScrollY());
        assertEquals(0, layout(1, true).maxScrollY());
        assertEquals(0, layout(2, true).maxScrollY());
        int three = Math.round(TASK_TOP + ROW + THUMB_H
                + HgVerticalLayout.BOTTOM_RATIO * CARD_H - H);
        assertEquals(three, layout(3, true).maxScrollY());
        assertEquals(three, layout(4, true).maxScrollY());
        assertEquals(Math.round(three + ROW), layout(5, true).maxScrollY());
    }

    @Test public void visibility() {
        HgVerticalLayout l = layout(6, true);
        assertTrue(l.isVisible(0, 0, 0));
        assertTrue(l.isVisible(3, 0, 0)); // second row peeks in at the bottom
        assertFalse(l.isVisible(4, 0, 0));
        assertTrue(l.isVisible(4, 0, l.preloadMargin()));
        assertTrue(l.isFullyVisible(1, 0));
        assertFalse(l.isFullyVisible(2, 0));
        // Under the status bar counts as off screen (RecentsView clips the grid there).
        assertFalse(l.isFullyVisible(0, 50));
        float firstRowBottom = TASK_TOP + THUMB_H;
        assertTrue(l.isVisible(0, firstRowBottom - TOP - 1, 0));
        assertFalse(l.isVisible(0, firstRowBottom - TOP, 0));
        assertTrue(l.isVisible(4, ROW, 0));
        assertEquals(480f, l.preloadMargin(), EPS);
    }

    /** Scrolled above the first row's place, the header fades out within its height. */
    @Test public void headerFadesAboveTheTop() {
        HgVerticalLayout l = layout(6, true);
        assertEquals(TASK_TOP - HEADER, l.topEdge(), EPS);
        assertEquals(1f, l.headerAlpha(0, 0), EPS);
        assertEquals(1f, l.headerAlpha(1, 0), EPS);
        assertEquals(0.5f, l.headerAlpha(0, HEADER / 2f), EPS);
        assertEquals(0f, l.headerAlpha(0, HEADER), EPS);
        assertEquals(0f, l.headerAlpha(0, 900), EPS);
        // The second row is untouched until it reaches the top edge.
        assertEquals(1f, l.headerAlpha(2, ROW - 1), EPS);
        assertEquals(0.5f, l.headerAlpha(2, ROW + HEADER / 2f), 0.01f);
        // The overscroll at the top (negative scroll) keeps everything shown.
        assertEquals(1f, l.headerAlpha(0, -200), EPS);
    }

    /** MiuiHome's card fade: 1 - p^2 / 3, p in card heights above the top edge. */
    @Test public void cardFadesAboveTheTop() {
        HgVerticalLayout l = layout(6, true);
        assertEquals(1f, l.cardAlpha(0, 0), EPS);
        assertEquals(1f, l.cardAlpha(2, ROW / 2f), EPS);
        assertEquals(1f - 0.25f / 3f, l.cardAlpha(0, CARD_H / 2f), EPS);
        assertEquals(2f / 3f, l.cardAlpha(0, CARD_H), EPS);
        assertEquals(2f / 3f, l.cardAlpha(0, 3 * CARD_H), EPS);
    }

    /** Clear all and the hold button fling each card out by its own side. */
    @Test public void dismissSides() {
        HgVerticalLayout l = layout(5, true);
        assertEquals(-1, l.dismissSide(0));
        assertEquals(1, l.dismissSide(1));
        assertEquals(-1, l.dismissSide(4));
    }

    @Test public void actionsSitInTheBottomPadding() {
        HgVerticalLayout l = layout(5, true);
        int margin = Math.round((HgVerticalLayout.BOTTOM_RATIO * CARD_H - 144) / 2f);
        assertEquals(margin, l.actionsBottomMargin(144, 20));
        assertEquals(400, l.actionsBottomMargin(144, 400));
    }

    /** An upward drag scrolls toward older cards; the touch-down point stays put. */
    @Test public void scrollTranspose() {
        float[] m = new float[9];
        HgVerticalLayout.transpose(500, 1200, HgVerticalLayout.scrollSign(true), 1f, m);
        assertPoint(m, 500, 1200, 500, 1200);
        // Finger up 100: older cards are on the strip's left, so a drag to the right.
        assertPoint(m, 500, 1100, 600, 1200);
        assertPoint(m, 470, 1200, 500, 1170);
        HgVerticalLayout.transpose(500, 1200, HgVerticalLayout.scrollSign(false), 1f, m);
        assertPoint(m, 500, 1100, 400, 1200);
    }

    /** A sideways swipe toward the controller's side looks like an upward swipe. */
    @Test public void dismissTranspose() {
        float[] m = new float[9];
        HgVerticalLayout.dismissTranspose(300, 900, -1, m);
        assertPoint(m, 300, 900, 300, 900);
        assertPoint(m, 250, 900, 300, 850);
        assertPoint(m, 350, 900, 300, 950);
        HgVerticalLayout.dismissTranspose(300, 900, 1, m);
        assertPoint(m, 350, 900, 300, 850);
        // Vertical travel becomes sideways, which the dismiss detector ignores.
        assertPoint(m, 300, 960, 360, 900);
    }

    private static void assertPoint(float[] m, float x, float y, float ex, float ey) {
        assertEquals(ex, m[0] * x + m[1] * y + m[2], EPS);
        assertEquals(ey, m[3] * x + m[4] * y + m[5], EPS);
    }
}
