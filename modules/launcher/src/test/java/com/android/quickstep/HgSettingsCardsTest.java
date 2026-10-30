package com.android.quickstep;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HgSettingsCardsTest {
    private static final float OUTER = 24f;
    private static final float INNER = 4f;

    @Test public void rowsOfOneSectionJoin() {
        Object section = new Object();
        assertTrue(HgSettingsCards.joins(true, section, section));
    }

    @Test public void aRowBelowAHeaderStartsACard() {
        Object section = new Object();
        assertFalse(HgSettingsCards.joins(false, null, section));
    }

    @Test public void rowsOfTwoSectionsDoNotJoin() {
        assertFalse(HgSettingsCards.joins(true, new Object(), new Object()));
    }

    @Test public void aLoneRowIsRoundAllAround() {
        assertArrayEquals(new float[] {24, 24, 24, 24, 24, 24, 24, 24},
                HgSettingsCards.radii(false, false, OUTER, INNER), 0f);
    }

    @Test public void theFirstRowIsRoundOnTop() {
        assertArrayEquals(new float[] {24, 24, 24, 24, 4, 4, 4, 4},
                HgSettingsCards.radii(false, true, OUTER, INNER), 0f);
    }

    @Test public void theLastRowIsRoundBelow() {
        assertArrayEquals(new float[] {4, 4, 4, 4, 24, 24, 24, 24},
                HgSettingsCards.radii(true, false, OUTER, INNER), 0f);
    }

    @Test public void aMiddleRowIsBarelyRound() {
        assertArrayEquals(new float[] {4, 4, 4, 4, 4, 4, 4, 4},
                HgSettingsCards.radii(true, true, OUTER, INNER), 0f);
    }
}
