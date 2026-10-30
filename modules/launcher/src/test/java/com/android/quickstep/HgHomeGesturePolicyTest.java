package com.android.quickstep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.quickstep.GestureState.GestureEndTarget;

import org.junit.Test;

public class HgHomeGesturePolicyTest {
    @Test public void releasedSwipeUpOverHomeStaysOnHome() {
        assertEquals(GestureEndTarget.LAST_TASK,
                HgHomeGesturePolicy.homeTarget(GestureEndTarget.HOME, true));
    }

    @Test public void releasedSwipeUpOverAnAppStillGoesHome() {
        assertEquals(GestureEndTarget.HOME,
                HgHomeGesturePolicy.homeTarget(GestureEndTarget.HOME, false));
    }

    @Test public void nonHomeTargetsOverHomeAreUnchanged() {
        // RECENTS: trackpad swipe up; REJECT_HOME: display without a home gesture
        assertEquals(GestureEndTarget.RECENTS,
                HgHomeGesturePolicy.homeTarget(GestureEndTarget.RECENTS, true));
        assertEquals(GestureEndTarget.REJECT_HOME,
                HgHomeGesturePolicy.homeTarget(GestureEndTarget.REJECT_HOME, true));
    }

    @Test public void nonHomeTargetsOverAnAppAreUnchanged() {
        for (GestureEndTarget t : GestureEndTarget.values()) {
            if (t == GestureEndTarget.HOME) continue;
            assertEquals(t, HgHomeGesturePolicy.homeTarget(t, false));
        }
    }

    // --- recents attached behind the home app ---

    @Test public void overHomeRecentsStaysHiddenWhileSwipingUp() {
        // AOSP attaches from the first frame (the home window "is going away")
        assertFalse(HgHomeGesturePolicy.recentsAttached(true, true, null, false));
    }

    @Test public void overHomeHoldOrQuickSwitchShowsRecents() {
        assertTrue(HgHomeGesturePolicy.recentsAttached(true, true, null, true));
    }

    @Test public void overHomeRecentsAndNewTaskEndsShowRecents() {
        assertTrue(HgHomeGesturePolicy.recentsAttached(
                true, true, GestureEndTarget.RECENTS, false));
        assertTrue(HgHomeGesturePolicy.recentsAttached(
                true, true, GestureEndTarget.NEW_TASK, false));
    }

    @Test public void overHomeSwipeEndingOnHomeHidesRecents() {
        // LAST_TASK attaches recents in AOSP; over home it springs back to the home app
        assertFalse(HgHomeGesturePolicy.recentsAttached(
                true, true, GestureEndTarget.LAST_TASK, false));
        // also after a hold that was taken back down
        assertFalse(HgHomeGesturePolicy.recentsAttached(
                true, true, GestureEndTarget.LAST_TASK, true));
        assertFalse(HgHomeGesturePolicy.recentsAttached(
                false, true, GestureEndTarget.HOME, false));
        assertFalse(HgHomeGesturePolicy.recentsAttached(
                false, true, GestureEndTarget.REJECT_HOME, true));
    }

    @Test public void overAnAppAospDecides() {
        for (boolean aosp : new boolean[] {false, true}) {
            for (boolean byGesture : new boolean[] {false, true}) {
                assertEquals(aosp,
                        HgHomeGesturePolicy.recentsAttached(aosp, false, null, byGesture));
                for (GestureEndTarget t : GestureEndTarget.values()) {
                    assertEquals(aosp,
                            HgHomeGesturePolicy.recentsAttached(aosp, false, t, byGesture));
                }
            }
        }
    }

    // --- home window alpha ---

    @Test public void homeWindowUntouchedUntilRecentsShows() {
        assertEquals(1f, HgHomeGesturePolicy.homeWindowAlpha(false), 0f);
        assertEquals(0f, HgHomeGesturePolicy.homeWindowAlpha(true), 0f);
    }

    // --- end animation duration ---

    @Test public void springBackAfterHoldWaitsForTheHomeFade() {
        assertEquals(300, HgHomeGesturePolicy.endAnimationDuration(
                40, true, GestureEndTarget.LAST_TASK, 0f, 300));
        assertEquals(300, HgHomeGesturePolicy.endAnimationDuration(
                40, true, GestureEndTarget.LAST_TASK, 0.5f, 300));
        assertEquals(350, HgHomeGesturePolicy.endAnimationDuration(
                350, true, GestureEndTarget.LAST_TASK, 0f, 300));
    }

    @Test public void springBackWithHomeUntouchedEndsAtOnce() {
        // The home window never moved: nothing to animate, so the back key is not delayed.
        assertEquals(0, HgHomeGesturePolicy.endAnimationDuration(
                40, true, GestureEndTarget.LAST_TASK, 1f, 300));
        assertEquals(0, HgHomeGesturePolicy.endAnimationDuration(
                760, true, GestureEndTarget.LAST_TASK, 1f, 300));
    }

    @Test public void otherEndsKeepAospDuration() {
        for (GestureEndTarget t : GestureEndTarget.values()) {
            if (t != GestureEndTarget.LAST_TASK) {
                assertEquals(40, HgHomeGesturePolicy.endAnimationDuration(40, true, t, 0f, 300));
            }
            assertEquals(40, HgHomeGesturePolicy.endAnimationDuration(40, false, t, 0f, 300));
        }
    }

    // --- Shell's "will finish to home" hint (setWillFinishToHome) ---

