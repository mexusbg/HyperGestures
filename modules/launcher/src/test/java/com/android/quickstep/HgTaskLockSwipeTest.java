package com.android.quickstep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HgTaskLockSwipeTest {
    private static final float ARM = 168f; // ARM_DP at 3x

    @Test public void upwardAndNoTravelDoNotMoveTheCard() {
        assertEquals(0f, HgTaskLockSwipeController.cardTravel(0f, ARM), 0f);
        assertEquals(0f, HgTaskLockSwipeController.cardTravel(-50f, ARM), 0f);
    }

    /** The card follows half the finger up to the arm point. */
    @Test public void followsHalfTheFingerUpToTheArmPoint() {
        assertEquals(50f, HgTaskLockSwipeController.cardTravel(100f, ARM), 0.001f);
        assertEquals(ARM, HgTaskLockSwipeController.cardTravel(2 * ARM, ARM), 0.001f);
    }

    /** Past the arm point it moves much less, but still moves (no hard stop). */
    @Test public void resistsPastTheArmPoint() {
        float atArm = HgTaskLockSwipeController.cardTravel(2 * ARM, ARM);
        float beyond = HgTaskLockSwipeController.cardTravel(2 * ARM + 200f, ARM);
        assertTrue(beyond > atArm);
        assertTrue(beyond - atArm < 0.5f * 200f * HgTaskLockSwipeController.FOLLOW);
    }
}
