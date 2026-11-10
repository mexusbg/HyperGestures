package com.android.quickstep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Where the lock badge sits on a card's header icon (owner, 2026-11-10: "move the locked indicator
 * inside the app icons"): a disc in the icon's bottom-end corner (HgTaskLockBadge.geometry).
 */
public class HgTaskLockBadgeTest {
    private static final float S = 90f; // a 30 dp icon at 3x

    @Test public void ltr_bottomRightCorner() {
        float[] g = HgTaskLockBadge.geometry(S, false);
        assertTrue("right half", g[0] > S / 2);
        assertTrue("bottom half", g[1] > S / 2);
        assertEquals(HgTaskLockBadge.BADGE_FRACTION * S, g[2], 1e-3);
    }

    @Test public void rtl_bottomLeftCorner() {
        float[] ltr = HgTaskLockBadge.geometry(S, false);
        float[] rtl = HgTaskLockBadge.geometry(S, true);
        assertEquals(S - ltr[0], rtl[0], 1e-3);
        assertEquals(ltr[1], rtl[1], 1e-3);
    }

    /** The refused-dismiss wobble's peak size still fits inside the icon (nothing is cut off). */
    @Test public void nudgedDisc_staysInsideTheIcon() {
        for (boolean rtl : new boolean[] {false, true}) {
            float[] g = HgTaskLockBadge.geometry(S, rtl);
            float r = g[2] * HgTaskLockBadge.NUDGE_SCALE;
            assertTrue(g[0] - r >= -1e-3 && g[0] + r <= S + 1e-3);
            assertTrue(g[1] - r >= -1e-3 && g[1] + r <= S + 1e-3);
        }
    }
}
