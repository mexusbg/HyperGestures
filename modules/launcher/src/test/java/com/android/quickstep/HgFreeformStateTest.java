package com.android.quickstep;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HgFreeformStateTest {
    /** The gesture's settle and recents' DEFAULT state send one broadcast, not two. */
    @Test public void sameStateWithinASecondIsARepeat() {
        assertTrue(HgFreeformState.isRepeat("toRecents", 1500, "toRecents", 1000));
    }

    @Test public void laterOrDifferentStateIsSent() {
        assertFalse(HgFreeformState.isRepeat("toRecents", 2500, "toRecents", 1000));
        assertFalse(HgFreeformState.isRepeat("toHome", 1100, "toRecents", 1000));
        assertFalse(HgFreeformState.isRepeat("toRecents", 1000, null, 0));
    }
}
