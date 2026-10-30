package com.android.quickstep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class HgLayoutPreviewTest {
    private static final float EPS = 1e-4f;

    /** Vertical: two columns, newest top-left, rows in order. */
    @Test public void verticalIsTwoColumnsAtRest() {
        List<HgLayoutPreview.Card> f = HgLayoutPreview.frame(HgLayoutPreview.VERTICAL, 0f);
        assertEquals(8, f.size());
        assertEquals(f.get(0).y, f.get(1).y, EPS); // one row
        assertTrue(f.get(0).x < f.get(1).x); // left, then right
        assertTrue(f.get(2).y > f.get(0).y); // next row below
        assertEquals(f.get(0).x, f.get(2).x, EPS); // same column
    }

    /** Vertical scrolls up and comes back: the same place at the start and end of a cycle. */
    @Test public void verticalLoops() {
        float start = HgLayoutPreview.frame(HgLayoutPreview.VERTICAL, 0f).get(0).y;
        float mid = HgLayoutPreview.frame(HgLayoutPreview.VERTICAL, 0.55f).get(0).y;
        float end = HgLayoutPreview.frame(HgLayoutPreview.VERTICAL, 0.999f).get(0).y;
        assertTrue(mid < start);
        assertEquals(start, end, 1e-3f);
    }

    /** Horizontal: one row, a page further on in the middle of the cycle. */
    @Test public void horizontalPagesSideways() {
        List<HgLayoutPreview.Card> rest = HgLayoutPreview.frame(HgLayoutPreview.HORIZONTAL, 0f);
        List<HgLayoutPreview.Card> paged =
                HgLayoutPreview.frame(HgLayoutPreview.HORIZONTAL, 0.4f);
        assertEquals(rest.get(0).y, rest.get(1).y, EPS);
        assertTrue(rest.get(1).x > rest.get(0).x);
        // A page on: the second card where the first was.
        assertEquals(rest.get(0).x, paged.get(1).x, 1e-3f);
    }

    /** Stack: drawn back to front, the front card full size, the ones behind smaller. */
    @Test public void stackDrawsOlderCardsFirstAndSmaller() {
        List<HgLayoutPreview.Card> f = HgLayoutPreview.frame(HgLayoutPreview.STACK, 0.3f);
        assertEquals(4, f.size());
        for (int i = 1; i < f.size(); i++) {
            assertTrue("drawn back to front", f.get(i).w >= f.get(i - 1).w - EPS);
        }
    }

    /** Every card stays a sane size, at any moment of every mode. */
    @Test public void cardsStaySane() {
        for (int mode : new int[] {HgLayoutPreview.VERTICAL, HgLayoutPreview.HORIZONTAL,
                HgLayoutPreview.STACK}) {
            for (float t = 0; t < 1; t += 0.01f) {
                for (HgLayoutPreview.Card c : HgLayoutPreview.frame(mode, t)) {
                    assertTrue(c.w > 0 && c.h > 0);
                    assertTrue(c.alpha >= 0 && c.alpha <= 1);
                }
            }
        }
    }

    @Test public void modeFromSetting() {
        assertEquals(HgLayoutPreview.VERTICAL, HgLayoutPreview.mode("0"));
        assertEquals(HgLayoutPreview.HORIZONTAL, HgLayoutPreview.mode("1"));
        assertEquals(HgLayoutPreview.STACK, HgLayoutPreview.mode("2"));
        assertEquals("0", HgLayoutPreview.value(HgLayoutPreview.VERTICAL));
        assertEquals("2", HgLayoutPreview.value(HgLayoutPreview.STACK));
        // Unreadable or unknown: nothing selected.
        assertEquals(-1, HgLayoutPreview.mode(""));
        assertEquals(-1, HgLayoutPreview.mode(null));
    }
}
