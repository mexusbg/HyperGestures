package com.android.quickstep;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

/** Which closing home targets the recents animation hides (HgHomeUnderRecents). */
public class HgHomeUnderRecentsTest {
    private static final int OPENING = 0; // RemoteAnimationTarget.MODE_OPENING
    private static final int CLOSING = 1; // RemoteAnimationTarget.MODE_CLOSING
    private static final int STANDARD = 1; // WindowConfiguration.ACTIVITY_TYPE_STANDARD
    private static final int HOME = 2; // WindowConfiguration.ACTIVITY_TYPE_HOME
    private static final int UNDEFINED = 0; // ACTIVITY_TYPE_UNDEFINED

    /**
     * HyperOS's split with one app left (minimized: the app on top, home under it; owner,
     * 2026-11-03): home closes along with the app and stayed drawn over recents.
     */
    @Test
    public void homeClosingWithAnApp_hidden() {
        assertArrayEquals(new int[] {2},
                HgHomeUnderRecents.homesToHide(
                        new int[] {CLOSING, CLOSING, CLOSING},
                        new int[] {STANDARD, UNDEFINED, HOME},
                        new boolean[] {true, false, true}));
    }

    /** In HyperOS's split the app's task reports no activity type (21:48, Slack). */
    @Test
    public void homeClosingWithAnUntypedApp_hidden() {
        assertArrayEquals(new int[] {1},
                HgHomeUnderRecents.homesToHide(
                        new int[] {CLOSING, CLOSING},
                        new int[] {UNDEFINED, HOME},
                        new boolean[] {true, true}));
    }

    /** Recents over the third-party home: AOSP's own case, home is all there is. */
    @Test
    public void homeAlone_leftAsIs() {
        assertArrayEquals(new int[0],
                HgHomeUnderRecents.homesToHide(
                        new int[] {CLOSING}, new int[] {HOME}, new boolean[] {true}));
    }

    @Test
    public void appWithoutHome_nothingHidden() {
        assertArrayEquals(new int[0],
                HgHomeUnderRecents.homesToHide(
                        new int[] {CLOSING}, new int[] {STANDARD}, new boolean[] {true}));
    }

    /** An opening home (going home) is never hidden. */
    @Test
    public void openingHome_leftAsIs() {
        assertArrayEquals(new int[0],
                HgHomeUnderRecents.homesToHide(
                        new int[] {CLOSING, OPENING}, new int[] {STANDARD, HOME},
                        new boolean[] {true, true}));
    }

    /** A closing task with no activity (a split root) is no app. */
    @Test
    public void homeWithOnlyASplitRoot_leftAsIs() {
        assertArrayEquals(new int[0],
                HgHomeUnderRecents.homesToHide(
                        new int[] {CLOSING, CLOSING}, new int[] {UNDEFINED, HOME},
                        new boolean[] {false, true}));
    }
}
