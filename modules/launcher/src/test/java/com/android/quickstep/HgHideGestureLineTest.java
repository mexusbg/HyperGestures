package com.android.quickstep;

import static com.android.quickstep.HgHideGestureLine.HIDE;
import static com.android.quickstep.HgHideGestureLine.KEEP;
import static com.android.quickstep.HgHideGestureLine.SHOW;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HgHideGestureLineTest {
    /** Opt-in: on only when exactly "1"; unset, "0" and anything else leave the line visible. */
    @Test public void offUnlessOne() {
        assertTrue(HgHideGestureLine.isOn("1"));
        assertFalse(HgHideGestureLine.isOn((String) null));
        assertFalse(HgHideGestureLine.isOn("0"));
        assertFalse(HgHideGestureLine.isOn(""));
        assertFalse(HgHideGestureLine.isOn("true"));
    }

    @Test public void onRegistersUnlessAlreadyEnabled() {
        assertEquals(HIDE, HgHideGestureLine.decide(true, false, false));
        // Registered but disabled, e.g. by the module's restore.sh: enabled again.
        assertEquals(HIDE, HgHideGestureLine.decide(true, true, false));
        assertEquals(KEEP, HgHideGestureLine.decide(true, true, true));
    }

    @Test public void offRemovesAnyRegisteredOverlay() {
        assertEquals(SHOW, HgHideGestureLine.decide(false, true, true));
        assertEquals(SHOW, HgHideGestureLine.decide(false, true, false));
        assertEquals(KEEP, HgHideGestureLine.decide(false, false, false));
    }

    /** The page's line: SystemUI draws a change made in this boot only after a reboot. */
    @Test public void statusFollowsOverlayInfoAndTheBootOfTheChange() {
        assertEquals(HgHideGestureLine.STATUS_HIDDEN,
                HgHideGestureLine.status(true, true, true, false));
        assertEquals(HgHideGestureLine.STATUS_HIDDEN_AFTER_REBOOT,
                HgHideGestureLine.status(true, true, true, true));
        assertEquals(HgHideGestureLine.STATUS_NOT_APPLIED,
                HgHideGestureLine.status(true, true, false, true));
        assertEquals(HgHideGestureLine.STATUS_NOT_APPLIED,
                HgHideGestureLine.status(true, false, false, false));
        assertEquals(HgHideGestureLine.STATUS_SHOWN_AFTER_REBOOT,
                HgHideGestureLine.status(false, false, false, true));
        assertEquals(HgHideGestureLine.STATUS_NONE,
                HgHideGestureLine.status(false, false, false, false));
        assertEquals(HgHideGestureLine.STATUS_NOT_APPLIED,
                HgHideGestureLine.status(false, true, true, false));
    }

    /** Only the two colours, fully transparent; never dimen/navigation_handle_radius. */
    @Test public void overlaysOnlyTheHandleColours() {
        assertArrayEquals(new String[] {
                "com.android.systemui:color/navigation_bar_home_handle_light_color",
                "com.android.systemui:color/navigation_bar_home_handle_dark_color"},
                HgHideGestureLine.COLORS);
        assertEquals(0, HgHideGestureLine.TRANSPARENT);
        assertEquals("HgHideGestureLine", HgHideGestureLine.OVERLAY_NAME);
    }
}