    @Test public void overHomeRecentsEndWillFinishToRecents() {
        // getHomeTarget is LAST_TASK over home, so AOSP's hint is false; a RECENTS end must
        // still tell Shell it finishes to recents, or the finish returns to the home app
        assertTrue(HgHomeGesturePolicy.willFinishToHome(false, true, GestureEndTarget.RECENTS));
        assertTrue(HgHomeGesturePolicy.willFinishToHome(true, true, GestureEndTarget.RECENTS));
    }

    @Test public void overHomeEverythingElseKeepsTheHint() {
        // finger down (hold or not), release back on home, quick switch: AOSP's (false) hint
        assertFalse(HgHomeGesturePolicy.willFinishToHome(false, true, null));
        assertFalse(HgHomeGesturePolicy.willFinishToHome(
                false, true, GestureEndTarget.LAST_TASK));
        assertFalse(HgHomeGesturePolicy.willFinishToHome(
                false, true, GestureEndTarget.NEW_TASK));
        assertFalse(HgHomeGesturePolicy.willFinishToHome(
                false, true, GestureEndTarget.REJECT_HOME));
        assertTrue(HgHomeGesturePolicy.willFinishToHome(true, true, null));
    }

    @Test public void overAnAppTheHintIsAosps() {
        for (boolean aosp : new boolean[] {false, true}) {
            assertEquals(aosp, HgHomeGesturePolicy.willFinishToHome(aosp, false, null));
            for (GestureEndTarget t : GestureEndTarget.values()) {
                assertEquals(aosp, HgHomeGesturePolicy.willFinishToHome(aosp, false, t));
            }
        }
    }
    /** A released swipe up over the home app that AOSP sends HOME: back key after spring back. */
    @Test public void backOnReleaseOverHomeWhenAospGoesHome() {
        assertTrue(HgHomeGesturePolicy.backOnRelease(GestureEndTarget.HOME, true, true));
    }

    @Test public void noBackOnReleaseOverAnotherApp() {
        assertFalse(HgHomeGesturePolicy.backOnRelease(GestureEndTarget.HOME, false, true));
    }

    @Test public void noBackOnReleaseWhenTurnedOff() {
        assertFalse(HgHomeGesturePolicy.backOnRelease(GestureEndTarget.HOME, true, false));
    }

    /** RECENTS (trackpad) or REJECT_HOME (no home on this display) never send back. */
    @Test public void noBackForOtherHomeTargets() {
        assertFalse(HgHomeGesturePolicy.backOnRelease(GestureEndTarget.RECENTS, true, true));
        assertFalse(HgHomeGesturePolicy.backOnRelease(GestureEndTarget.REJECT_HOME, true, true));
    }

    @Test public void homeBackSettingDefaultsOn() {
        assertTrue(HgHomeGesturePolicy.homeBackEnabled(null));
        assertTrue(HgHomeGesturePolicy.homeBackEnabled("1"));
        assertFalse(HgHomeGesturePolicy.homeBackEnabled("0"));
    }

    @Test public void homeBackWaitsWhileTheRecentsAnimationRuns() {
        assertEquals(HgHomeGesturePolicy.HomeBackStep.WAIT,
                HgHomeGesturePolicy.homeBackStep(1100, 1000, true, 500));
    }

    /** A cleanup from before the release belongs to an earlier gesture. */
    @Test public void homeBackIgnoresAnEarlierCleanup() {
        assertEquals(HgHomeGesturePolicy.HomeBackStep.WAIT,
                HgHomeGesturePolicy.homeBackStep(1100, 1000, false, 900));
    }

    @Test public void homeBackSettlesAfterTheCleanup() {
        long cleaned = 1200;
        assertEquals(HgHomeGesturePolicy.HomeBackStep.WAIT,
                HgHomeGesturePolicy.homeBackStep(cleaned + 10, 1000, false, cleaned));
        assertEquals(HgHomeGesturePolicy.HomeBackStep.SEND,
                HgHomeGesturePolicy.homeBackStep(
                        cleaned + HgHomeGesturePolicy.HOME_BACK_SETTLE_MS, 1000, false, cleaned));
    }

    /** A new recents animation after this one was cleaned up: the user swiped again. */
    @Test public void homeBackGivesUpWhenANewGestureStarted() {
        assertEquals(HgHomeGesturePolicy.HomeBackStep.GIVE_UP,
                HgHomeGesturePolicy.homeBackStep(1300, 1000, true, 1200));
    }

    @Test public void homeBackGivesUpAfterTheTimeout() {
        assertEquals(HgHomeGesturePolicy.HomeBackStep.GIVE_UP,
                HgHomeGesturePolicy.homeBackStep(
                        1000 + HgHomeGesturePolicy.HOME_BACK_TIMEOUT_MS + 1, 1000, true, 0));
    }

    // --- the home window as a closing target of a swipe from an app ---

    @Test public void homeBehindATranslucentAppIsNotATaskCard() {
        // Viber's chat screen: Shell sends the home task as a closing target next to the app
        assertFalse(HgHomeGesturePolicy.isTaskCard(/* isHome= */ true, /* otherApp= */ true));
        assertTrue(HgHomeGesturePolicy.isTaskCard(/* isHome= */ false, /* otherApp= */ true));
    }

    @Test public void homeAloneIsTheTaskCard() {
        // a swipe over the home app itself
        assertTrue(HgHomeGesturePolicy.isTaskCard(/* isHome= */ true, /* otherApp= */ false));
    }

    @Test public void appsAreAlwaysTaskCards() {
        assertTrue(HgHomeGesturePolicy.isTaskCard(/* isHome= */ false, /* otherApp= */ false));
    }
}
