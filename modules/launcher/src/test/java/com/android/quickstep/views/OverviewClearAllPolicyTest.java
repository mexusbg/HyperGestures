package com.android.quickstep.views;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OverviewClearAllPolicyTest {
    @Test public void phone_clearAllInActionsRowOnly() {
        assertTrue(OverviewClearAllPolicy.inActionsRow(false));
        assertTrue(OverviewClearAllPolicy.showActionsButton(true, false));
        assertFalse(OverviewClearAllPolicy.showListButton(true, false));
    }

    @Test public void largeScreen_inListButtonAsUpstream() {
        assertFalse(OverviewClearAllPolicy.inActionsRow(true));
        assertFalse(OverviewClearAllPolicy.showActionsButton(true, true));
        assertTrue(OverviewClearAllPolicy.showListButton(true, true));
    }

    /** States without clear all (modal, split select, background app) show neither button. */
    @Test public void stateWithoutClearAll_neitherButton() {
        for (boolean largeScreen : new boolean[] {false, true}) {
            assertFalse(OverviewClearAllPolicy.showActionsButton(false, largeScreen));
            assertFalse(OverviewClearAllPolicy.showListButton(false, largeScreen));
        }
    }

    @Test public void canClearAll_needsTasksAndNoDismissalRunning() {
        assertTrue(OverviewClearAllPolicy.canClearAll(3, false));
        assertFalse(OverviewClearAllPolicy.canClearAll(0, false));
        assertFalse(OverviewClearAllPolicy.canClearAll(3, true));
    }
}
