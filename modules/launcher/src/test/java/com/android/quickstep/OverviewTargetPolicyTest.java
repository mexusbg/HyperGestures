package com.android.quickstep;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OverviewTargetPolicyTest {
    @Test public void noOwnHome_alwaysFallback() {
        assertTrue(OverviewTargetPolicy.useFallback(false, false, true, false));
        assertTrue(OverviewTargetPolicy.useFallback(false, false, false, false));
        assertTrue(OverviewTargetPolicy.useFallback(false, true, true, false));
    }

    @Test public void ownHome_matchesAospDecision() {
        // AOSP: integrated overview iff !homeDisabled && (defaultHome == null || isDefaultHome)
        assertFalse(OverviewTargetPolicy.useFallback(true, false, true, false));
        assertFalse(OverviewTargetPolicy.useFallback(true, false, false, true));
        assertTrue(OverviewTargetPolicy.useFallback(true, false, false, false));
        assertTrue(OverviewTargetPolicy.useFallback(true, true, true, false));
    }
}
