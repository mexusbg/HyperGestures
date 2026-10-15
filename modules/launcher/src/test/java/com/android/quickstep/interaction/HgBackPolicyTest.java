package com.android.quickstep.interaction;

import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_BOUNCER_SHOWING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_DEVICE_DREAMING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_SCREEN_PINNING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;

import org.junit.Test;

public class HgBackPolicyTest {
    private static final long AWAKE = 1L << 28; // SYSUI_STATE_AWAKE; only the blocking bits matter

    @Test public void allowedInGesturalModeWithPlainAppState() {
        assertTrue(HgBackPolicy.isAllowed(true, AWAKE));
    }

    @Test public void notInButtonModes() {
        assertFalse(HgBackPolicy.isAllowed(false, AWAKE));
    }

    @Test public void notOnKeyguardUnlessBouncer() {
        assertFalse(HgBackPolicy.isAllowed(true, AWAKE | SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING));
        assertTrue(HgBackPolicy.isAllowed(true,
                AWAKE | SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING | SYSUI_STATE_BOUNCER_SHOWING));
    }

    @Test public void notWhileDreamingOrPinned() {
        assertFalse(HgBackPolicy.isAllowed(true, AWAKE | SYSUI_STATE_DEVICE_DREAMING));
        assertFalse(HgBackPolicy.isAllowed(true, AWAKE | SYSUI_STATE_SCREEN_PINNING));
    }

    // 1200x2608 display, 48 px edge, 144 px bottom gesture area (nezha values are resource-driven)
    @Test public void edgesOutsideBottomAreaStartBack() {
        assertTrue(HgBackPolicy.isBackEdgeDown(10, 1300, 1200, 2608, 48, 48, 144));
        assertTrue(HgBackPolicy.isBackEdgeDown(1195, 1300, 1200, 2608, 48, 48, 144));
    }

    @Test public void middleOfScreenIsNotBack() {
        assertFalse(HgBackPolicy.isBackEdgeDown(600, 1300, 1200, 2608, 48, 48, 144));
        assertFalse(HgBackPolicy.isBackEdgeDown(49, 1300, 1200, 2608, 48, 48, 144));
    }

    // Back sensitivity scales each side on its own: 1.33 left (63 px), 0.75 right (36 px).
    @Test public void eachSideUsesItsOwnWidth() {
        assertTrue(HgBackPolicy.isBackEdgeDown(63, 1300, 1200, 2608, 63, 36, 144));
        assertFalse(HgBackPolicy.isBackEdgeDown(64, 1300, 1200, 2608, 63, 36, 144));
        assertTrue(HgBackPolicy.isBackEdgeDown(1164, 1300, 1200, 2608, 63, 36, 144));
        assertFalse(HgBackPolicy.isBackEdgeDown(1163, 1300, 1200, 2608, 63, 36, 144));
    }

    @Test public void bottomGestureAreaIsNeverBack() {
        assertFalse(HgBackPolicy.isBackEdgeDown(10, 2600, 1200, 2608, 48, 48, 144));
        assertFalse(HgBackPolicy.isBackEdgeDown(1195, 2464, 1200, 2608, 48, 48, 144));
    }

    /** AOSP semantics: (int) (inset x scale); the default 1.0 leaves the system inset alone. */
    @Test public void sensitivityScalesTheSystemInset() {
        assertEquals(48, HgBackPolicy.scaledEdgeWidth(48, 1f));
        assertEquals(28, HgBackPolicy.scaledEdgeWidth(48, 0.6f));   // 28.8 truncated
        assertEquals(36, HgBackPolicy.scaledEdgeWidth(48, 0.75f));
        assertEquals(63, HgBackPolicy.scaledEdgeWidth(48, 1.33f));  // 63.84 truncated
        assertEquals(79, HgBackPolicy.scaledEdgeWidth(48, 1.66f));
    }

    /** Values outside AOSP Settings' slider are clamped to its ends; garbage means 1.0. */
    @Test public void sensitivityIsClampedToTheSlider() {
        assertEquals(0.6f, HgBackPolicy.insetScale(0f), 0f);
        assertEquals(0.6f, HgBackPolicy.insetScale(-2f), 0f);
        assertEquals(1.66f, HgBackPolicy.insetScale(10f), 0f);
        assertEquals(1f, HgBackPolicy.insetScale(Float.NaN), 0f);
        assertEquals(1f, HgBackPolicy.insetScale(Float.POSITIVE_INFINITY), 0f);
        assertEquals(1.33f, HgBackPolicy.insetScale(1.33f), 0f);
        assertEquals(28, HgBackPolicy.scaledEdgeWidth(48, 0f));
        assertEquals(79, HgBackPolicy.scaledEdgeWidth(48, 5f));
    }

