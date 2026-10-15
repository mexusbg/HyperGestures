package com.android.quickstep.interaction;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HgBackHapticsTest {
    /** The ready tick plays past 180 px of sideways travel (raw px, as MiuiHome), not before. */
    @Test public void readyTickPast180Px() {
        HgBackHapticTimer t = new HgBackHapticTimer();
        t.reset();
        assertFalse(t.onMove(40, true, 1000));   // armed at AOSP's drag slop: no tick yet
        assertFalse(t.onMove(180, true, 1010));  // MiuiHome: offset > 180
        assertTrue(t.onMove(181, true, 1020));
    }

    /** Only while a release would trigger back (the arrow is armed). */
    @Test public void readyTickOnlyWhileArmed() {
        HgBackHapticTimer t = new HgBackHapticTimer();
        t.reset();
        assertFalse(t.onMove(300, false, 1000)); // vertical abort: not armed
        assertTrue(t.onMove(320, true, 1050));
    }

    /** Once per gesture, as MiuiHome's mArrowFeedBackDone guard; again after a touch-down. */
    @Test public void readyTickOncePerGesture() {
        HgBackHapticTimer t = new HgBackHapticTimer();
        t.reset();
        assertTrue(t.onMove(200, true, 1000));
        assertFalse(t.onMove(250, true, 1100));
        assertFalse(t.onMove(100, true, 1200));
        assertFalse(t.onMove(250, true, 1300)); // pulled back and out again
        t.reset();
        assertTrue(t.onMove(200, true, 5000));
    }

    /** A release 140 ms or more after the ready tick plays the hand-up tick. */
    @Test public void handUpTickAfterTheBlock() {
        HgBackHapticTimer t = new HgBackHapticTimer();
        t.reset();
        t.onMove(200, true, 1000);
        assertTrue(t.onBackTriggered(1140));
        assertTrue(t.onBackTriggered(1600));
    }

    /** A quick flick (release < 140 ms after the ready tick) ticks only once, as in MiuiHome. */
    @Test public void handUpTickDroppedWithinTheBlock() {
        HgBackHapticTimer t = new HgBackHapticTimer();
        t.reset();
        t.onMove(200, true, 1000);
        assertFalse(t.onBackTriggered(1000));
        assertFalse(t.onBackTriggered(1139));
    }

    /** Back released before 180 px (no ready tick): the hand-up tick alone. */
    @Test public void handUpTickWithoutReadyTick() {
        HgBackHapticTimer t = new HgBackHapticTimer();
        t.reset();
        t.onMove(120, true, 1000);
        assertTrue(t.onBackTriggered(1010));
        t.onMove(400, true, 2000);
        t.reset();                               // the next gesture starts clean
        assertTrue(t.onBackTriggered(2010));
    }

    /** Haptic v2 (our phone): MiuiHome's 0 for both ticks, if the vibrator supports it. */
    @Test public void v2UsesEffectZero() {
        assertArrayEquals(new int[] {0, 0}, HgBackHaptics.effectIds("2.0", id -> true));
        assertArrayEquals(new int[] {-1, -1}, HgBackHaptics.effectIds("2.0", id -> id != 0));
    }

    /** Linear motor without v2: 162 / 163, only when both are supported. */
    @Test public void linearUsesReadyAndHandUpPair() {
        assertArrayEquals(new int[] {162, 163}, HgBackHaptics.effectIds("", id -> true));
        assertArrayEquals(new int[] {162, 163},
                HgBackHaptics.effectIds("1.0", id -> id == 162 || id == 163));
        assertArrayEquals(new int[] {-1, -1}, HgBackHaptics.effectIds("", id -> id == 162));
    }

    /** HyperOS's haptic_feedback_disable decides when it is an integer: on only at 0. */
    @Test public void hyperOsDisableKeyDecides() {
        assertTrue(HgBackHaptics.touchFeedbackOn("0", "0"));    // the owner's phone: vibrates
        assertTrue(HgBackHaptics.touchFeedbackOn(" 0 ", null));
        assertFalse(HgBackHaptics.touchFeedbackOn("1", "1"));
        assertFalse(HgBackHaptics.touchFeedbackOn("2", null));
        assertFalse(HgBackHaptics.touchFeedbackOn("-1", "1"));
    }

    /** Without an integer haptic_feedback_disable: AOSP's haptic_feedback_enabled, off only at 0. */
    @Test public void aospEnabledKeyOtherwise() {
        assertFalse(HgBackHaptics.touchFeedbackOn(null, "0"));
        assertFalse(HgBackHaptics.touchFeedbackOn("", "0"));
        assertFalse(HgBackHaptics.touchFeedbackOn("x", " 0"));
        assertTrue(HgBackHaptics.touchFeedbackOn(null, "1"));
        assertTrue(HgBackHaptics.touchFeedbackOn(null, "2"));   // anything but 0 counts as on
    }

    /** Neither key readable as an integer: on (AOSP's default). */
    @Test public void neitherKeyMeansOn() {
        assertTrue(HgBackHaptics.touchFeedbackOn(null, null));
        assertTrue(HgBackHaptics.touchFeedbackOn("", "junk"));
        assertEquals("haptic_feedback_disable", HgBackHaptics.KEY_DISABLE);
        assertEquals("haptic_feedback_enabled", HgBackHaptics.KEY_ENABLED);
    }

    /** No HyperOS effect: VibratorWrapper's click (-1) for both. */
    @Test public void nothingSupported() {
        assertArrayEquals(new int[] {-1, -1}, HgBackHaptics.effectIds(null, id -> false));
    }
}
