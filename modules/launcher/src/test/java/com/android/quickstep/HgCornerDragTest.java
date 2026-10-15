package com.android.quickstep;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HgCornerDragTest {
    /** MiuiHome's springs: response (s) to Android stiffness, (2 pi / response)^2. */
    @Test public void stiffnessFromMiuiResponse() {
        assertEquals(438.6f, HgCornerDrag.stiffness(HgCornerDrag.HOLD_RESPONSE), 0.1f);
        assertEquals(194.9f, HgCornerDrag.stiffness(0.45f), 0.1f);
        assertEquals(130.5f, HgCornerDrag.stiffness(0.55f), 0.1f);
        assertEquals(631.7f, HgCornerDrag.stiffness(0.25f), 0.1f);
    }

    @Test public void heldCornerToTarget() {
        assertEquals(HgCornerDrag.Target.SPLIT, HgCornerDrag.target(-1));
        assertEquals(HgCornerDrag.Target.FLOATING, HgCornerDrag.target(1));
        assertEquals(HgCornerDrag.Target.NONE, HgCornerDrag.target(0));
    }
}