    /** The settings page's slider shows the stop nearest the applied scale; 1.0 is the default. */
    @Test public void sensitivitySliderStops() {
        assertEquals(1f, HgBackPolicy.SENSITIVITY_STEPS[HgBackPolicy.DEFAULT_SENSITIVITY_STEP], 0f);
        for (int i = 0; i < HgBackPolicy.SENSITIVITY_STEPS.length; i++) {
            assertEquals(i, HgBackPolicy.sensitivityStep(HgBackPolicy.SENSITIVITY_STEPS[i]));
        }
        assertEquals(2, HgBackPolicy.sensitivityStep(1.1f));
        assertEquals(3, HgBackPolicy.sensitivityStep(1.2f));
        assertEquals(0, HgBackPolicy.sensitivityStep(0f));   // clamped to 0.6
        assertEquals(4, HgBackPolicy.sensitivityStep(9f));   // clamped to 1.66
        assertEquals(2, HgBackPolicy.sensitivityStep(Float.NaN));
        // The slider's ends are the clamp's.
        assertEquals(HgBackPolicy.MIN_INSET_SCALE, HgBackPolicy.SENSITIVITY_STEPS[0], 0f);
        assertEquals(HgBackPolicy.MAX_INSET_SCALE, HgBackPolicy.SENSITIVITY_STEPS[4], 0f);
    }

    @Test public void edgeDownOutsideExclusionStartsBack() {
        assertTrue(HgBackPolicy.startsBack(true, false));
    }

    @Test public void edgeDownInsideExclusionIsLeftToTheApp() {
        // e.g. a seek bar thumb at the left or right edge that the app excluded
        assertFalse(HgBackPolicy.startsBack(true, true));
    }

    @Test public void notAnEdgeDownNeverStartsBack() {
        assertFalse(HgBackPolicy.startsBack(false, false));
        assertFalse(HgBackPolicy.startsBack(false, true));
    }

    @Test public void neverCommitDuringRecentsAnimation() {
        assertFalse(HgBackPolicy.canCommit(true, 10_000, 0));
        assertFalse(HgBackPolicy.canCommit(true, 10_000, 1_000));
        assertTrue(HgBackPolicy.canCommit(false, 10_000, 0));
    }

    @Test public void noCommitRightAfterRecentsCleanUp() {
        assertFalse(HgBackPolicy.canCommit(false, 10_000, 10_000 - 100));
        assertFalse(HgBackPolicy.canCommit(false, 10_000,
                10_000 - HgBackPolicy.RECENTS_COOLDOWN_MS + 1));
    }

    @Test public void commitOnceCooldownHasPassed() {
        assertTrue(HgBackPolicy.canCommit(false, 10_000, 10_000 - 400));
        assertTrue(HgBackPolicy.canCommit(false, 10_000, 10_000 - 5_000));
    }

    @Test public void commitWhenRecentsNeverCleanedUp() {
        assertTrue(HgBackPolicy.canCommit(false, 10_000, 0));
        assertTrue(HgBackPolicy.canCommit(false, 50, 0));
    }

    @Test public void cooldownIs400ms() {
        assertEquals(400, HgBackPolicy.RECENTS_COOLDOWN_MS);
    }

    @Test public void backPathDefaultsToKey() {
        assertEquals(HgBackPolicy.BackPath.KEY, HgBackPolicy.backPath(null));
        assertEquals(HgBackPolicy.BackPath.KEY, HgBackPolicy.backPath("key"));
        assertEquals(HgBackPolicy.BackPath.KEY, HgBackPolicy.backPath("garbage"));
        assertEquals(HgBackPolicy.BackPath.KEY, HgBackPolicy.backPath(""));
    }

    @Test public void backPathEventAndNullAreOptIn() {
        assertEquals(HgBackPolicy.BackPath.EVENT, HgBackPolicy.backPath("event"));
        assertEquals(HgBackPolicy.BackPath.NULL_EVENT, HgBackPolicy.backPath("null"));
    }

    @Test public void backPathIsExactLowercaseOnly() {
        assertEquals(HgBackPolicy.BackPath.KEY, HgBackPolicy.backPath("EVENT"));
        assertEquals(HgBackPolicy.BackPath.KEY, HgBackPolicy.backPath("Null"));
        assertEquals(HgBackPolicy.BackPath.KEY, HgBackPolicy.backPath(" event"));
    }

    /** The arrow panel idle (GONE) or not added yet: the overlay window is hidden. */
    @Test public void overlayHiddenWhileArrowIdle() {
        assertEquals(View.INVISIBLE, HgBackPolicy.overlayVisibility(true, View.GONE));
        assertEquals(View.INVISIBLE, HgBackPolicy.overlayVisibility(true, View.INVISIBLE));
        assertEquals(View.INVISIBLE, HgBackPolicy.overlayVisibility(true));
    }

    /** The arrow panel shown for an accepted swipe (or its exit animation): the window shows. */
    @Test public void overlayShownWhileArrowVisible() {
        assertEquals(View.VISIBLE, HgBackPolicy.overlayVisibility(true, View.VISIBLE));
        assertEquals(View.VISIBLE, HgBackPolicy.overlayVisibility(true, View.GONE, View.VISIBLE));
    }

    /** An edge tap shows the panel on touch-down, but the window stays hidden until armed. */
    @Test public void overlayHiddenUntilArmed() {
        assertEquals(View.INVISIBLE, HgBackPolicy.overlayVisibility(false, View.VISIBLE));
        assertEquals(View.INVISIBLE, HgBackPolicy.overlayVisibility(false, View.GONE, View.VISIBLE));
    }

    /** MIUI back arrow: a slow release goes back only past 180 px inward (raw px, strict). */
    @Test public void commitPastArmTravel() {
        assertFalse(HgBackPolicy.commitsOnRelease(false, true, 100, 0.5f, true));
        assertFalse(HgBackPolicy.commitsOnRelease(false, true, 180, 0.5f, true));
        assertTrue(HgBackPolicy.commitsOnRelease(false, true, 181, 0.5f, true));
        assertTrue(HgBackPolicy.commitsOnRelease(false, true, 600, 0f, true));
    }

    /** A flick faster than 2 px/ms inward goes back before 180 px (strict). */
    @Test public void commitOnFastRelease() {
        assertFalse(HgBackPolicy.commitsOnRelease(false, true, 60, 2f, true));
        assertTrue(HgBackPolicy.commitsOnRelease(false, true, 60, 2.01f, true));
        assertTrue(HgBackPolicy.commitsOnRelease(false, true, 60, 5f, true));
    }

    /** The right edge counts inward as leftward: negative screen dx and velocity. */
    @Test public void commitFromTheRightEdge() {
        assertTrue(HgBackPolicy.commitsOnRelease(false, false, -181, -0.5f, true));
        assertFalse(HgBackPolicy.commitsOnRelease(false, false, -180, -0.5f, true));
        assertTrue(HgBackPolicy.commitsOnRelease(false, false, -60, -2.5f, true));
        assertFalse(HgBackPolicy.commitsOnRelease(false, false, -60, -1f, true));
    }

    /** Travel or speed toward the edge never goes back, on either side. */
    @Test public void noCommitOutward() {
        assertFalse(HgBackPolicy.commitsOnRelease(false, true, -300, -5f, true));
        assertFalse(HgBackPolicy.commitsOnRelease(false, false, 300, 5f, true));
        assertFalse(HgBackPolicy.commitsOnRelease(false, true, 60, -5f, true));
        assertFalse(HgBackPolicy.commitsOnRelease(false, false, -60, 5f, true));
    }

    /** A swipe AOSP's rules disarmed (pulled back, or mostly vertical) never goes back. */
    @Test public void noCommitWhenDisarmed() {
        assertFalse(HgBackPolicy.commitsOnRelease(false, true, 400, 5f, false));
        assertFalse(HgBackPolicy.commitsOnRelease(false, false, -400, -5f, false));
    }

    /** A shown chevron (HgBackArrowMath.isArmed) always means a release goes back. */
    @Test public void armedChevronAlwaysCommits() {
        for (float travel = 0; travel <= 1200; travel += 0.5f) {
            for (boolean left : new boolean[] {true, false}) {
                float dx = left ? travel : -travel;
                if (HgBackArrowMath.isArmed(travel, true)) {
                    assertTrue(HgBackPolicy.commitsOnRelease(false, left, dx, 0f, true));
                }
            }
        }
    }

    /**
     * A chevron armed at the last MOVE goes back even if the UP's own x lands under 180 px or
     * its velocity is low (the user saw it and felt the ready tick).
     */
    @Test public void armedAtLastMoveCommits() {
        assertTrue(HgBackPolicy.commitsOnRelease(true, true, 179, 0f, true));
        assertTrue(HgBackPolicy.commitsOnRelease(true, false, -179, 0f, true));
        assertFalse(HgBackPolicy.commitsOnRelease(false, true, 179, 0f, true));
        assertFalse(HgBackPolicy.commitsOnRelease(false, false, -179, 0f, true));
    }

    /** Not armed at the last MOVE: the travel and velocity rule alone decides. */
    @Test public void unarmedFallsBackToTheRule() {
        assertTrue(HgBackPolicy.commitsOnRelease(false, true, 181, 0f, true));
        assertTrue(HgBackPolicy.commitsOnRelease(false, true, 60, 2.5f, true));
        assertFalse(HgBackPolicy.commitsOnRelease(false, true, 60, 1.5f, true));
        assertFalse(HgBackPolicy.commitsOnRelease(false, true, 181, 3f, false));
    }
}
